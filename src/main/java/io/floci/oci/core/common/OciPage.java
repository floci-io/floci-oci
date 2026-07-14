package io.floci.oci.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * OCI list pagination: {@code limit} and {@code page} query parameters in, the
 * {@code opc-next-page} response header out. Page tokens are opaque to callers —
 * internally a base64-encoded offset.
 */
public final class OciPage {

    public static final String OPC_NEXT_PAGE = "opc-next-page";
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private OciPage() {
    }

    public record Page<T>(List<T> items, String nextPage) {

        public boolean hasNextPage() {
            return nextPage != null;
        }
    }

    /**
     * Slices {@code all} according to the request's {@code limit} and {@code page}.
     * A malformed or stale page token raises {@code InvalidParameter}.
     */
    public static <T> Page<T> paginate(List<T> all, Integer limit, String pageToken) {
        int effectiveLimit = normalizeLimit(limit);
        int offset = decode(pageToken);
        if (offset >= all.size()) {
            return new Page<>(List.of(), null);
        }
        int end = Math.min(offset + effectiveLimit, all.size());
        List<T> items = List.copyOf(all.subList(offset, end));
        String next = end < all.size() ? encode(end) : null;
        return new Page<>(items, next);
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw OciException.invalidParameter("limit must be a positive integer");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    static int decode(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
            int offset = Integer.parseInt(decoded);
            if (offset < 0) {
                throw new NumberFormatException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter("Invalid page token: " + pageToken);
        }
    }

    /** Convenience: page token of the element after {@code lastIndex}, or null when done. */
    public static Optional<String> nextToken(int end, int totalSize) {
        return end < totalSize ? Optional.of(encode(end)) : Optional.empty();
    }
}
