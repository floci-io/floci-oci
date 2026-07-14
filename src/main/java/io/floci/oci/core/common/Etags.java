package io.floci.oci.core.common;

import java.util.UUID;

/**
 * ETag generation and conditional-request evaluation for OCI resources.
 * OCI etags are opaque strings; {@code if-match} / {@code if-none-match} accept
 * the wildcard {@code *}.
 */
public final class Etags {

    private Etags() {
    }

    public static String newEtag() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Evaluates an {@code if-match} precondition. Throws {@code NoEtagMatch} (412)
     * when the header is present and does not match the current etag.
     */
    public static void checkIfMatch(String ifMatchHeader, String currentEtag) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            return;
        }
        if ("*".equals(ifMatchHeader.trim())) {
            return;
        }
        for (String candidate : ifMatchHeader.split(",")) {
            if (unquote(candidate).equals(currentEtag)) {
                return;
            }
        }
        throw OciException.noEtagMatch(
                "The if-match etag does not match the current etag of the resource.");
    }

    /**
     * Evaluates an {@code if-none-match} precondition (create-if-absent semantics:
     * only {@code *} is meaningful on writes). Throws {@code IfNoneMatchFailed} (412)
     * when the resource exists and the header forbids overwrite.
     */
    public static void checkIfNoneMatch(String ifNoneMatchHeader, String currentEtag) {
        if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) {
            return;
        }
        String header = ifNoneMatchHeader.trim();
        if ("*".equals(header)) {
            if (currentEtag != null) {
                throw OciException.ifNoneMatchFailed("The resource already exists.");
            }
            return;
        }
        for (String candidate : header.split(",")) {
            if (unquote(candidate).equals(currentEtag)) {
                throw OciException.ifNoneMatchFailed(
                        "The if-none-match etag matches the current etag of the resource.");
            }
        }
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
