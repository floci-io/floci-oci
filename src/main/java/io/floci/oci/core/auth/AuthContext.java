package io.floci.oci.core.auth;

/**
 * The caller a request names: from its OCI Signature {@code Authorization} header, or from an S3
 * Compatibility API access key.
 *
 * <p>{@code verified} is true only when {@link RequestAuthenticator} checked the request signature
 * against a known public key: an uploaded API key, a trusted public key, or a security token's
 * {@code jwk} claim. With {@code floci-oci.auth.signature-verification=off}, and for callers whose key
 * is unknown in {@code known-keys} mode, the caller is only parsed and {@code verified} stays false.
 * {@code fingerprint} is null for callers that do not sign with an API key.
 */
public record AuthContext(String tenancyId, String userId, String fingerprint, PrincipalKind kind,
                          boolean verified) {

    /** A {@code keyId="<tenancy>/<user>/<fingerprint>"} signature, not verified yet. */
    public static AuthContext apiKey(String tenancyId, String userId, String fingerprint) {
        return new AuthContext(tenancyId, userId, fingerprint, PrincipalKind.API_KEY, false);
    }

    /** A {@code keyId="ST$<jwt>"} signature, named by the token's {@code tenant} and {@code sub}, not verified yet. */
    public static AuthContext securityToken(String tenancyId, String userId) {
        return new AuthContext(tenancyId, userId, null, PrincipalKind.SECURITY_TOKEN, false);
    }

    /** A caller named by other means, such as an S3 access key, whose request is never verified. */
    public static AuthContext unknown(String tenancyId, String userId) {
        return new AuthContext(tenancyId, userId, null, PrincipalKind.UNKNOWN, false);
    }

    /** This caller, with its request signature verified. */
    public AuthContext asVerified() {
        return new AuthContext(tenancyId, userId, fingerprint, kind, true);
    }
}
