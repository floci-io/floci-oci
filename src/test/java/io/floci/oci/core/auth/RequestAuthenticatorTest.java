package io.floci.oci.core.auth;

import io.floci.oci.config.EmulatorConfig.SignatureVerificationMode;
import io.floci.oci.testing.OciRequestSigner;
import io.floci.oci.testing.SecurityTokens;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RequestAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final String TENANCY = "ocid1.tenancy.oc1..t";
    private static final String USER = "ocid1.user.oc1..u";
    private static final String HOST = "localhost";
    private static final String TARGET = "/20160918/users";
    private static final KeyPair UPLOADED = OciRequestSigner.newKeyPair();
    private static final KeyPair TRUSTED = OciRequestSigner.newKeyPair();
    private static final KeyPair STRANGER = OciRequestSigner.newKeyPair();
    private static final String UPLOADED_FINGERPRINT = PublicKeys.fingerprint(UPLOADED.getPublic());

    /** Stands in for Identity: only {@link #UPLOADED} is uploaded, for {@link #USER}. */
    private static final PublicKeyResolver IDENTITY = caller -> USER.equals(caller.userId())
            && UPLOADED_FINGERPRINT.equals(caller.fingerprint()) ? Optional.of(UPLOADED.getPublic()) : Optional.empty();

    private static RequestAuthenticator authenticator(SignatureVerificationMode mode) {
        return new RequestAuthenticator(mode, List.of(TRUSTED.getPublic()), IDENTITY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RequestAuthenticator.Authentication authenticate(RequestAuthenticator authenticator,
                                                                    String keyId, KeyPair signedWith) {
        Map<String, String> headers = OciRequestSigner.of(keyId, signedWith.getPrivate())
                .sign("GET", TARGET, HOST, NOW, null, null);
        AuthContext caller = OciSignatureParser.parse(headers.get("authorization")).orElseThrow();
        return authenticator.authenticate(caller, headers.get("authorization"), "GET", TARGET, headers::get);
    }

    private static String keyId(String fingerprint) {
        return TENANCY + "/" + USER + "/" + fingerprint;
    }

    @Test
    void offAcceptsAnySignatureUnverified() {
        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.OFF), keyId(UPLOADED_FINGERPRINT), STRANGER);

        assertFalse(outcome.isRejected());
        assertFalse(outcome.caller().verified());
        assertEquals(PrincipalKind.API_KEY, outcome.caller().kind());
    }

    @Test
    void knownKeysVerifiesAnUploadedKey() {
        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), keyId(UPLOADED_FINGERPRINT), UPLOADED);

        assertFalse(outcome.isRejected());
        assertTrue(outcome.caller().verified());
        assertEquals(USER, outcome.caller().userId());
    }

    @Test
    void knownKeysRejectsAnUploadedKeyIdSignedWithAnotherKey() {
        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), keyId(UPLOADED_FINGERPRINT), STRANGER);

        assertEquals("the signature does not match the signing key", outcome.rejection());
    }

    @Test
    void knownKeysPassesAnUnknownKeyUnverified() {
        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), keyId("aa:bb"), STRANGER);

        assertFalse(outcome.isRejected());
        assertFalse(outcome.caller().verified());
    }

    @Test
    void strictRejectsAnUnknownKey() {
        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.STRICT), keyId("aa:bb"), STRANGER);

        assertEquals("the signing key is not known", outcome.rejection());
    }

    @Test
    void aTrustedKeyVerifiesAnyTenancyAndUserWithItsFingerprint() {
        String keyId = "ocid1.tenancy.oc1..elsewhere/ocid1.user.oc1..anyone/"
                + PublicKeys.fingerprint(TRUSTED.getPublic()).toUpperCase();

        RequestAuthenticator.Authentication outcome =
                authenticate(authenticator(SignatureVerificationMode.STRICT), keyId, TRUSTED);

        assertFalse(outcome.isRejected(), outcome.rejection());
        assertTrue(outcome.caller().verified());
        assertEquals("ocid1.user.oc1..anyone", outcome.caller().userId());
    }

    @Test
    void aSecurityTokenVerifiesAgainstItsJwk() {
        KeyPair session = OciRequestSigner.newKeyPair();
        String token = SecurityTokens.session(TENANCY, USER, (RSAPublicKey) session.getPublic());

        RequestAuthenticator.Authentication matching =
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), "ST$" + token, session);
        RequestAuthenticator.Authentication mismatched =
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), "ST$" + token, STRANGER);

        assertTrue(matching.caller().verified());
        assertEquals(PrincipalKind.SECURITY_TOKEN, matching.caller().kind());
        assertEquals("the signature does not match the signing key", mismatched.rejection());
    }

    @Test
    void aSecurityTokenWithoutAUsableJwkIsRejectedOnceVerificationIsOn() {
        String token = SecurityTokens.jwt("{\"sub\":\"" + USER + "\",\"tenant\":\"" + TENANCY + "\"}");

        assertEquals("the security token carries no RSA jwk claim",
                authenticate(authenticator(SignatureVerificationMode.KNOWN_KEYS), "ST$" + token, STRANGER).rejection());
        assertFalse(authenticate(authenticator(SignatureVerificationMode.OFF), "ST$" + token, STRANGER).isRejected());
    }

    @Test
    void trustedKeysLoadFromPublicOrPrivatePemFiles() throws Exception {
        Path publicPem = Files.createTempFile("trusted", ".pub");
        Path privatePem = Files.createTempFile("trusted", ".pem");
        Files.writeString(publicPem, OciRequestSigner.pem(TRUSTED.getPublic()), StandardCharsets.UTF_8);
        Files.writeString(privatePem, OciRequestSigner.pem(TRUSTED.getPrivate()), StandardCharsets.UTF_8);

        List<PublicKey> keys = RequestAuthenticator.loadTrustedKeys(
                List.of(publicPem.toString(), privatePem.toString()));

        assertArrayEquals(TRUSTED.getPublic().getEncoded(), keys.get(0).getEncoded());
        assertArrayEquals(TRUSTED.getPublic().getEncoded(), keys.get(1).getEncoded());
    }

    @Test
    void anUnreadableTrustedKeyStopsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RequestAuthenticator.loadTrustedKeys(List.of("/no/such/key.pem")));

        assertTrue(e.getMessage().contains("/no/such/key.pem"));
    }
}
