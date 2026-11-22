package io.floci.oci.core.common;

/**
 * Glob matching where {@code *} stands for any run of characters, including none. It never
 * backtracks exponentially: a pattern such as {@code *a*a*a*a*b} against a long run of {@code a}
 * costs at most value length times pattern length steps, unlike the equivalent regular expression.
 */
public final class Wildcards {

    private Wildcards() {
    }

    /** Whether the whole of {@code value} matches {@code pattern}. */
    public static boolean matches(String value, String pattern) {
        int v = 0;
        int p = 0;
        int star = -1;
        int resume = 0;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = v;
            } else if (p < pattern.length() && pattern.charAt(p) == value.charAt(v)) {
                p++;
                v++;
            } else if (star >= 0) {
                p = star + 1;
                v = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
