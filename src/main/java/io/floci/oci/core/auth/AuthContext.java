package io.floci.oci.core.auth;

/**
 * Identity extracted from a parsed OCI Signature {@code Authorization} header.
 * The RSA signature itself is never verified — only the keyId's structure.
 */
public record AuthContext(String tenancyId, String userId, String fingerprint) {
}
