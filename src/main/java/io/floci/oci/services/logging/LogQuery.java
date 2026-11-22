package io.floci.oci.services.logging;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Wildcards;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The subset of the Logging query language (Logging/Reference/query_language_specification.htm)
 * floci-oci evaluates:
 *
 * <pre>
 *   query    := search scope ("," scope)* ("|" pipe)*
 *   scope    := "compartmentOcid[/logGroupNameOrOcid[/logNameOrOcid]]"
 *   pipe     := [where] expr | sort by expr [asc|desc] ("," ...)* | top N by expr
 *             | dedup expr ("," expr)* | select expr [as name] ("," ...)* | count
 *             | summarize agg [as name] ("," ...)* [by expr [as name] ("," ...)*]
 *   expr     := or/and/not, comparisons (= != &gt; &gt;= &lt; &lt;=), + - * /, fields, literals and the
 *               functions contains_ci, contains_cs, upper, lower, concat, substr, isnull,
 *               isnotnull, rounddown and not
 *   agg      := count() | count(expr) | sum | avg | min | max | first | last
 * </pre>
 *
 * A row is {@code {"datetime": <epoch millis>, "logContent": <envelope>}} until a select, count or
 * summarize projects it. A field resolves against the row first and then against
 * {@code logContent}, so {@code data.level} and {@code type} read the stored entry. Comparing
 * {@code logContent} with a string matches any value in the entry; {@code *} is a wildcard in
 * string comparisons.
 */
final class LogQuery {

    private LogQuery() {
    }

    record Scope(String compartmentId, String logGroup, String log) {
    }

    interface Pipe {
        List<Map<String, Object>> apply(List<Map<String, Object>> rows);
    }

    record Parsed(List<Scope> scopes, List<Pipe> pipes) {

        List<Map<String, Object>> run(List<Map<String, Object>> rows) {
            List<Map<String, Object>> current = rows;
            for (Pipe pipe : pipes) {
                current = pipe.apply(current);
            }
            return current;
        }
    }

    static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            throw OciException.missingParameter("searchQuery is required");
        }
        Parser parser = new Parser(tokenize(text), text);
        return parser.query();
    }

    // ── Tokens ─────────────────────────────────────────────────────────────────

    private enum Kind { IDENT, STRING, QUOTED, NUMBER, OP, END }

    private record Token(Kind kind, String text) {

        boolean is(String value) {
            return (kind == Kind.OP || kind == Kind.IDENT) && text.equalsIgnoreCase(value);
        }
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            // Comments are skipped here rather than stripped first, so "--" or "/*" inside a quoted
            // value stays part of the value.
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("--", i)) {
                int newline = text.indexOf('\n', i);
                i = newline < 0 ? text.length() : newline + 1;
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) {
                    throw invalid("unclosed comment", text);
                }
                i = close + 2;
            } else if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                int j = i + 1;
                while (j < text.length()) {
                    char d = text.charAt(j);
                    if (d == '\\' && j + 1 < text.length()) {
                        value.append(text.charAt(j + 1));
                        j += 2;
                    } else if (d == c && j + 1 < text.length() && text.charAt(j + 1) == c) {
                        value.append(c);
                        j += 2;
                    } else if (d == c) {
                        break;
                    } else {
                        value.append(d);
                        j++;
                    }
                }
                if (j >= text.length()) {
                    throw invalid("unclosed string", text);
                }
                tokens.add(new Token(c == '\'' ? Kind.STRING : Kind.QUOTED, value.toString()));
                i = j + 1;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                    j++;
                }
                tokens.add(new Token(Kind.NUMBER, text.substring(i, j)));
                i = j;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_'
                        || text.charAt(j) == '$')) {
                    j++;
                }
                tokens.add(new Token(Kind.IDENT, text.substring(i, j)));
                i = j;
            } else {
                String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
                if (Set.of(">=", "<=", "!=", "==").contains(two)) {
                    tokens.add(new Token(Kind.OP, two.equals("==") ? "=" : two));
                    i += 2;
                } else if ("|,()=<>+-*/.".indexOf(c) >= 0) {
                    tokens.add(new Token(Kind.OP, String.valueOf(c)));
                    i++;
                } else {
                    throw invalid("unexpected character '" + c + "'", text);
                }
            }
        }
        tokens.add(new Token(Kind.END, ""));
        return tokens;
    }

    static OciException invalid(String reason, String query) {
        return OciException.invalidParameter("Invalid search query: " + reason + " (query: " + query + ")");
    }

    // ── Expressions ────────────────────────────────────────────────────────────

    interface Expr {
        Object eval(Map<String, Object> row);

        default String name() {
            return null;
        }
    }

    private record Literal(Object value) implements Expr {
        public Object eval(Map<String, Object> row) {
            return value;
        }
    }

    private record Field(List<String> path) implements Expr {
        public Object eval(Map<String, Object> row) {
            return resolve(row, path);
        }

        @Override
        public String name() {
            return path.get(path.size() - 1);
        }
    }

    private record Binary(String op, Expr left, Expr right) implements Expr {
        public Object eval(Map<String, Object> row) {
            return switch (op) {
                case "and" -> truthy(left.eval(row)) && truthy(right.eval(row));
                case "or" -> truthy(left.eval(row)) || truthy(right.eval(row));
                case "=" -> equalsMatch(left, left.eval(row), right.eval(row), row);
                case "!=" -> !equalsMatch(left, left.eval(row), right.eval(row), row);
                case ">", ">=", "<", "<=" -> compare(op, left.eval(row), right.eval(row));
                default -> arithmetic(op, left.eval(row), right.eval(row));
            };
        }
    }

    /** The functions a query may call, with their minimum and maximum argument counts. */
    private static final Map<String, int[]> FUNCTIONS = Map.ofEntries(
            Map.entry("not", new int[] {1, 1}), Map.entry("isnull", new int[] {1, 1}),
            Map.entry("isnotnull", new int[] {1, 1}), Map.entry("upper", new int[] {1, 1}),
            Map.entry("lower", new int[] {1, 1}), Map.entry("concat", new int[] {1, Integer.MAX_VALUE}),
            Map.entry("substr", new int[] {2, 3}), Map.entry("contains_ci", new int[] {2, 3}),
            Map.entry("contains_cs", new int[] {2, 3}), Map.entry("rounddown", new int[] {2, 2}));

    /** No value a query computes may grow past this, so a chain of concat() cannot exhaust memory. */
    static final int MAX_VALUE_LENGTH = 1 << 20;
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?[0-9]+");

    private record Call(String function, List<Expr> args) implements Expr {
        public Object eval(Map<String, Object> row) {
            List<Object> values = new ArrayList<>();
            for (Expr arg : args) {
                values.add(arg.eval(row));
            }
            return switch (function) {
                case "not" -> !truthy(values.get(0));
                case "isnull" -> values.get(0) == null;
                case "isnotnull" -> values.get(0) != null;
                case "upper" -> values.get(0) == null ? null : String.valueOf(values.get(0)).toUpperCase(Locale.ROOT);
                case "lower" -> values.get(0) == null ? null : String.valueOf(values.get(0)).toLowerCase(Locale.ROOT);
                case "concat" -> {
                    StringBuilder joined = new StringBuilder();
                    for (Object v : values) {
                        String part = v == null ? "" : String.valueOf(v);
                        if (joined.length() + part.length() > MAX_VALUE_LENGTH) {
                            throw OciException.invalidParameter("concat() would build a value longer than "
                                    + MAX_VALUE_LENGTH + " characters");
                        }
                        joined.append(part);
                    }
                    yield joined.toString();
                }
                case "substr" -> {
                    String s = values.get(0) == null ? "" : String.valueOf(values.get(0));
                    Number from = number(values.get(1));
                    int start = Math.clamp(from == null ? 0 : from.longValue(), 0, s.length());
                    Number length = values.size() > 2 ? number(values.get(2)) : null;
                    // Capped at what is left before adding, so a huge length cannot overflow past the start.
                    int end = length == null ? s.length()
                            : start + Math.clamp(length.longValue(), 0, s.length() - start);
                    yield s.substring(start, end);
                }
                case "contains_ci", "contains_cs" -> {
                    if (values.get(0) == null || values.get(1) == null) {
                        yield false;
                    }
                    boolean caseSensitive = values.size() > 2 ? truthy(values.get(2)) : function.equals("contains_cs");
                    String haystack = String.valueOf(values.get(0));
                    String needle = String.valueOf(values.get(1));
                    yield caseSensitive ? haystack.contains(needle)
                            : haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
                }
                case "rounddown" -> {
                    Number time = number(values.get(0));
                    long step = interval(String.valueOf(values.get(1)));
                    yield time == null ? null : Math.floorDiv(time.longValue(), step) * step;
                }
                default -> throw OciException.invalidParameter("Unknown function " + function + "()");
            };
        }
    }

    private record Negate(Expr inner) implements Expr {
        public Object eval(Map<String, Object> row) {
            Number n = number(inner.eval(row));
            if (n == null) {
                return null;
            }
            return integral(n) && n.longValue() != Long.MIN_VALUE ? (Object) (-n.longValue()) : -n.doubleValue();
        }
    }

    /** Field paths resolve against the row, then inside {@code logContent}. */
    static Object resolve(Map<String, Object> row, List<String> path) {
        Object value = walk(row, path);
        if (value == null && !"logContent".equals(path.get(0)) && row.get("logContent") instanceof Map<?, ?> content) {
            value = walk(content, path);
        }
        return value;
    }

    private static Object walk(Object node, List<String> path) {
        Object current = node;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value != null && !(value instanceof Number n && n.doubleValue() == 0);
    }

    private static boolean equalsMatch(Expr leftExpr, Object left, Object right, Map<String, Object> row) {
        if (leftExpr instanceof Field field && field.path().equals(List.of("logContent")) && right instanceof String s) {
            return anyValueMatches(row.get("logContent"), s);
        }
        if (left == null || right == null) {
            return left == null && right == null;
        }
        Number ln = number(left);
        Number rn = number(right);
        if (ln != null && rn != null && !(left instanceof String && right instanceof String)) {
            return compareNumbers(ln, rn) == 0;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
        }
        return wildcard(String.valueOf(left), String.valueOf(right));
    }

    private static boolean anyValueMatches(Object node, String pattern) {
        if (node instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(v -> anyValueMatches(v, pattern));
        }
        if (node instanceof Collection<?> list) {
            return list.stream().anyMatch(v -> anyValueMatches(v, pattern));
        }
        return node != null && wildcard(String.valueOf(node), pattern);
    }

    private static boolean wildcard(String value, String pattern) {
        return Wildcards.matches(value, pattern);
    }

    private static boolean compare(String op, Object left, Object right) {
        Number l = number(left);
        Number r = number(right);
        if (l == null || r == null) {
            return false;
        }
        int c = compareNumbers(l, r);
        return switch (op) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            default -> c <= 0;
        };
    }

    private static Object arithmetic(String op, Object left, Object right) {
        Number l = number(left);
        Number r = number(right);
        if (l == null || r == null) {
            return null;
        }
        double a = l.doubleValue();
        double b = r.doubleValue();
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            default -> b == 0 ? null : a / b;
        };
    }

    /** A whole number that fits a long is parsed exactly; anything else as a double. */
    static Number number(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s) {
            if (WHOLE_NUMBER.matcher(s).matches()) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    // Safe to ignore: the digits overflow a long, so the value is read as a double below.
                }
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean integral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    /**
     * Compares exactly: two integers as longs, and an integer with a double by their exact values, since
     * converting a long above 2^53 to a double would make it equal to its neighbours and break transitivity.
     * Infinities and NaN, which have no exact value, order as {@link Double#compare} does.
     */
    private static int compareNumbers(Number a, Number b) {
        if (integral(a) && integral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        double da = a.doubleValue();
        double db = b.doubleValue();
        if (!Double.isFinite(da) || !Double.isFinite(db)) {
            return Double.compare(da, db);
        }
        return exact(a).compareTo(exact(b));
    }

    private static BigDecimal exact(Number n) {
        return integral(n) ? BigDecimal.valueOf(n.longValue()) : new BigDecimal(n.doubleValue());
    }

    /** {@code 15m}, {@code 1h}, {@code 2d}, {@code 30s} in milliseconds. */
    static long interval(String text) {
        // At most 9 digits: a positive interval that cannot overflow once converted to milliseconds.
        if (text == null || !text.matches("[0-9]{1,9}[dhms]") || text.matches("0+[dhms]")) {
            throw OciException.invalidParameter("Invalid interval " + text + ": use for example 1m, 1h or 1d");
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return amount * switch (text.charAt(text.length() - 1)) {
            case 'd' -> 86_400_000L;
            case 'h' -> 3_600_000L;
            case 'm' -> 60_000L;
            default -> 1_000L;
        };
    }

    private static int compareValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        Number na = number(a);
        Number nb = number(b);
        if (na != null && nb != null) {
            return compareNumbers(na, nb);
        }
        // One order for every pair, as List.sort requires: numbers first, then text alphabetically.
        if (na != null || nb != null) {
            return na != null ? -1 : 1;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ── Parser ─────────────────────────────────────────────────────────────────

    private record Named(Expr expr, String name) {
    }

    private record Aggregate(String function, Expr arg, String name) {
    }

    private static final class Parser {

        private final List<Token> tokens;
        private final String text;
        private int pos;

        Parser(List<Token> tokens, String text) {
            this.tokens = tokens;
            this.text = text;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private boolean accept(String value) {
            if (peek().is(value)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(String value) {
            if (!accept(value)) {
                throw invalid("expected " + value + " near '" + peek().text() + "'", text);
            }
        }

        Parsed query() {
            expect("search");
            List<Scope> scopes = new ArrayList<>();
            do {
                Token scope = next();
                if (scope.kind() != Kind.QUOTED && scope.kind() != Kind.STRING) {
                    throw invalid("search needs a quoted scope such as \"compartmentOcid/logGroup/log\"", text);
                }
                String[] parts = scope.text().split("/", -1);
                if (parts.length > 3 || parts[0].isBlank()) {
                    throw invalid("a scope is compartment[/logGroup[/log]]", text);
                }
                scopes.add(new Scope(parts[0], parts.length > 1 ? parts[1] : null, parts.length > 2 ? parts[2] : null));
            } while (accept(",") || peek().kind() == Kind.QUOTED);
            List<Pipe> pipes = new ArrayList<>();
            while (accept("|")) {
                pipes.add(pipe());
            }
            if (peek().kind() != Kind.END) {
                throw invalid("unexpected '" + peek().text() + "'", text);
            }
            return new Parsed(scopes, pipes);
        }

        private Pipe pipe() {
            if (accept("sort")) {
                expect("by");
                List<Expr> keys = new ArrayList<>();
                List<Boolean> descending = new ArrayList<>();
                do {
                    keys.add(expr());
                    boolean desc = accept("desc");
                    if (!desc) {
                        accept("asc");
                    }
                    descending.add(desc);
                } while (accept(","));
                return rows -> {
                    Comparator<Map<String, Object>> order = null;
                    for (int i = 0; i < keys.size(); i++) {
                        Expr key = keys.get(i);
                        Comparator<Map<String, Object>> one = (a, b) -> compareValues(key.eval(a), key.eval(b));
                        if (descending.get(i)) {
                            one = one.reversed();
                        }
                        order = order == null ? one : order.thenComparing(one);
                    }
                    List<Map<String, Object>> sorted = new ArrayList<>(rows);
                    sorted.sort(order);
                    return sorted;
                };
            }
            if (accept("top")) {
                Token n = next();
                if (n.kind() != Kind.NUMBER || !n.text().matches("[0-9]{1,9}")) {
                    throw invalid("top needs a whole row count", text);
                }
                int count = Integer.parseInt(n.text());
                expect("by");
                Expr key = expr();
                boolean ascending = accept("asc");
                accept("desc");
                return rows -> {
                    List<Map<String, Object>> sorted = new ArrayList<>(rows);
                    Comparator<Map<String, Object>> order = (a, b) -> compareValues(key.eval(a), key.eval(b));
                    sorted.sort(ascending ? order : order.reversed());
                    return sorted.subList(0, Math.min(count, sorted.size()));
                };
            }
            if (accept("dedup")) {
                List<Expr> keys = exprList();
                return rows -> {
                    Set<List<Object>> seen = new LinkedHashSet<>();
                    List<Map<String, Object>> kept = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        List<Object> key = keys.stream().map(k -> k.eval(row)).toList();
                        if (seen.add(key)) {
                            kept.add(row);
                        }
                    }
                    return kept;
                };
            }
            if (accept("select")) {
                List<Named> columns = new ArrayList<>();
                do {
                    if (accept("*")) {
                        columns.add(new Named(null, "*"));
                    } else {
                        columns.add(named());
                    }
                } while (accept(","));
                return rows -> rows.stream().map(row -> {
                    Map<String, Object> projected = new LinkedHashMap<>();
                    for (Named column : columns) {
                        if (column.expr() == null) {
                            projected.putAll(row);
                        } else {
                            projected.put(column.name(), column.expr().eval(row));
                        }
                    }
                    return projected;
                }).toList();
            }
            if (accept("count")) {
                return rows -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("count", rows.size());
                    return List.of(result);
                };
            }
            if (accept("summarize")) {
                List<Aggregate> aggregates = new ArrayList<>();
                do {
                    aggregates.add(aggregate());
                } while (accept(","));
                List<Named> groups = new ArrayList<>();
                if (accept("by")) {
                    do {
                        groups.add(named());
                    } while (accept(","));
                }
                return rows -> summarize(rows, aggregates, groups);
            }
            accept("where");
            Expr condition = expr();
            return rows -> rows.stream().filter(row -> truthy(condition.eval(row))).toList();
        }

        private Aggregate aggregate() {
            Token fn = next();
            if (fn.kind() != Kind.IDENT || !Set.of("count", "sum", "avg", "min", "max", "first", "last")
                    .contains(fn.text().toLowerCase(Locale.ROOT))) {
                throw invalid("summarize needs an aggregate function such as count()", text);
            }
            String function = fn.text().toLowerCase(Locale.ROOT);
            expect("(");
            Expr arg = peek().is(")") ? null : expr();
            expect(")");
            if (arg == null && !"count".equals(function)) {
                throw invalid(function + "() needs an argument", text);
            }
            String name = accept("as") ? identifier() : function;
            return new Aggregate(function, arg, name);
        }

        private Named named() {
            Expr expr = expr();
            String name = accept("as") ? identifier() : expr.name();
            if (name == null) {
                throw invalid("an expression needs a name: add 'as <name>'", text);
            }
            return new Named(expr, name);
        }

        private String identifier() {
            Token t = next();
            if (t.kind() != Kind.IDENT && t.kind() != Kind.QUOTED) {
                throw invalid("expected a name", text);
            }
            return t.text();
        }

        private List<Expr> exprList() {
            List<Expr> list = new ArrayList<>();
            do {
                list.add(expr());
            } while (accept(","));
            return list;
        }

        private Expr expr() {
            Expr left = and();
            while (accept("or")) {
                left = new Binary("or", left, and());
            }
            return left;
        }

        private Expr and() {
            Expr left = comparison();
            while (accept("and")) {
                left = new Binary("and", left, comparison());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            for (String op : List.of("=", "!=", ">=", "<=", ">", "<")) {
                if (peek().kind() == Kind.OP && peek().text().equals(op)) {
                    pos++;
                    return new Binary(op, left, additive());
                }
            }
            return left;
        }

        private Expr additive() {
            Expr left = multiplicative();
            while (peek().is("+") || peek().is("-")) {
                String op = next().text();
                left = new Binary(op, left, multiplicative());
            }
            return left;
        }

        private Expr multiplicative() {
            Expr left = primary();
            while (peek().is("*") || peek().is("/")) {
                String op = next().text();
                left = new Binary(op, left, primary());
            }
            return left;
        }

        private Expr primary() {
            Token t = next();
            switch (t.kind()) {
                case NUMBER -> {
                    Number n = number(t.text());
                    if (n == null) {
                        // An interval literal such as 15m.
                        return new Literal(t.text());
                    }
                    return new Literal(integral(n) || t.text().contains(".") ? n : (Object) n.longValue());
                }
                case STRING -> {
                    return new Literal(t.text());
                }
                case QUOTED -> {
                    if (peek().is(".")) {
                        return field(t.text());
                    }
                    return new Literal(t.text());
                }
                case IDENT -> {
                    String word = t.text();
                    String lower = word.toLowerCase(Locale.ROOT);
                    switch (lower) {
                        case "true" -> {
                            return new Literal(Boolean.TRUE);
                        }
                        case "false" -> {
                            return new Literal(Boolean.FALSE);
                        }
                        case "null" -> {
                            return new Literal(null);
                        }
                        default -> {
                        }
                    }
                    if (peek().is("(")) {
                        pos++;
                        List<Expr> args = peek().is(")") ? List.of() : exprList();
                        expect(")");
                        int[] arity = FUNCTIONS.get(lower);
                        if (arity == null) {
                            throw invalid("unknown function " + word + "()", text);
                        }
                        if (args.size() < arity[0] || args.size() > arity[1]) {
                            throw invalid(word + "() takes " + (arity[0] == arity[1] ? String.valueOf(arity[0])
                                    : arity[0] + (arity[1] == Integer.MAX_VALUE ? " or more" : " to " + arity[1]))
                                    + " arguments, got " + args.size(), text);
                        }
                        return new Call(lower, args);
                    }
                    return field(word);
                }
                case OP -> {
                    if (t.text().equals("(")) {
                        Expr inner = expr();
                        expect(")");
                        return inner;
                    }
                    if (t.text().equals("-")) {
                        return new Negate(primary());
                    }
                    if (t.text().equals("+")) {
                        return primary();
                    }
                    throw invalid("unexpected '" + t.text() + "'", text);
                }
                default -> throw invalid("unexpected end of query", text);
            }
        }

        private Expr field(String first) {
            List<String> path = new ArrayList<>();
            path.add(first);
            while (accept(".")) {
                Token part = next();
                if (part.kind() != Kind.IDENT && part.kind() != Kind.QUOTED) {
                    throw invalid("expected a field name after '.'", text);
                }
                path.add(part.text());
            }
            return new Field(List.copyOf(path));
        }
    }

    private static List<Map<String, Object>> summarize(List<Map<String, Object>> rows, List<Aggregate> aggregates,
                                                      List<Named> groups) {
        Map<List<Object>, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = groups.stream().map(g -> g.expr().eval(row)).toList();
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        if (groups.isEmpty() && buckets.isEmpty()) {
            buckets.put(List.of(), List.of());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> bucket : buckets.entrySet()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < groups.size(); i++) {
                out.put(groups.get(i).name(), bucket.getKey().get(i));
            }
            for (Aggregate aggregate : aggregates) {
                out.put(aggregate.name(), aggregateValue(aggregate, bucket.getValue()));
            }
            result.add(out);
        }
        return result;
    }

    private static Object aggregateValue(Aggregate aggregate, List<Map<String, Object>> rows) {
        if (aggregate.arg() == null) {
            return rows.size();
        }
        List<Object> values = rows.stream().map(r -> aggregate.arg().eval(r)).filter(Objects::nonNull).toList();
        List<Double> numbers = values.stream().map(LogQuery::number).filter(Objects::nonNull)
                .map(Number::doubleValue).toList();
        return switch (aggregate.function()) {
            case "count" -> values.size();
            case "sum" -> numbers.stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> numbers.isEmpty() ? null : numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "min" -> numbers.isEmpty() ? null : numbers.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> numbers.isEmpty() ? null : numbers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "first" -> values.isEmpty() ? null : values.get(0);
            default -> values.isEmpty() ? null : values.get(values.size() - 1);
        };
    }
}
