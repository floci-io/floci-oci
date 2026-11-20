package io.floci.oci.services.logging;

import io.floci.oci.core.common.OciException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogQueryTest {

    private static Map<String, Object> row(long datetime, Map<String, Object> data) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("data", data);
        content.put("type", "app");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("datetime", datetime);
        row.put("logContent", content);
        return row;
    }

    @Test
    void parsesScopesAndComments() {
        LogQuery.Parsed query = LogQuery.parse("search \"c1/g1/l1\", \"c2/g2\" \"c3\" /* all */ | count -- total");
        assertEquals(List.of(new LogQuery.Scope("c1", "g1", "l1"), new LogQuery.Scope("c2", "g2", null),
                new LogQuery.Scope("c3", null, null)), query.scopes());
    }

    @Test
    void quotedFieldsWildcardsAndRounddown() {
        List<Map<String, Object>> rows = List.of(
                row(65_000, Map.of("first name", "Ada", "code", "acc-42")),
                row(125_000, Map.of("first name", "Bob", "code", "rej-1")));

        assertEquals(1, LogQuery.parse("search \"c\" | where data.\"first name\" = 'Ada'").run(rows).size());
        assertEquals(1, LogQuery.parse("search \"c\" | data.code = \"acc-*\"").run(rows).size());
        assertEquals(1, LogQuery.parse("search \"c\" | not(data.code = 'acc-*') and type = 'app'").run(rows).size());
        assertEquals(List.of(Map.of("m", 60_000L, "n", 1), Map.of("m", 120_000L, "n", 1)),
                LogQuery.parse("search \"c\" | summarize count() as n by rounddown(datetime, '1m') as m").run(rows));
        assertEquals(List.of(Map.of("upper", "ADA"), Map.of("upper", "BOB")),
                LogQuery.parse("search \"c\" | select upper(data.\"first name\") as upper").run(rows));
    }

    @Test
    void commentMarkersInsideQuotedValuesStayPartOfTheValue() {
        List<Map<String, Object>> rows = List.of(
                row(1_000, Map.of("message", "retry--failed")),
                row(2_000, Map.of("message", "a/*x*/b")));

        assertEquals(1, LogQuery.parse("search \"c\" | where data.message = 'retry--failed' -- the dash case")
                .run(rows).size());
        assertEquals(1, LogQuery.parse("search \"c\" /* block */ | where data.message = 'a/*x*/b'").run(rows).size());
    }

    @Test
    void generatedValuesAreBounded() {
        StringBuilder query = new StringBuilder("search \"c\" | select 'a' as x");
        for (int i = 0; i < 30; i++) {
            query.append(" | select concat(x, x) as x");
        }
        LogQuery.Parsed parsed = LogQuery.parse(query.toString());
        OciException e = assertThrows(OciException.class, () -> parsed.run(List.of(row(1_000, Map.of()))));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void invalidRounddownIntervalsAreInvalidParameter() {
        List<Map<String, Object>> rows = List.of(row(1_000, Map.of()));
        for (String interval : List.of("0m", "99999999999999999999m", "5x")) {
            LogQuery.Parsed parsed = LogQuery.parse("search \"c\" | select rounddown(datetime, '" + interval + "') as t");
            OciException e = assertThrows(OciException.class, () -> parsed.run(rows), interval);
            assertEquals("InvalidParameter", e.getCode(), interval);
        }
    }

    @Test
    void substrClampsItsBounds() {
        List<Map<String, Object>> rows = List.of(row(1_000, Map.of("m", "hello")));
        assertEquals(List.of(Map.of("s", "llo")),
                LogQuery.parse("search \"c\" | select substr(data.m, 2, 99) as s").run(rows));
        assertEquals(List.of(Map.of("s", "")),
                LogQuery.parse("search \"c\" | select substr(data.m, 9) as s").run(rows));
    }

    @Test
    void rejectsWhatItCannotParse() {
        for (String bad : List.of("", "search c", "search \"c\" /* unclosed",
                "search \"c\" | select upper() as x", "search \"c\" | where rounddown(datetime) = 1",
                "search \"c\" | where nosuchfn(type) = 1", "search \"c\" | top 1.5 by datetime",
                "search \"c\" | top 99999999999 by datetime", "search \"c\" | where substr(type) = 'a'", "search \"c\" | sort data", "search \"c\" | where 'unclosed",
                "search \"c\" | summarize total()", "search \"c\" | top x by a")) {
            assertThrows(OciException.class, () -> LogQuery.parse(bad), bad);
        }
    }
}
