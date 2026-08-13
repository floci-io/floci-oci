package io.floci.oci.core.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OciSignatureParserTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..aaaatenancy";
    private static final String USER = "ocid1.user.oc1..aaaauser";
    private static final String FINGERPRINT = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99";

    private static String header(String keyId) {
        return "Signature version=\"1\",keyId=\"" + keyId + "\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    @Test
    void parsesTenancyUserFingerprintTriple() {
        Optional<AuthContext> auth =
                OciSignatureParser.parse(header(TENANCY + "/" + USER + "/" + FINGERPRINT));
        assertTrue(auth.isPresent());
        assertEquals(TENANCY, auth.get().tenancyId());
        assertEquals(USER, auth.get().userId());
        assertEquals(FINGERPRINT, auth.get().fingerprint());
    }

    @Test
    void schemeIsCaseInsensitive() {
        String h = header(TENANCY + "/" + USER + "/" + FINGERPRINT)
                .replaceFirst("Signature", "signature");
        assertTrue(OciSignatureParser.parse(h).isPresent());
    }

    @Test
    void rejectsMissingHeader() {
        assertTrue(OciSignatureParser.parse(null).isEmpty());
        assertTrue(OciSignatureParser.parse("").isEmpty());
    }

    @Test
    void rejectsOtherSchemes() {
        assertTrue(OciSignatureParser.parse("Bearer some-token").isEmpty());
        assertFalse(OciSignatureParser.isSignatureScheme("Bearer some-token"));
    }

    @Test
    void rejectsInstancePrincipalKeyIds() {
        // "ST$<token>" keyIds carry no tenancy triple.
        assertTrue(OciSignatureParser.parse(header("ST$eyJhbGciOi")).isEmpty());
    }

    @Test
    void rejectsMalformedKeyId() {
        assertTrue(OciSignatureParser.parse(header("just-one-part")).isEmpty());
        assertTrue(OciSignatureParser.parse(header("a/b")).isEmpty());
        assertTrue(OciSignatureParser.parse(header("a//c")).isEmpty());
        assertTrue(OciSignatureParser.parse(
                "Signature version=\"1\",algorithm=\"rsa-sha256\"").isEmpty());
    }
}
