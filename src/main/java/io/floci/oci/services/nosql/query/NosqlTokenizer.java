package io.floci.oci.services.nosql.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a NoSQL DDL or SQL statement into tokens. Keywords are not reserved here: they are
 * {@link Kind#IDENT} tokens that the parsers match case-insensitively. Strings take single or
 * double quotes, as the NoSQL SQL dialect allows both.
 */
public final class NosqlTokenizer {

    public enum Kind { IDENT, NUMBER, STRING, VARIABLE, SYMBOL, EOF }

    public record Token(Kind kind, String text, int position) {

        public boolean is(String keyword) {
            return kind == Kind.IDENT && text.equalsIgnoreCase(keyword);
        }

        public boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        public String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    private static final List<String> TWO_CHAR_SYMBOLS = List.of("<=", ">=", "!=", "<>");
    private static final String ONE_CHAR_SYMBOLS = "(),.;*=<>+-/[]";

    private NosqlTokenizer() {
    }

    public static List<Token> tokenize(String statement) {
        if (statement == null) {
            throw new NosqlSyntaxException("statement is required");
        }
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = statement.length();
        while (i < n) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(statement.charAt(i)) || statement.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENT, statement.substring(start, i), start));
            } else if (c == '`') {
                int end = statement.indexOf('`', i + 1);
                if (end < 0) {
                    throw new NosqlSyntaxException("Unterminated quoted identifier at position " + i);
                }
                tokens.add(new Token(Kind.IDENT, statement.substring(i + 1, end), i));
                i = end + 1;
            } else if (c == '$') {
                int start = i;
                i++;
                while (i < n && (Character.isLetterOrDigit(statement.charAt(i)) || statement.charAt(i) == '_')) {
                    i++;
                }
                if (i == start + 1) {
                    throw new NosqlSyntaxException("Expected a variable name after '$' at position " + start);
                }
                tokens.add(new Token(Kind.VARIABLE, statement.substring(start, i), start));
            } else if (Character.isDigit(c)) {
                int start = i;
                i = scanNumber(statement, i);
                tokens.add(new Token(Kind.NUMBER, statement.substring(start, i), start));
            } else if (c == '\'' || c == '"') {
                int start = i;
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = statement.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        value.append(statement.charAt(i + 1));
                        i += 2;
                    } else if (ch == c) {
                        if (i + 1 < n && statement.charAt(i + 1) == c) {
                            value.append(c);
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        value.append(ch);
                        i++;
                    }
                }
                if (!closed) {
                    throw new NosqlSyntaxException("Unterminated string literal at position " + start);
                }
                tokens.add(new Token(Kind.STRING, value.toString(), start));
            } else if (i + 1 < n && TWO_CHAR_SYMBOLS.contains(statement.substring(i, i + 2))) {
                tokens.add(new Token(Kind.SYMBOL, statement.substring(i, i + 2), i));
                i += 2;
            } else if (ONE_CHAR_SYMBOLS.indexOf(c) >= 0) {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
                i++;
            } else {
                throw new NosqlSyntaxException("Unexpected character '" + c + "' at position " + i);
            }
        }
        tokens.add(new Token(Kind.EOF, "", n));
        return tokens;
    }

    private static int scanNumber(String s, int i) {
        int n = s.length();
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i + 1 < n && s.charAt(i) == '.' && Character.isDigit(s.charAt(i + 1))) {
            i++;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(s.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
        }
        return i;
    }
}
