package io.floci.oci.services.nosql.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NosqlQueryEvaluatorTest {

    private static final List<String> COLUMNS = List.of("id", "name", "age", "info");

    private static Map<String, Object> row(int id, String name, Object age, Map<String, Object> info) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("age", age);
        row.put("info", info);
        return row;
    }

    private static final List<Map<String, Object>> ROWS = List.of(
            row(1, "ana", 31, Map.of("city", "Lima", "tier", 2)),
            row(2, "bo", 25, Map.of("city", "Oslo")),
            row(3, "cy", null, Map.of("city", "Lima", "tier", 1)),
            row(4, "di", 40.5, null),
            row(5, "ed", "unknown", Map.of("city", "Rome", "tier", 3)));

    private static List<Map<String, Object>> run(String sql) {
        return run(sql, Map.of());
    }

    private static List<Map<String, Object>> run(String sql, Map<String, Object> variables) {
        return new NosqlQueryEvaluator(NosqlQueryParser.parse(sql), variables, COLUMNS).execute(ROWS);
    }

    private static List<Object> ids(List<Map<String, Object>> results) {
        return results.stream().map(r -> r.get("id")).toList();
    }

    @Test
    void selectStarProjectsEveryColumn() {
        List<Map<String, Object>> results = run("SELECT * FROM users");
        assertEquals(5, results.size());
        assertEquals(COLUMNS, List.copyOf(results.getFirst().keySet()));
    }

    @Test
    void nestedWhereWithJsonPaths() {
        assertEquals(List.of(1, 3), ids(run("SELECT id FROM users u WHERE u.info.city = 'Lima'"
                + " AND (u.info.tier < 3 OR u.age IS NULL)")));
        assertEquals(List.of(5), ids(run("SELECT id FROM users WHERE NOT (info.city = 'Lima' OR"
                + " info.city = \"Oslo\") AND EXISTS info.tier")));
        assertEquals(List.of(2, 4), ids(run("SELECT id FROM users $u WHERE NOT EXISTS $u.info.tier")));
        assertEquals(List.of(1, 2), ids(run("SELECT id FROM users WHERE age BETWEEN 20 AND 35")));
        assertEquals(List.of(2, 5), ids(run("SELECT id FROM users WHERE name IN ('bo', 'ed', 'zz')")));
        assertEquals(List.of(3), ids(run("SELECT id FROM users WHERE age IS NULL")));
    }

    @Test
    void comparisonsWithNullOrMissingAreUnknown() {
        assertEquals(List.of(), ids(run("SELECT id FROM users WHERE info.missing = 1")));
        assertEquals(List.of(), ids(run("SELECT id FROM users WHERE info.missing != 1")));
        assertEquals(List.of(1, 2, 4), ids(run("SELECT id FROM users WHERE age > 1")));
    }

    @Test
    void mixedTypeOrderByPutsNumbersBeforeStringsAndNullsLast() {
        assertEquals(List.of(2, 1, 4, 5, 3), ids(run("SELECT id, age FROM users ORDER BY age")));
        assertEquals(List.of(3, 5, 4, 1, 2), ids(run("SELECT id FROM users ORDER BY age DESC")));
        assertEquals(List.of(3, 2, 1, 4, 5), ids(run("SELECT id FROM users ORDER BY age NULLS FIRST")));
    }

    @Test
    void limitAndOffset() {
        assertEquals(List.of(2, 3), ids(run("SELECT id FROM users ORDER BY id LIMIT 2 OFFSET 1")));
        assertEquals(List.of(), ids(run("SELECT id FROM users OFFSET 10")));
    }

    @Test
    void declaredVariablesBind() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("$city", "Lima");
        variables.put("n", 1);
        assertEquals(List.of(3), ids(run("DECLARE $city STRING; $n INTEGER; SELECT id FROM users"
                + " WHERE info.city = $city ORDER BY id DESC LIMIT $n", variables)));
        assertThrows(NosqlSyntaxException.class,
                () -> run("DECLARE $city STRING; SELECT id FROM users WHERE info.city = $city"));
    }

    @Test
    void aggregates() {
        Map<String, Object> result = run("SELECT count(*), count(age) AS aged, min(id), max(id), sum(id),"
                + " avg(id) FROM users").getFirst();
        assertEquals(5, result.get("Column_1"));
        assertEquals(4, result.get("aged"));
        assertEquals(1, result.get("Column_3"));
        assertEquals(5, result.get("Column_4"));
        assertEquals(15L, result.get("Column_5"));
        assertEquals(3.0, result.get("Column_6"));
        assertEquals(2, run("SELECT count(*) FROM users WHERE info.city = 'Lima'").getFirst().get("Column_1"));
    }

    @Test
    void integerOverflowWidensToNumberInsteadOfWrapping() {
        long big = 4611686018427387904L;
        List<Map<String, Object>> rows = List.of(Map.of("id", 1, "v", big), Map.of("id", 2, "v", big));
        List<String> columns = List.of("id", "v");
        BigDecimal twice = BigDecimal.valueOf(big).multiply(BigDecimal.valueOf(2));

        Map<String, Object> sum = new NosqlQueryEvaluator(NosqlQueryParser.parse("SELECT sum(v) FROM t"),
                Map.of(), columns).execute(rows).getFirst();
        assertEquals(twice, sum.get("Column_1"));

        Map<String, Object> first = new NosqlQueryEvaluator(NosqlQueryParser.parse("SELECT v + v AS added,"
                + " v * 2 AS doubled, v * 2 - v AS back, -v - v - v AS negative, v - 1 AS small FROM t WHERE id = 1"),
                Map.of(), columns).execute(rows).getFirst();
        assertEquals(twice, first.get("added"));
        assertEquals(twice, first.get("doubled"));
        assertEquals(new BigDecimal(big), first.get("back"));
        assertEquals(BigDecimal.valueOf(big).multiply(BigDecimal.valueOf(3)).negate(), first.get("negative"));
        assertEquals(big - 1, first.get("small"));

        Map<String, Object> minimum = new NosqlQueryEvaluator(NosqlQueryParser.parse("SELECT -v AS n FROM t"
                + " WHERE id = 1"), Map.of(), columns).execute(List.of(Map.of("id", 1, "v", Long.MIN_VALUE)))
                .getFirst();
        assertEquals(BigDecimal.valueOf(Long.MIN_VALUE).negate(), minimum.get("n"));
    }

    @Test
    void projectionNamesAndArithmetic() {
        Map<String, Object> first = run("SELECT u.info.city, id * 10 AS scaled, id + 1 FROM users u"
                + " WHERE id = 1").getFirst();
        assertEquals("Lima", first.get("city"));
        assertEquals(10, first.get("scaled"));
        assertEquals(2, first.get("Column_3"));
    }

    @Test
    void unsupportedStatementsAreRejected() {
        assertThrows(NosqlSyntaxException.class, () -> NosqlQueryParser.parse("UPDATE users SET age = 1"));
        assertThrows(NosqlSyntaxException.class,
                () -> NosqlQueryParser.parse("SELECT name, count(*) FROM users GROUP BY name"));
        assertThrows(NosqlSyntaxException.class, () -> NosqlQueryParser.parse("SELECT id, count(*) FROM users"));
        assertThrows(NosqlSyntaxException.class, () -> NosqlQueryParser.parse("SELECT FROM users"));
    }
}
