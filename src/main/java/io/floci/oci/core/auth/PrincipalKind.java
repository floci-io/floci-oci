package io.floci.oci.core.auth;

/** How a request names its caller. */
public enum PrincipalKind {
    /** {@code keyId="<tenancy>/<user>/<fingerprint>"}: a user's API signing key. */
    API_KEY,
    /** {@code keyId="ST$<jwt>"}: a session, instance principal, federation or OAuth security token. */
    SECURITY_TOKEN,
    /** Any other credential, such as an S3 Compatibility API access key, which is never verified. */
    UNKNOWN
}
