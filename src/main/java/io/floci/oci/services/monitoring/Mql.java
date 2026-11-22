package io.floci.oci.services.monitoring;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Wildcards;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parser for the subset of the Monitoring Query Language (Monitoring/Reference/mql.htm) that
 * floci-oci evaluates:
 *
 * <pre>
 *   query     := term (("&amp;&amp;" | "||") term)*
 *   term      := arith [predicate]
 *   arith     := [number op] metric [op number]          op: + - * / %
 *   metric    := name "[" interval "]" ["{" dims "}"] ("." call)*
 *   call      := grouping() | groupBy(dim, ...) | mean() | avg() | count() | sum() | min() | max()
 *              | first() | last() | rate() | increment() | percentile(p) | absent([period])
 *   predicate := (&gt; | &gt;= | &lt; | &lt;= | == | !=) number | [not] in (number, number)
 *   dims      := dim (("," dim))*      dim: key (= | =~ | !=) "value"
 * </pre>
 *
 * Arithmetic between two metrics and nested queries are not supported and answer
 * {@code 400 InvalidParameter}.
 */
public final class Mql {

    static final Set<String> STATISTICS = Set.of("mean", "avg", "count", "sum", "min", "max", "first", "last",
            "rate", "increment", "percentile", "absent");
    /** "The default absence detection period is two hours" (mql.htm). */
    static final Duration DEFAULT_ABSENCE_PERIOD = Duration.ofHours(2);
    static final Duration MIN_ABSENCE_PERIOD = Duration.ofMinutes(1);
    static final Duration MAX_ABSENCE_PERIOD = Duration.ofDays(3);
    private static final Pattern INTERVAL = Pattern.compile("([0-9]+)([mhd])");

    private Mql() {
    }

    /** A whole query: one or more terms joined by {@code &&} or {@code ||}. */
    public record Query(List<Term> terms, List<String> joins) {

        public boolean hasPredicate() {
            return terms.stream().anyMatch(t -> t.predicate() != null);
        }
    }

    public record Term(MetricSelector metric, Arithmetic arithmetic, Predicate predicate, String text) {
    }

    /** {@code number op metric} when {@code numberFirst}, else {@code metric op number}; null when absent. */
    public record Arithmetic(char op, double number, boolean numberFirst) {

        double apply(double value) {
            double left = numberFirst ? number : value;
            double right = numberFirst ? value : number;
            return switch (op) {
                case '+' -> left + right;
                case '-' -> left - right;
                case '*' -> left * right;
                case '/' -> left / right;
                default -> left % right;
            };
        }
    }

    public record DimensionFilter(String key, String operator, String value) {

        boolean matches(Map<String, String> dimensions) {
            String actual = dimensions.get(key);
            boolean match = switch (operator) {
                case "=~" -> actual != null && fuzzy(value, actual);
                default -> value.equals(actual);
            };
            return "!=".equals(operator) ? actual == null || !value.equals(actual) : match;
        }

        private static boolean fuzzy(String value, String actual) {
            for (String alternative : value.split("\\|", -1)) {
                if (Wildcards.matches(actual, alternative)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The metric part of a term. {@code groupBy} is null when there is no grouping, empty for
     * {@code grouping()}, else the dimensions to group by.
     */
    public record MetricSelector(String name, Duration interval, List<DimensionFilter> filters,
                                 List<String> groupBy, String statistic, double percentile,
                                 Duration absencePeriod) {
    }

    /** {@code > 80}, {@code in (60, 80)} and the rest; {@code absent()} terms have none. */
    public record Predicate(String operator, double low, double high) {

        public boolean test(double value) {
            return switch (operator) {
                case ">" -> value > low;
                case ">=" -> value >= low;
                case "<" -> value < low;
                case "<=" -> value <= low;
                case "==" -> value == low;
                case "!=" -> value != low;
                case "in" -> value >= low && value <= high;
                default -> value < low || value > high;
            };
        }
    }

    public static Query parse(String text) {
        if (text == null || text.isBlank()) {
            throw OciException.missingParameter("query is required");
        }
        Parser parser = new Parser(text);
        Query query = parser.query();
        parser.skipSpace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected input");
        }
        return query;
    }

    /** Parses an MQL interval or a resolution: {@code 1m}-{@code 60m}, {@code 1h}-{@code 24h}, {@code 1d}. */
    public static Duration interval(String text) {
        Matcher m = INTERVAL.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw OciException.invalidParameter("Invalid interval " + text + ": use 1m-60m, 1h-24h or 1d");
        }
        // Three digits cover every valid interval; longer ones are invalid and could overflow.
        int amount = m.group(1).length() > 3 ? 0 : Integer.parseInt(m.group(1));
        Duration duration = switch (m.group(2)) {
            case "m" -> amount >= 1 && amount <= 60 ? Duration.ofMinutes(amount) : null;
            case "h" -> amount >= 1 && amount <= 24 ? Duration.ofHours(amount) : null;
            default -> amount == 1 ? Duration.ofDays(1) : null;
        };
        if (duration == null) {
            throw OciException.invalidParameter("Invalid interval " + text + ": use 1m-60m, 1h-24h or 1d");
        }
        return duration;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Query query() {
            List<Term> terms = new ArrayList<>();
            List<String> joins = new ArrayList<>();
            terms.add(term());
            while (true) {
                skipSpace();
                if (text.startsWith("&&", pos) || text.startsWith("||", pos)) {
                    joins.add(text.substring(pos, pos + 2));
                    pos += 2;
                    terms.add(term());
                } else {
                    return new Query(terms, joins);
                }
            }
        }

        Term term() {
            skipSpace();
            int start = pos;
            Arithmetic arithmetic = null;
            if (peekNumber()) {
                double number = number();
                char op = operator();
                arithmetic = new Arithmetic(op, number, true);
            }
            MetricSelector metric = metric();
            skipSpace();
            if (arithmetic == null && pos < text.length() && "+-*/%".indexOf(text.charAt(pos)) >= 0) {
                char op = text.charAt(pos++);
                skipSpace();
                if (!peekNumber()) {
                    throw error("arithmetic between two metrics is not supported");
                }
                arithmetic = new Arithmetic(op, number(), false);
            }
            Predicate predicate = predicate();
            if (predicate != null && "absent".equals(metric.statistic())) {
                throw error("absent() takes no predicate");
            }
            return new Term(metric, arithmetic, predicate, text.substring(start, pos).trim());
        }

        private char operator() {
            skipSpace();
            if (pos >= text.length() || "+-*/%".indexOf(text.charAt(pos)) < 0) {
                throw error("expected an arithmetic operator");
            }
            return text.charAt(pos++);
        }

        MetricSelector metric() {
            skipSpace();
            String name = metricName();
            skipSpace();
            expect('[');
            int close = text.indexOf(']', pos);
            if (close < 0) {
                throw error("unclosed interval");
            }
            Duration interval = interval(text.substring(pos, close));
            pos = close + 1;
            skipSpace();
            List<DimensionFilter> filters = new ArrayList<>();
            if (peek('{')) {
                pos++;
                skipSpace();
                while (!peek('}')) {
                    filters.add(dimension());
                    skipSpace();
                    if (peek(',')) {
                        pos++;
                        skipSpace();
                    } else if (!peek('}')) {
                        throw error("expected , or }");
                    }
                }
                pos++;
            }
            List<String> groupBy = null;
            String statistic = null;
            double percentile = 0;
            Duration absence = DEFAULT_ABSENCE_PERIOD;
            skipSpace();
            while (peek('.')) {
                pos++;
                String call = identifier("function");
                skipSpace();
                expect('(');
                List<String> args = arguments();
                switch (call) {
                    case "grouping" -> {
                        if (!args.isEmpty()) {
                            throw error("grouping() takes no arguments");
                        }
                        groupBy = List.of();
                    }
                    case "groupBy" -> {
                        if (args.isEmpty()) {
                            throw error("groupBy() needs at least one dimension");
                        }
                        groupBy = args;
                    }
                    default -> {
                        if (!STATISTICS.contains(call)) {
                            throw error("unknown function " + call + "()");
                        }
                        if (statistic != null) {
                            throw error("only one statistic is supported");
                        }
                        statistic = call;
                        if ("percentile".equals(call)) {
                            percentile = percentileArgument(args);
                        } else if ("absent".equals(call)) {
                            if (args.size() > 1) {
                                throw error("absent() takes at most one argument, the absence detection period");
                            }
                            if (!args.isEmpty()) {
                                absence = absencePeriod(args.get(0));
                            }
                        } else if (!args.isEmpty()) {
                            throw error(call + "() takes no arguments");
                        }
                    }
                }
                skipSpace();
            }
            if (statistic == null) {
                throw error("a statistic such as mean() is required");
            }
            return new MetricSelector(name, interval, filters, groupBy, statistic, percentile, absence);
        }

        private double percentileArgument(List<String> args) {
            try {
                double p = Double.parseDouble(args.size() == 1 ? args.get(0) : "");
                if (p > 0 && p < 1) {
                    return p;
                }
            } catch (NumberFormatException e) {
                // reported below
            }
            throw error("percentile() takes one value greater than 0.0 and less than 1.0");
        }

        /**
         * {@code absent(20h)}, or {@code absent(20)} in hours as mql.htm's example writes it. "Valid values
         * range from one minute (1m) to three days (3d or 72h)" (Monitoring/Reference/mql.htm).
         */
        private Duration absencePeriod(String arg) {
            String value = arg.trim().toLowerCase(Locale.ROOT);
            Duration period = null;
            if (value.matches("[0-9]{1,5}")) {
                period = Duration.ofHours(Long.parseLong(value));
            } else if (value.matches("[0-9]{1,5}[mhd]")) {
                long amount = Long.parseLong(value.substring(0, value.length() - 1));
                period = switch (value.charAt(value.length() - 1)) {
                    case 'm' -> Duration.ofMinutes(amount);
                    case 'h' -> Duration.ofHours(amount);
                    default -> Duration.ofDays(amount);
                };
            }
            if (period == null || period.compareTo(MIN_ABSENCE_PERIOD) < 0
                    || period.compareTo(MAX_ABSENCE_PERIOD) > 0) {
                throw error("invalid absence detection period " + arg + ": it must be from 1m to 3d");
            }
            return period;
        }

        private List<String> arguments() {
            List<String> args = new ArrayList<>();
            int close = text.indexOf(')', pos);
            if (close < 0) {
                throw error("unclosed (");
            }
            String inside = text.substring(pos, close).trim();
            pos = close + 1;
            if (!inside.isEmpty()) {
                for (String arg : inside.split(",")) {
                    args.add(arg.trim());
                }
            }
            return args;
        }

        private DimensionFilter dimension() {
            String key = dimensionName();
            skipSpace();
            String operator;
            if (text.startsWith("=~", pos)) {
                operator = "=~";
            } else if (text.startsWith("!=", pos)) {
                operator = "!=";
            } else if (peek('=')) {
                operator = "=";
            } else {
                throw error("expected =, =~ or != after " + key);
            }
            pos += operator.length();
            skipSpace();
            expect('"');
            int close = text.indexOf('"', pos);
            if (close < 0) {
                throw error("unclosed dimension value");
            }
            String value = text.substring(pos, close);
            pos = close + 1;
            return new DimensionFilter(key, operator, value);
        }

        private Predicate predicate() {
            skipSpace();
            for (String op : List.of(">=", "<=", "==", "!=", ">", "<")) {
                if (text.startsWith(op, pos)) {
                    pos += op.length();
                    skipSpace();
                    return new Predicate(op, number(), 0);
                }
            }
            String rest = text.substring(pos).toLowerCase(Locale.ROOT);
            String op = rest.startsWith("not in") ? "not in" : rest.startsWith("in") ? "in" : null;
            if (op == null || (op.equals("in") && rest.length() > 2 && Character.isLetterOrDigit(rest.charAt(2)))) {
                return null;
            }
            pos += op.length();
            skipSpace();
            expect('(');
            skipSpace();
            double low = number();
            skipSpace();
            expect(',');
            skipSpace();
            double high = number();
            skipSpace();
            expect(')');
            return new Predicate(op, low, high);
        }

        private boolean peekNumber() {
            skipSpace();
            if (pos >= text.length()) {
                return false;
            }
            char c = text.charAt(pos);
            return Character.isDigit(c) || c == '.'
                    || (c == '-' && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1)));
        }

        private double number() {
            skipSpace();
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
            }
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            try {
                return Double.parseDouble(text.substring(start, pos));
            } catch (NumberFormatException e) {
                pos = start;
                throw error("expected a number");
            }
        }

        /** A metric name runs to its interval, and may contain dots ({@code my_app.success_rate}). */
        private String metricName() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != '[' && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (start == pos || !Character.isLetter(text.charAt(start))) {
                throw error("expected a metric name");
            }
            return text.substring(start, pos);
        }

        /**
         * A dimension name as ingestion accepts it, printable ASCII without spaces (oci-go-sdk
         * {@code MetricDataDetails.Dimensions}), up to the filter's operator. The characters MQL itself
         * uses around a name ({@code = ! ~ , { } "}) end it.
         */
        private String dimensionName() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) > ' ' && text.charAt(pos) <= '~'
                    && "=!~,{}\"".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            if (start == pos) {
                throw error("expected a dimension name");
            }
            return text.substring(start, pos);
        }

        private String identifier(String what) {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos))
                    || "_-$.".indexOf(text.charAt(pos)) >= 0 && !"function".equals(what))) {
                pos++;
            }
            if (start == pos || !Character.isLetter(text.charAt(start))) {
                throw error("expected " + what);
            }
            return text.substring(start, pos);
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected " + c);
            }
            pos++;
        }

        void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        OciException error(String reason) {
            return OciException.invalidParameter("Invalid query at position " + pos + ": " + reason
                    + " (query: " + text + ")");
        }
    }
}
