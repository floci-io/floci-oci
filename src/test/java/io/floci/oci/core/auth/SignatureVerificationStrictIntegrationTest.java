package io.floci.oci.core.auth;

import io.floci.oci.testing.OciRequestSigner;
import io.floci.oci.testing.SecurityTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

import static io.floci.oci.core.auth.SignedRequests.TENANCY;
import static io.floci.oci.core.auth.SignedRequests.signed;
import static io.floci.oci.core.auth.SignedRequests.signedJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code signature-verification=strict} with {@code require-signature=true}: every request must be
 * signed with a known key, and a trusted public key bootstraps the first user and API key.
 */
@QuarkusTest
@TestProfile(SignatureVerificationStrictIntegrationTest.StrictProfile.class)
class SignatureVerificationStrictIntegrationTest {

    /**
     * A fixture key, not one generated here: the profile's config can be read in another class loader
     * than the test's, so both must find the same key on disk. {@code openssl pkey -pubout -outform DER
     * | openssl md5 -c} gives its fingerprint.
     */
    private static final Path TRUSTED_PUBLIC = Path.of("src/test/resources/auth/trusted-key.pub").toAbsolutePath();
    private static final String TRUSTED_FINGERPRINT = "99:15:47:c3:c4:58:86:82:f2:f2:8c:38:27:5b:6a:36";
    private static final PrivateKey TRUSTED = privateKey("/auth/trusted-key.pem");
    private static final String NOT_VERIFIED =
            "The required authentication information was not provided or was incorrect: ";

    private static PrivateKey privateKey(String resource) {
        try (InputStream in = SignatureVerificationStrictIntegrationTest.class.getResourceAsStream(resource);
             PEMParser parser = new PEMParser(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            return new JcaPEMKeyConverter().getPrivateKey((PrivateKeyInfo) parser.readObject());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OciRequestSigner trustedAs(String userId) {
        return OciRequestSigner.of(TENANCY + "/" + userId + "/" + TRUSTED_FINGERPRINT, TRUSTED);
    }

    @Test
    void aTrustedKeyBootstrapsTheFirstUserAndApiKey() {
        OciRequestSigner bootstrap = trustedAs("ocid1.user.oc1..anyone");
        String createUser = "{\"compartmentId\":\"" + TENANCY + "\",\"name\":\"sig-strict-" + System.nanoTime()
                + "\",\"description\":\"bootstrapped\"}";
        String userId = signedJson(bootstrap, "POST", "/20160918/users", createUser, createUser)
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");

        KeyPair uploaded = OciRequestSigner.newKeyPair();
        String uploadKey = "{\"key\":\"" + OciRequestSigner.pem(uploaded.getPublic()).replace("\n", "\\n") + "\"}";
        String apiKeys = "/20160918/users/" + userId + "/apiKeys";
        String keyId = signedJson(bootstrap, "POST", apiKeys, uploadKey, uploadKey)
            .when().post(apiKeys)
            .then().statusCode(200).extract().path("keyId");

        signed(OciRequestSigner.of(keyId, uploaded.getPrivate()), "GET", apiKeys)
            .when().get(apiKeys)
            .then().statusCode(200).body("[0].keyId", equalTo(keyId));
    }

    @Test
    void anUnknownKeyIsNotAuthenticated() {
        OciRequestSigner unknown = OciRequestSigner.of(TENANCY + "/ocid1.user.oc1..anyone/aa:bb:cc",
                OciRequestSigner.newKeyPair().getPrivate());

        signed(unknown, "GET", "/20160918/users?compartmentId=" + TENANCY)
            .when().get("/20160918/users?compartmentId=" + TENANCY)
            .then()
                .statusCode(401)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthenticated"))
                .body("message", equalTo(NOT_VERIFIED + "the signing key is not known."));
    }

    @Test
    void anUnsignedRequestIsNotAuthenticated() {
        given()
            .when().get("/20160918/users?compartmentId=" + TENANCY)
            .then().statusCode(401).body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void exemptPathsStayExempt() {
        given().when().get("/health").then().statusCode(200);
        given().when().get("/_floci-oci/health").then().statusCode(200);
    }

    @Test
    void aSecurityTokenMustMatchItsJwk() {
        KeyPair session = OciRequestSigner.newKeyPair();
        String token = SecurityTokens.session(TENANCY, "ocid1.user.oc1..sessionuser", (RSAPublicKey) session.getPublic());
        String target = "/20160918/users?compartmentId=" + TENANCY;

        signed(OciRequestSigner.of("ST$" + token, session.getPrivate()), "GET", target)
            .when().get(target)
            .then().statusCode(200);
        signed(OciRequestSigner.of("ST$" + token, TRUSTED), "GET", target)
            .when().get(target)
            .then()
                .statusCode(401)
                .body("message", equalTo(NOT_VERIFIED + "the signature does not match the signing key."));
    }

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-oci.auth.signature-verification", "strict",
                    "floci-oci.auth.require-signature", "true",
                    "floci-oci.auth.trusted-public-keys", TRUSTED_PUBLIC.toString());
        }
    }
}
