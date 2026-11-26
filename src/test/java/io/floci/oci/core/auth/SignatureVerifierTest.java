package io.floci.oci.core.auth;

import io.floci.oci.testing.OciRequestSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final KeyPair KEY = OciRequestSigner.newKeyPair();
    private static final KeyPair OTHER_KEY = OciRequestSigner.newKeyPair();
    private static final String KEY_ID = "ocid1.tenancy.oc1..t/ocid1.user.oc1..u/aa:bb";
    private static final String HOST = "identity.us-ashburn-1.oci.oraclecloud.com";
    private static final String TARGET = "/20160918/compartments?compartmentId=ocid1.tenancy.oc1..t&name=a%20b";
    private static final byte[] BODY = "{\"name\":\"c\"}".getBytes(StandardCharsets.UTF_8);

    private final SignatureVerifier verifier = new SignatureVerifier(Clock.fixed(NOW, ZoneOffset.UTC));
    private final OciRequestSigner signer = OciRequestSigner.of(KEY_ID, KEY.getPrivate());

    private SignatureVerifier.Result verify(String method, String target, Map<String, String> headers) {
        return verifier.verify(method, target, headers::get,
                OciSignatureParser.parameters(headers.get("authorization")), KEY.getPublic());
    }

    @Test
    void aGetSignedOverTheGenericHeadersVerifies() {
        Map<String, String> headers = signer.sign("GET", TARGET, HOST, NOW, null, null);

        SignatureVerifier.Result result = verify("GET", TARGET, headers);

        assertTrue(result.verified(), result.failure());
    }

    @Test
    void aPostSignedOverItsBodyVerifiesAndCarriesTheBodyHash() {
        Map<String, String> headers = signer.sign("POST", "/20160918/compartments", HOST, NOW, BODY, "application/json");

        assertTrue(verify("POST", "/20160918/compartments", headers).verified());
        assertTrue(SignatureVerifier.coversBody(OciSignatureParser.parameters(headers.get("authorization"))));
        assertEquals(headers.get("x-content-sha256"), SignatureVerifier.bodyHash(BODY));
    }

    @Test
    void aPostSignedWithoutBodyHeadersVerifiesWithoutCoveringTheBody() {
        Map<String, String> headers = signer.sign("PUT", "/n/ns/b/bucket/o/object", HOST, NOW, null, null);

        assertTrue(verify("PUT", "/n/ns/b/bucket/o/object", headers).verified());
        assertFalse(SignatureVerifier.coversBody(OciSignatureParser.parameters(headers.get("authorization"))));
    }

    @Test
    void aTamperedBodyNoLongerMatchesTheSignedHash() {
        Map<String, String> headers = signer.sign("POST", "/20160918/compartments", HOST, NOW, BODY, "application/json");

        assertNotEquals(headers.get("x-content-sha256"),
                SignatureVerifier.bodyHash("{\"name\":\"d\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aTamperedQueryDoesNotVerify() {
        Map<String, String> headers = signer.sign("GET", TARGET, HOST, NOW, null, null);

        SignatureVerifier.Result result = verify("GET", TARGET.replace("a%20b", "a%20c"), headers);

        assertFalse(result.verified());
        assertEquals("the signature does not match the signing key", result.failure());
    }

    @Test
    void anotherMethodDoesNotVerify() {
        Map<String, String> headers = signer.sign("GET", TARGET, HOST, NOW, null, null);

        assertFalse(verify("DELETE", TARGET, headers).verified());
    }

    @Test
    void anotherKeyDoesNotVerify() {
        Map<String, String> headers = OciRequestSigner.of(KEY_ID, OTHER_KEY.getPrivate())
                .sign("GET", TARGET, HOST, NOW, null, null);

        assertEquals("the signature does not match the signing key", verify("GET", TARGET, headers).failure());
    }

    @Test
    void aDateMoreThanFiveMinutesAwayDoesNotVerify() {
        Instant stale = NOW.minus(Duration.ofMinutes(5)).minusSeconds(1);
        Instant future = NOW.plus(Duration.ofMinutes(5)).plusSeconds(1);

        assertEquals("the signed date is more than 5 minutes from the server time",
                verify("GET", TARGET, signer.sign("GET", TARGET, HOST, stale, null, null)).failure());
        assertFalse(verify("GET", TARGET, signer.sign("GET", TARGET, HOST, future, null, null)).verified());
        assertTrue(verify("GET", TARGET,
                signer.sign("GET", TARGET, HOST, NOW.minus(Duration.ofMinutes(5)), null, null)).verified());
    }

    @Test
    void aMissingSignedHeaderDoesNotVerify() {
        Map<String, String> headers = new HashMap<>(
                signer.sign("POST", "/20160918/compartments", HOST, NOW, BODY, "application/json"));
        headers.remove("content-type");

        assertEquals("the signed header content-type is missing from the request",
                verify("POST", "/20160918/compartments", headers).failure());
    }

    @Test
    void anotherHostDoesNotVerify() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        headers.put("host", "identity.eu-frankfurt-1.oci.oraclecloud.com");

        assertFalse(verify("GET", TARGET, headers).verified());
    }

    @Test
    void pssSignaturesVerifyWithEitherCommonSaltLength() {
        int maxSalt = 256 - 32 - 2;
        for (int salt : new int[] {32, maxSalt}) {
            Map<String, String> headers = signer.pss(salt).sign("GET", TARGET, HOST, NOW, null, null);

            assertTrue(verify("GET", TARGET, headers).verified(), "salt " + salt);
        }
    }

    @Test
    void headersAreSignedInTheListedOrder() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        headers.put("authorization", signer.authorization("GET", TARGET, headers,
                List.of("host", "(request-target)", "date")));

        assertTrue(verify("GET", TARGET, headers).verified());
        String reordered = headers.get("authorization").replace("host (request-target) date", "date (request-target) host");
        headers.put("authorization", reordered);
        assertFalse(verify("GET", TARGET, headers).verified());
    }

    @Test
    void onlyRsaSha256IsAccepted() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        headers.put("authorization", headers.get("authorization").replace("rsa-sha256", "hmac-sha256"));

        assertEquals("the signature algorithm must be rsa-sha256", verify("GET", TARGET, headers).failure());
    }

    @Test
    void aSignatureThatDoesNotCoverTheDateDoesNotVerify() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        headers.put("authorization", signer.authorization("GET", TARGET, headers, List.of("(request-target)", "host")));

        assertEquals("the signature does not cover the date header", verify("GET", TARGET, headers).failure());
    }

    @Test
    void aMissingHeadersParameterMeansTheDateAlone() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        String authorization = signer.authorization("GET", TARGET, headers, List.of("date"))
                .replace("headers=\"date\",", "");
        headers.put("authorization", authorization);

        assertEquals(List.of("date"), SignatureVerifier.signedHeaders(OciSignatureParser.parameters(authorization)));
        assertTrue(verify("GET", TARGET, headers).verified());
    }

    @Test
    void aSignatureThatIsNotBase64DoesNotVerify() {
        Map<String, String> headers = new HashMap<>(signer.sign("GET", TARGET, HOST, NOW, null, null));
        headers.put("authorization", headers.get("authorization").replaceAll("signature=\"[^\"]*\"", "signature=\"%%%\""));

        assertEquals("the signature is not base64", verify("GET", TARGET, headers).failure());
    }
}
