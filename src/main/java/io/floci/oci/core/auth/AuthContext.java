package io.floci.oci.core.auth;

/**
 * Identity extracted from a parsed OCI Signature {@code Authorization} header.
 * The RSA signature itself is never verified — only the keyId's structure.
 * {@code fingerprint} is null for security-token (session) callers, whose keyId has none.
 */
public record AuthContext(String tenancyId, String userId, String fingerprint) {
}
