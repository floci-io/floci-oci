package io.floci.oci.core.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Names OCI assigns once per tenancy and never changes, such as the Object Storage namespace.
 * The emulator derives them from the tenancy OCID, so they are stable across restarts and need
 * no stored record.
 */
public final class TenancyNames {

    private TenancyNames() {
    }

    /** A stable string of {@code length} characters from {@code alphabet} for {@code tenancyId}. */
    public static String derive(String tenancyId, String alphabet, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenancyId.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                name.append(alphabet.charAt((digest[i] & 0xff) % alphabet.length()));
            }
            return name.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
