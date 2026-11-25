package io.floci.oci.services.nosql.query;

import io.floci.oci.services.nosql.query.NosqlQueryParser.Aggregate;
import io.floci.oci.services.nosql.query.NosqlQueryParser.And;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Arithmetic;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Between;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Compare;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Exists;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Expr;
import io.floci.oci.services.nosql.query.NosqlQueryParser.In;
import io.floci.oci.services.nosql.query.NosqlQueryParser.IsNull;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Literal;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Negate;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Not;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Or;
import io.floci.oci.services.nosql.query.NosqlQueryParser.OrderTerm;
import io.floci.oci.services.nosql.query.NosqlQueryParser.PathExpr;
import io.floci.oci.services.nosql.query.NosqlQueryParser.SelectItem;
import io.floci.oci.services.nosql.query.NosqlQueryParser.SelectQuery;
import io.floci.oci.services.nosql.query.NosqlQueryParser.Variable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a parsed {@link SelectQuery} over a table's rows. SQL NULL (and JSON null) is a Java
 * {@code null}; a path that leads nowhere is {@link #MISSING}, which compares as unknown, is
 * false for EXISTS and IS NULL, and projects as null. Comparisons are three-valued: unknown
 * filters a row out, like false. Numbers compare by value across INTEGER, LONG, DOUBLE and
 * NUMBER. ORDER BY sorts numbers before strings before booleans before other values, with
 * nulls last ascending and first descending unless NULLS FIRST/LAST says otherwise.
 */
public final class NosqlQueryEvaluator {

    /** The value of a path that does not exist in the row. */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "MISSING";
        }
    };

    private final SelectQuery query;
    private final Map<String, Object> variables;
    private final List<String> columns;

    /**
     * @param variables bound values keyed with or without the leading {@code $}
     * @param columns   the table's column names in schema order, used to project {@code SELECT *}
     */
    public NosqlQueryEvaluator(SelectQuery query, Map<String, Object> variables, List<String> columns) {
        this.query = query;
        this.variables = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> this.variables.put(k.startsWith("$") ? k : "$" + k, v));
        }
        for (String declared : query.declared().keySet()) {
            if (!this.variables.containsKey(declared)) {
                throw new NosqlSyntaxException("No value bound for the declared variable " + declared);
            }
        }
        this.columns = columns;
    }

    public List<Map<String, Object>> execute(List<Map<String, Object>> rows) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (query.where() == null || Boolean.TRUE.equals(truth(eval(query.where(), row)))) {
                matched.add(row);
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        if (query.aggregates()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < query.items().size(); i++) {
                SelectItem item = query.items().get(i);
                result.put(itemName(item, i), aggregate((Aggregate) item.expr(), matched));
            }
            results.add(result);
        } else {
            if (!query.orderBy().isEmpty()) {
                matched.sort(rowComparator());
            }
            for (Map<String, Object> row : matched) {
                results.add(project(row));
            }
        }
        int offset = intOf(query.offset(), "OFFSET", 0);
        int limit = intOf(query.limit(), "LIMIT", Integer.MAX_VALUE);
        if (offset >= results.size()) {
            return List.of();
        }
        int end = (int) Math.min((long) offset + limit, results.size());
        return new ArrayList<>(results.subList(offset, end));
    }

    private Map<String, Object> project(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (query.items() == null) {
            for (String column : columns) {
                out.put(column, row.get(column));
            }
            return out;
        }
        for (int i = 0; i < query.items().size(); i++) {
            SelectItem item = query.items().get(i);
            Object value = eval(item.expr(), row);
            out.put(itemName(item, i), value == MISSING ? null : value);
        }
        return out;
    }

    /** A named item keeps its alias; a path keeps its last field name; anything else is Column_N. */
    static String itemName(SelectItem item, int index) {
        if (item.alias() != null) {
            return item.alias();
        }
        if (item.expr() instanceof PathExpr path && !path.steps().getLast().startsWith("$")) {
            return path.steps().getLast();
        }
        return "Column_" + (index + 1);
    }

    private int intOf(Expr expr, String clause, int fallback) {
        if (expr == null) {
            return fallback;
        }
        Object value = eval(expr, Map.of());
        if (!(value instanceof Number n) || n.longValue() < 0 || n.doubleValue() != n.longValue()) {
            throw new NosqlSyntaxException(clause + " must be a non-negative integer");
        }
        return (int) Math.min(n.longValue(), Integer.MAX_VALUE);
    }

    private Comparator<Map<String, Object>> rowComparator() {
        Comparator<Map<String, Object>> comparator = null;
        for (OrderTerm term : query.orderBy()) {
            boolean nullsFirst = term.nullsFirst() != null ? term.nullsFirst() : term.descending();
            Comparator<Map<String, Object>> single = (a, b) -> compareForSort(
                    eval(term.expr(), a), eval(term.expr(), b), term.descending(), nullsFirst);
            comparator = comparator == null ? single : comparator.thenComparing(single);
        }
        return comparator;
    }

    static int compareForSort(Object a, Object b, boolean descending, boolean nullsFirst) {
        boolean aNull = a == null || a == MISSING;
        boolean bNull = b == null || b == MISSING;
        if (aNull || bNull) {
            if (aNull && bNull) {
                return 0;
            }
            return aNull == nullsFirst ? -1 : 1;
        }
        int cmp = compareValues(a, b);
        return descending ? -cmp : cmp;
    }

    /** Total order over non-null values: numbers, then strings, then booleans, then the rest. */
    public static int compareValues(Object a, Object b) {
        int rankA = rank(a);
        int rankB = rank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        return switch (rankA) {
            case 0 -> decimal((Number) a).compareTo(decimal((Number) b));
            case 1 -> ((String) a).compareTo((String) b);
            case 2 -> Boolean.compare((Boolean) a, (Boolean) b);
            default -> String.valueOf(a).compareTo(String.valueOf(b));
        };
    }

    private static int rank(Object value) {
        if (value instanceof Number) {
            return 0;
        }
        if (value instanceof String) {
            return 1;
        }
        if (value instanceof Boolean) {
            return 2;
        }
        return 3;
    }

    static BigDecimal decimal(Number n) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        if (n instanceof Double || n instanceof Float) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(n.toString());
    }

    private Object eval(Expr expr, Map<String, Object> row) {
        return switch (expr) {
            case Literal literal -> literal.value();
            case Variable variable -> {
                if (!variables.containsKey(variable.name())) {
                    throw new NosqlSyntaxException("No value bound for the variable " + variable.name());
                }
                yield variables.get(variable.name());
            }
            case PathExpr path -> resolve(path.steps(), row);
            case Compare compare -> compare(compare.op(), eval(compare.left(), row), eval(compare.right(), row));
            case And and -> {
                Boolean left = truth(eval(and.left(), row));
                if (Boolean.FALSE.equals(left)) {
                    yield false;
                }
                Boolean right = truth(eval(and.right(), row));
                if (Boolean.FALSE.equals(right)) {
                    yield false;
                }
                yield left == null || right == null ? null : true;
            }
            case Or or -> {
                Boolean left = truth(eval(or.left(), row));
                if (Boolean.TRUE.equals(left)) {
                    yield true;
                }
                Boolean right = truth(eval(or.right(), row));
                if (Boolean.TRUE.equals(right)) {
                    yield true;
                }
                yield left == null || right == null ? null : false;
            }
            case Not not -> {
                Boolean value = truth(eval(not.operand(), row));
                yield value == null ? null : !value;
            }
            case IsNull isNull -> {
                Object value = eval(isNull.operand(), row);
                boolean nullValue = value == null;
                yield value == MISSING ? false : nullValue != isNull.negated();
            }
            case Exists exists -> eval(exists.operand(), row) != MISSING;
            case In in -> {
                Object value = eval(in.operand(), row);
                Boolean found = false;
                for (Expr candidate : in.values()) {
                    Boolean equal = truth(compare("=", value, eval(candidate, row)));
                    if (Boolean.TRUE.equals(equal)) {
                        found = true;
                        break;
                    }
                    if (equal == null) {
                        found = null;
                    }
                }
                yield found == null ? null : found != in.negated();
            }
            case Between between -> {
                Object value = eval(between.operand(), row);
                Boolean low = truth(compare(">=", value, eval(between.low(), row)));
                Boolean high = truth(compare("<=", value, eval(between.high(), row)));
                Boolean inside = Boolean.FALSE.equals(low) || Boolean.FALSE.equals(high) ? Boolean.FALSE
                        : low == null || high == null ? null : Boolean.TRUE;
                yield inside == null ? null : inside != between.negated();
            }
            case Arithmetic arithmetic -> arithmetic(arithmetic.op(), eval(arithmetic.left(), row),
                    eval(arithmetic.right(), row));
            case Negate negate -> {
                Object value = eval(negate.operand(), row);
                yield value instanceof Number n ? narrow(decimal(n).negate(), isIntegral(n)) : null;
            }
            case Aggregate aggregate -> throw new NosqlSyntaxException(
                    aggregate.function().toLowerCase(Locale.ROOT) + "() is only allowed in the SELECT list");
        };
    }

    private Object resolve(List<String> steps, Map<String, Object> row) {
        int start = 0;
        if (steps.size() > 1 && matchesTable(steps.getFirst())) {
            start = 1;
        }
        Object current = row;
        for (int i = start; i < steps.size(); i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return MISSING;
            }
            String step = steps.get(i);
            if (map.containsKey(step)) {
                current = map.get(step);
            } else if (i == start) {
                Object found = MISSING;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && key.equalsIgnoreCase(step)) {
                        found = entry.getValue();
                        break;
                    }
                }
                if (found == MISSING) {
                    return MISSING;
                }
                current = found;
            } else {
                return MISSING;
            }
        }
        return current;
    }

    private boolean matchesTable(String step) {
        if (query.alias() != null && step.equalsIgnoreCase(query.alias())) {
            return true;
        }
        return step.equalsIgnoreCase(query.tableName()) || step.equalsIgnoreCase("$" + query.tableName());
    }

    private static Boolean truth(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    static Object compare(String op, Object left, Object right) {
        if (left == null || right == null || left == MISSING || right == MISSING) {
            return null;
        }
        if (rank(left) != rank(right) || rank(left) == 3) {
            if (rank(left) == 3 && rank(right) == 3 && ("=".equals(op) || "!=".equals(op))) {
                return left.equals(right) == "=".equals(op);
            }
            return "!=".equals(op);
        }
        int cmp = compareValues(left, right);
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> throw new NosqlSyntaxException("Unknown operator " + op);
        };
    }

    private static Object arithmetic(char op, Object left, Object right) {
        if (!(left instanceof Number l) || !(right instanceof Number r)) {
            return null;
        }
        boolean integral = isIntegral(l) && isIntegral(r);
        BigDecimal a = decimal(l);
        BigDecimal b = decimal(r);
        return switch (op) {
            case '+' -> narrow(a.add(b), integral);
            case '-' -> narrow(a.subtract(b), integral);
            case '*' -> narrow(a.multiply(b), integral);
            case '/' -> {
                if (b.signum() == 0) {
                    yield null;
                }
                yield a.divide(b, MathContext.DECIMAL64).doubleValue();
            }
            default -> null;
        };
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
    }

    private static Object narrow(BigDecimal value, boolean integral) {
        if (!integral) {
            return value.doubleValue();
        }
        long asLong = value.longValue();
        if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
            return (int) asLong;
        }
        return asLong;
    }

    private Object aggregate(Aggregate aggregate, List<Map<String, Object>> rows) {
        if (aggregate.argument() == null) {
            return rows.size();
        }
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = eval(aggregate.argument(), row);
            if (value != null && value != MISSING) {
                values.add(value);
            }
        }
        return switch (aggregate.function()) {
            case "COUNT" -> values.size();
            case "MIN" -> values.stream().min(NosqlQueryEvaluator::compareValues).orElse(null);
            case "MAX" -> values.stream().max(NosqlQueryEvaluator::compareValues).orElse(null);
            case "SUM", "AVG" -> {
                List<Number> numbers = values.stream().filter(Number.class::isInstance).map(Number.class::cast)
                        .toList();
                if (numbers.isEmpty()) {
                    yield null;
                }
                BigDecimal sum = BigDecimal.ZERO;
                boolean integral = true;
                for (Number n : numbers) {
                    sum = sum.add(decimal(n));
                    integral &= isIntegral(n);
                }
                if ("AVG".equals(aggregate.function())) {
                    yield sum.divide(BigDecimal.valueOf(numbers.size()), MathContext.DECIMAL64).doubleValue();
                }
                if (integral) {
                    yield Long.valueOf(sum.longValue());
                }
                yield Double.valueOf(sum.doubleValue());
            }
            default -> throw new NosqlSyntaxException("Unknown aggregate " + aggregate.function());
        };
    }
}
