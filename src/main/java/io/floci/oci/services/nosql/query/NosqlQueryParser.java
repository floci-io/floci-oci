package io.floci.oci.services.nosql.query;

import io.floci.oci.services.nosql.query.NosqlTokenizer.Kind;
import io.floci.oci.services.nosql.query.NosqlTokenizer.Token;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses the SELECT subset of the NoSQL SQL dialect:
 * <pre>
 * [DECLARE $v TYPE; ...] SELECT * | expr [AS name], ... FROM table [[AS] alias]
 *     [WHERE cond] [ORDER BY expr [ASC|DESC] [NULLS FIRST|LAST], ...] [LIMIT n] [OFFSET n]
 * </pre>
 * Conditions take comparisons, AND/OR/NOT, IS [NOT] NULL, EXISTS, [NOT] IN and [NOT] BETWEEN;
 * expressions take literals, $variables, dotted JSON paths, arithmetic and the aggregates
 * count, min, max, sum and avg. UPDATE, INSERT, DELETE and GROUP BY are rejected.
 */
public final class NosqlQueryParser {

    private static final Set<String> AGGREGATES = Set.of("COUNT", "MIN", "MAX", "SUM", "AVG");
    private static final Set<String> COMPARISONS = Set.of("=", "!=", "<>", "<", "<=", ">", ">=");

    public sealed interface Expr permits Literal, Variable, PathExpr, Compare, And, Or, Not, IsNull, Exists,
            In, Between, Arithmetic, Negate, Aggregate {
    }

    public record Literal(Object value) implements Expr {
    }

    public record Variable(String name) implements Expr {
    }

    public record PathExpr(List<String> steps) implements Expr {
    }

    public record Compare(String op, Expr left, Expr right) implements Expr {
    }

    public record And(Expr left, Expr right) implements Expr {
    }

    public record Or(Expr left, Expr right) implements Expr {
    }

    public record Not(Expr operand) implements Expr {
    }

    public record IsNull(Expr operand, boolean negated) implements Expr {
    }

    public record Exists(Expr operand) implements Expr {
    }

    public record In(Expr operand, List<Expr> values, boolean negated) implements Expr {
    }

    public record Between(Expr operand, Expr low, Expr high, boolean negated) implements Expr {
    }

    public record Arithmetic(char op, Expr left, Expr right) implements Expr {
    }

    public record Negate(Expr operand) implements Expr {
    }

    /** An aggregate call; {@code argument} is null for {@code count(*)}. */
    public record Aggregate(String function, Expr argument) implements Expr {
    }

    public record SelectItem(Expr expr, String alias) {
    }

    public record OrderTerm(Expr expr, boolean descending, Boolean nullsFirst) {
    }

    /**
     * A parsed SELECT. {@code items} is null for {@code SELECT *}. {@code declared} maps each
     * DECLAREd variable (with its {@code $}) to its declared type.
     */
    public record SelectQuery(Map<String, String> declared, List<SelectItem> items, String tableName,
                              String alias, Expr where, List<OrderTerm> orderBy, Expr limit, Expr offset) {

        public boolean aggregates() {
            return items != null && items.stream().anyMatch(i -> i.expr() instanceof Aggregate);
        }
    }

    private final NosqlTokens tokens;

    private NosqlQueryParser(String statement) {
        this.tokens = new NosqlTokens(statement);
    }

    public static SelectQuery parse(String statement) {
        return new NosqlQueryParser(statement).query();
    }

    private SelectQuery query() {
        Map<String, String> declared = new LinkedHashMap<>();
        if (tokens.accept("DECLARE")) {
            do {
                Token variable = tokens.next();
                if (variable.kind() != Kind.VARIABLE) {
                    throw new NosqlSyntaxException("Expected a $variable at position " + variable.position());
                }
                declared.put(variable.text(), declaredType());
                tokens.expectSymbol(";");
            } while (tokens.peek().kind() == Kind.VARIABLE);
        }
        Token first = tokens.peek();
        if (first.is("UPDATE") || first.is("INSERT") || first.is("UPSERT") || first.is("DELETE")) {
            throw new NosqlSyntaxException(first.upper() + " statements are not supported by floci-oci;"
                    + " use UpdateRow and DeleteRow");
        }
        tokens.expect("SELECT");
        List<SelectItem> items = null;
        if (!tokens.acceptSymbol("*")) {
            items = new ArrayList<>();
            do {
                Expr expr = expression();
                String alias = null;
                if (tokens.accept("AS")) {
                    alias = tokens.identifier("a column alias");
                }
                items.add(new SelectItem(expr, alias));
            } while (tokens.acceptSymbol(","));
        }
        tokens.expect("FROM");
        String table = tokens.identifier("a table name");
        while (tokens.acceptSymbol(".")) {
            table = table + "." + tokens.identifier("a child table name");
        }
        String alias = null;
        if (tokens.accept("AS")) {
            alias = aliasName();
        } else if (tokens.peek().kind() == Kind.VARIABLE
                || (tokens.peek().kind() == Kind.IDENT && !isClauseKeyword(tokens.peek()))) {
            alias = aliasName();
        }
        Expr where = null;
        if (tokens.accept("WHERE")) {
            where = expression();
        }
        if (tokens.peek().is("GROUP")) {
            throw new NosqlSyntaxException("GROUP BY is not supported by floci-oci");
        }
        List<OrderTerm> orderBy = new ArrayList<>();
        if (tokens.accept("ORDER")) {
            tokens.expect("BY");
            do {
                Expr expr = expression();
                boolean descending = false;
                if (tokens.accept("DESC")) {
                    descending = true;
                } else {
                    tokens.accept("ASC");
                }
                Boolean nullsFirst = null;
                if (tokens.accept("NULLS")) {
                    if (tokens.accept("FIRST")) {
                        nullsFirst = true;
                    } else {
                        tokens.expect("LAST");
                        nullsFirst = false;
                    }
                }
                orderBy.add(new OrderTerm(expr, descending, nullsFirst));
            } while (tokens.acceptSymbol(","));
        }
        Expr limit = null;
        if (tokens.accept("LIMIT")) {
            limit = additive();
        }
        Expr offset = null;
        if (tokens.accept("OFFSET")) {
            offset = additive();
        }
        tokens.expectEnd();
        SelectQuery query = new SelectQuery(declared, items, table, alias, where, orderBy, limit, offset);
        if (query.aggregates() && query.items().stream().anyMatch(i -> !(i.expr() instanceof Aggregate))) {
            throw new NosqlSyntaxException("Mixing aggregate and non-aggregate SELECT expressions needs"
                    + " GROUP BY, which floci-oci does not support");
        }
        return query;
    }

    private String declaredType() {
        StringBuilder type = new StringBuilder(tokens.identifier("a variable type").toUpperCase(Locale.ROOT));
        int depth = 0;
        while (!tokens.atEnd() && (depth > 0 || !tokens.peek().isSymbol(";"))) {
            Token token = tokens.next();
            if (token.isSymbol("(")) {
                depth++;
            } else if (token.isSymbol(")")) {
                depth--;
            }
            type.append(token.text());
        }
        return type.toString();
    }

    private String aliasName() {
        Token token = tokens.next();
        if (token.kind() != Kind.IDENT && token.kind() != Kind.VARIABLE) {
            throw new NosqlSyntaxException("Expected a table alias at position " + token.position());
        }
        return token.text();
    }

    private static boolean isClauseKeyword(Token token) {
        return token.is("WHERE") || token.is("ORDER") || token.is("LIMIT") || token.is("OFFSET")
                || token.is("GROUP");
    }

    private Expr expression() {
        return or();
    }

    private Expr or() {
        Expr left = and();
        while (tokens.accept("OR")) {
            left = new Or(left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (tokens.accept("AND")) {
            left = new And(left, not());
        }
        return left;
    }

    private Expr not() {
        if (tokens.accept("NOT")) {
            return new Not(not());
        }
        if (tokens.accept("EXISTS")) {
            return new Exists(additive());
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        Token token = tokens.peek();
        if (token.kind() == Kind.SYMBOL && COMPARISONS.contains(token.text())) {
            tokens.next();
            String op = "<>".equals(token.text()) ? "!=" : token.text();
            return new Compare(op, left, additive());
        }
        if (tokens.accept("IS")) {
            boolean negated = tokens.accept("NOT");
            tokens.expect("NULL");
            return new IsNull(left, negated);
        }
        boolean negated = false;
        if (token.is("NOT") && (tokens.peek(1).is("IN") || tokens.peek(1).is("BETWEEN"))) {
            tokens.next();
            negated = true;
        }
        if (tokens.accept("IN")) {
            tokens.expectSymbol("(");
            List<Expr> values = new ArrayList<>();
            do {
                values.add(additive());
            } while (tokens.acceptSymbol(","));
            tokens.expectSymbol(")");
            return new In(left, values, negated);
        }
        if (tokens.accept("BETWEEN")) {
            Expr low = additive();
            tokens.expect("AND");
            Expr high = additive();
            return new Between(left, low, high, negated);
        }
        if (negated) {
            throw tokens.error("Expected IN or BETWEEN after NOT");
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (tokens.acceptSymbol("+")) {
                left = new Arithmetic('+', left, multiplicative());
            } else if (tokens.acceptSymbol("-")) {
                left = new Arithmetic('-', left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (tokens.acceptSymbol("*")) {
                left = new Arithmetic('*', left, unary());
            } else if (tokens.acceptSymbol("/")) {
                left = new Arithmetic('/', left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (tokens.acceptSymbol("-")) {
            return new Negate(unary());
        }
        if (tokens.acceptSymbol("+")) {
            return unary();
        }
        return primary();
    }

    private Expr primary() {
        Token token = tokens.next();
        switch (token.kind()) {
            case NUMBER -> {
                return new Literal(number(token.text()));
            }
            case STRING -> {
                return new Literal(token.text());
            }
            case VARIABLE -> {
                List<String> steps = new ArrayList<>();
                steps.add(token.text());
                pathSteps(steps);
                return steps.size() == 1 ? new Variable(token.text()) : new PathExpr(steps);
            }
            case SYMBOL -> {
                if (token.isSymbol("(")) {
                    Expr inner = expression();
                    tokens.expectSymbol(")");
                    return inner;
                }
                throw new NosqlSyntaxException("Unexpected '" + token.text() + "' at position " + token.position());
            }
            case IDENT -> {
                if (token.is("TRUE") || token.is("FALSE")) {
                    return new Literal(Boolean.parseBoolean(token.text().toLowerCase(Locale.ROOT)));
                }
                if (token.is("NULL")) {
                    return new Literal(null);
                }
                if (tokens.peek().isSymbol("(")) {
                    return functionCall(token);
                }
                List<String> steps = new ArrayList<>();
                steps.add(token.text());
                pathSteps(steps);
                return new PathExpr(steps);
            }
            default -> throw new NosqlSyntaxException("Unexpected end of statement");
        }
    }

    private void pathSteps(List<String> steps) {
        while (tokens.acceptSymbol(".")) {
            Token step = tokens.next();
            if (step.kind() != Kind.IDENT && step.kind() != Kind.STRING) {
                throw new NosqlSyntaxException("Expected a field name at position " + step.position());
            }
            steps.add(step.text());
        }
    }

    private Expr functionCall(Token name) {
        String function = name.upper();
        if (!AGGREGATES.contains(function)) {
            throw new NosqlSyntaxException("Function " + name.text() + " is not supported by floci-oci");
        }
        tokens.expectSymbol("(");
        Expr argument = null;
        if ("COUNT".equals(function) && tokens.acceptSymbol("*")) {
            tokens.expectSymbol(")");
            return new Aggregate(function, null);
        }
        argument = expression();
        tokens.expectSymbol(")");
        return new Aggregate(function, argument);
    }

    private static Object number(String text) {
        if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Double.parseDouble(text);
        }
        try {
            long value = Long.parseLong(text);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        } catch (NumberFormatException e) {
            return new BigDecimal(text);
        }
    }
}
