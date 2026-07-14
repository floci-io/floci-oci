package io.floci.oci.core.common;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * OCID parsing and generation.
 *
 * <p>Syntax: {@code ocid1.<resource-type>.<realm>.[region].[future-use.]<unique-id>} —
 * the region part is empty (double dot) for global resources such as tenancies,
 * compartments and users.
 */
public final class Ocids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int UNIQUE_LENGTH = 60;

    private Ocids() {
    }

    public record Ocid(String resourceType, String realm, String region, String uniqueId) {

        @Override
        public String toString() {
            return "ocid1." + resourceType + "." + realm + "." + (region == null ? "" : region)
                    + "." + uniqueId;
        }
    }

    /** Generates a regional OCID, e.g. {@code ocid1.bucket.oc1.iad.<unique>}. */
    public static String generate(String resourceType, String realm, String regionKey) {
        return new Ocid(resourceType, realm, regionKey, randomUnique()).toString();
    }

    /** Generates a global OCID (empty region), e.g. {@code ocid1.compartment.oc1..<unique>}. */
    public static String generateGlobal(String resourceType, String realm) {
        return new Ocid(resourceType, realm, null, randomUnique()).toString();
    }

    /**
     * Parses an OCID string. Returns empty for anything not matching the
     * {@code ocid1.<type>.<realm>.[region].<unique>} shape.
     */
    public static Optional<Ocid> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\.", -1);
        // ocid1 . type . realm . region(possibly empty) . [future-use…] . unique
        if (parts.length < 5 || !"ocid1".equals(parts[0])) {
            return Optional.empty();
        }
        String type = parts[1];
        String realm = parts[2];
        String region = parts[3].isEmpty() ? null : parts[3];
        String unique = parts[parts.length - 1];
        if (type.isEmpty() || realm.isEmpty() || unique.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Ocid(type.toLowerCase(Locale.ROOT), realm, region, unique));
    }

    public static boolean isValid(String value) {
        return parse(value).isPresent();
    }

    /** True when the OCID parses and its resource type equals {@code expectedType}. */
    public static boolean isOfType(String value, String expectedType) {
        return parse(value).map(o -> o.resourceType().equals(expectedType)).orElse(false);
    }

    private static String randomUnique() {
        StringBuilder sb = new StringBuilder(UNIQUE_LENGTH);
        for (int i = 0; i < UNIQUE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
