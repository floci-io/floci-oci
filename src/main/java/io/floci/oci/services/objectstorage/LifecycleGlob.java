package io.floci.oci.services.objectstorage;

import java.util.ArrayList;
import java.util.List;

/**
 * A lifecycle rule's object name pattern, in the glob syntax of oci-go-sdk {@code object_name_filter.go} and
 * Oracle's "Using Pattern Matching to Filter Objects" (usinglifecyclepolicies.htm):
 * <ul>
 *   <li>{@code *} matches any string, {@code /} included ({@code *.tmp} matches {@code foo/bar/baz.tmp});</li>
 *   <li>{@code ?} matches one character;</li>
 *   <li>{@code \} escapes the next character;</li>
 *   <li>{@code [...]} is either a set of characters or one {@code CHARACTER-CHARACTER} range. A {@code -} in a
 *   set must come first or last; a group cannot start with {@code ^} or {@code :}, so {@code [ab-yz]},
 *   {@code [a-mn-z]}, {@code [z-a]}, {@code [^a-z]} and {@code [:isalpha:]} are all invalid.</li>
 * </ul>
 * Matching runs in time proportional to the name times the pattern: a {@code *} only ever resumes from the
 * last one seen, so no pattern backtracks exponentially.
 */
final class LifecycleGlob {

    static final int MAX_LENGTH = 1024;

    private sealed interface Token permits Literal, AnyOne, AnyString, Group {
    }

    private record Literal(char c) implements Token {
    }

    private record AnyOne() implements Token {
    }

    private record AnyString() implements Token {
    }

    private record Group(String set, char low, char high) implements Token {

        boolean matches(char c) {
            return set != null ? set.indexOf(c) >= 0 : c >= low && c <= high;
        }
    }

    private final List<Token> tokens;

    private LifecycleGlob(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses {@code pattern}; an invalid one is an {@link IllegalArgumentException} naming why. */
    static LifecycleGlob compile(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("patterns must be between 1 and " + MAX_LENGTH + " characters");
        }
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (i + 1 >= pattern.length()) {
                        throw new IllegalArgumentException("a pattern cannot end with an unescaped \\");
                    }
                    tokens.add(new Literal(pattern.charAt(i + 1)));
                    i += 2;
                }
                case '*' -> {
                    if (tokens.isEmpty() || !(tokens.get(tokens.size() - 1) instanceof AnyString)) {
                        tokens.add(new AnyString());
                    }
                    i++;
                }
                case '?' -> {
                    tokens.add(new AnyOne());
                    i++;
                }
                case '[' -> {
                    int close = pattern.indexOf(']', i + 1);
                    if (close < 0) {
                        throw new IllegalArgumentException("unclosed [ in pattern");
                    }
                    tokens.add(group(pattern.substring(i + 1, close)));
                    i = close + 1;
                }
                default -> {
                    tokens.add(new Literal(c));
                    i++;
                }
            }
        }
        return new LifecycleGlob(tokens);
    }

    private static Group group(String inside) {
        if (inside.isEmpty()) {
            throw new IllegalArgumentException("[] matches nothing");
        }
        if (inside.charAt(0) == '^' || inside.charAt(0) == ':') {
            throw new IllegalArgumentException("a character group cannot start with ^ or :");
        }
        if (inside.length() == 3 && inside.charAt(1) == '-' && inside.charAt(0) != '-' && inside.charAt(2) != '-') {
            if (inside.charAt(0) > inside.charAt(2)) {
                throw new IllegalArgumentException("character range [" + inside + "] is reversed");
            }
            return new Group(null, inside.charAt(0), inside.charAt(2));
        }
        for (int k = 1; k < inside.length() - 1; k++) {
            if (inside.charAt(k) == '-') {
                throw new IllegalArgumentException("[" + inside + "] is not a set or a single CHARACTER-CHARACTER "
                        + "range; a - in a set must come first or last");
            }
        }
        return new Group(inside, '\0', '\0');
    }

    boolean matches(String name) {
        int n = 0;
        int t = 0;
        int starToken = -1;
        int starName = -1;
        while (n < name.length()) {
            if (t < tokens.size() && !(tokens.get(t) instanceof AnyString) && matchesOne(tokens.get(t), name.charAt(n))) {
                n++;
                t++;
            } else if (t < tokens.size() && tokens.get(t) instanceof AnyString) {
                starToken = t++;
                starName = n;
            } else if (starToken >= 0) {
                t = starToken + 1;
                n = ++starName;
            } else {
                return false;
            }
        }
        while (t < tokens.size() && tokens.get(t) instanceof AnyString) {
            t++;
        }
        return t == tokens.size();
    }

    private static boolean matchesOne(Token token, char c) {
        return switch (token) {
            case Literal literal -> literal.c() == c;
            case AnyOne any -> true;
            case Group group -> group.matches(c);
            case AnyString any -> true;
        };
    }
}
