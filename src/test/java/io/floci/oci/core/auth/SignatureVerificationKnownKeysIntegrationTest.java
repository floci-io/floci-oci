package io.floci.oci.core.auth;

import io.floci.oci.testing.OciRequestSigner;
import io.floci.oci.testing.SecurityTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.floci.oci.core.auth.SignedRequests.TENANCY;
import static io.floci.oci.core.auth.SignedRequests.signed;
import static io.floci.oci.core.auth.SignedRequests.signedAt;
import static io.floci.oci.core.auth.SignedRequests.signedJson;
import static io.floci.oci.core.auth.SignedRequests.signedWithoutBody;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code signature-verification=known-keys}: a keyId naming an uploaded key must verify, a keyId naming
 * no known key passes as before, and a security token must verify against its {@code jwk}.
 */
@QuarkusTest
@TestProfile(SignatureVerificationKnownKeysIntegrationTest.KnownKeysProfile.class)
class SignatureVerificationKnownKeysIntegrationTest {

    private static final KeyPair UPLOADED = OciRequestSigner.newKeyPair();
    private static final KeyPair OTHER = OciRequestSigner.newKeyPair();
    private static final String NOT_VERIFIED =
            "The required authentication information was not provided or was incorrect: ";

    private String userId;
    private OciRequestSigner uploadedSigner;

    @BeforeEach
    void uploadAKey() {
        userId = given().contentType("application/json")
                .body(Map.of("name", "sig-known-" + System.nanoTime(), "description", "signature verification"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
        String keyId = given().contentType("application/json")
                .body(Map.of("key", OciRequestSigner.pem(UPLOADED.getPublic())))
            .when().post("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(200).extract().path("keyId");
        uploadedSigner = OciRequestSigner.of(keyId, UPLOADED.getPrivate());
    }

    private String apiKeysPath() {
        return "/20160918/users/" + userId + "/apiKeys";
    }

    @Test
    void anUploadedKeyVerifies() {
        signed(uploadedSigner, "GET", apiKeysPath())
            .when().get(apiKeysPath())
            .then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void theRawQueryIsWhatIsVerified() {
        String target = "/20160918/compartments?compartmentId=" + TENANCY + "&unused=a%20b%2Fc";

        signed(uploadedSigner, "GET", target)
            .when().get(target)
            .then().statusCode(200);
    }

    @Test
    void theSameKeyIdSignedWithAnotherKeyIsNotAuthenticated() {
        OciRequestSigner forged = OciRequestSigner.of(TENANCY + "/" + userId + "/"
                + PublicKeys.fingerprint(UPLOADED.getPublic()), OTHER.getPrivate());

        signed(forged, "GET", apiKeysPath())
            .when().get(apiKeysPath())
            .then()
                .statusCode(401)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthenticated"))
                .body("message", equalTo(NOT_VERIFIED + "the signature does not match the signing key."))
                .body("size()", equalTo(2));
    }

    @Test
    void anUnknownFingerprintPassesUnverified() {
        OciRequestSigner unknown = OciRequestSigner.of(TENANCY + "/" + userId + "/aa:bb:cc", OTHER.getPrivate());

        signed(unknown, "GET", apiKeysPath())
            .when().get(apiKeysPath())
            .then().statusCode(200);
    }

    @Test
    void aSignedBodyVerifies() {
        String body = "{\"compartmentId\":\"" + TENANCY + "\",\"name\":\"sig-body-" + System.nanoTime()
                + "\",\"description\":\"signed body\"}";

        signedJson(uploadedSigner, "POST", "/20160918/compartments", body, body)
            .when().post("/20160918/compartments")
            .then().statusCode(200).body("description", equalTo("signed body"));
    }

    @Test
    void aTamperedBodyIsNotAuthenticated() {
        String name = "sig-tamper-" + System.nanoTime();
        String body = "{\"compartmentId\":\"" + TENANCY + "\",\"name\":\"" + name + "\",\"description\":\"signed body\"}";
        String tampered = body.replace("signed body", "forged body");

        signedJson(uploadedSigner, "POST", "/20160918/compartments", body, tampered)
            .when().post("/20160918/compartments")
            .then()
                .statusCode(401)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthenticated"))
                .body("message", equalTo(NOT_VERIFIED + "the body does not match x-content-sha256."));
    }

    @Test
    void aBodyLeftOutOfTheSignatureIsNotChecked() {
        String body = "{\"compartmentId\":\"" + TENANCY + "\",\"name\":\"sig-nobody-" + System.nanoTime()
                + "\",\"description\":\"unsigned body\"}";

        signedWithoutBody(uploadedSigner, "POST", "/20160918/compartments", body)
            .when().post("/20160918/compartments")
            .then().statusCode(200);
    }

    @Test
    void aTamperedQueryIsNotAuthenticated() {
        String target = "/20160918/compartments?compartmentId=" + TENANCY;

        signed(uploadedSigner, "GET", target)
            .when().get(target + "&lifecycleState=ACTIVE")
            .then().statusCode(401).body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void aStaleDateIsNotAuthenticated() {
        signedAt(uploadedSigner, "GET", apiKeysPath(), Instant.now().minus(Duration.ofMinutes(10)))
            .when().get(apiKeysPath())
            .then()
                .statusCode(401)
                .body("message", equalTo(NOT_VERIFIED + "the signed date is more than 5 minutes from the server time."));
    }

    @Test
    void aSecurityTokenVerifiesAgainstItsJwk() {
        KeyPair session = OciRequestSigner.newKeyPair();
        String token = SecurityTokens.session(TENANCY, userId, (RSAPublicKey) session.getPublic());

        signed(OciRequestSigner.of("ST$" + token, session.getPrivate()), "GET", apiKeysPath())
            .when().get(apiKeysPath())
            .then().statusCode(200);
        signed(OciRequestSigner.of("ST$" + token, OTHER.getPrivate()), "GET", apiKeysPath())
            .when().get(apiKeysPath())
            .then().statusCode(401).body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void exemptPathsAreNotVerified() {
        OciRequestSigner forged = OciRequestSigner.of(TENANCY + "/" + userId + "/"
                + PublicKeys.fingerprint(UPLOADED.getPublic()), OTHER.getPrivate());

        signed(forged, "GET", "/_floci-oci/health")
            .when().get("/_floci-oci/health")
            .then().statusCode(200);
    }

    public static class KnownKeysProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.auth.signature-verification", "known-keys");
        }
    }
}
