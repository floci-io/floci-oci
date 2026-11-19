package io.floci.oci.services.events;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The examples are the ones on Oracle's filterevents.htm page, against its deletebucket event. */
class EventFilterTest {

    private static Map<String, Object> event() {
        Map<String, Object> details = new HashMap<>();
        details.put("namespace", "example_namespace");
        details.put("publicAccessType", "NoPublicAccess");
        details.put("archivalState", null);
        return Map.of(
                "eventType", "com.oraclecloud.objectstorage.deletebucket",
                "source", "objectstorage",
                "data", Map.of(
                        "compartmentName", "example_name",
                        "resourceName", "my_bucket",
                        "additionalDetails", details));
    }

    @Test
    void emptyConditionMatchesEverything() {
        assertTrue(EventFilter.matches(Map.of(), event()));
    }

    @Test
    void exactEventType() {
        assertTrue(EventFilter.matches(Map.of("eventType", "com.oraclecloud.objectstorage.deletebucket"), event()));
        assertFalse(EventFilter.matches(Map.of("eventType", "com.oraclecloud.objectstorage.createbucket"), event()));
    }

    @Test
    void arrayMatchesAnyValue() {
        assertTrue(EventFilter.matches(Map.of("eventType", List.of(
                "com.oraclecloud.objectstorage.deletebucket", "com.oraclecloud.objectstorage.createbucket")), event()));
    }

    @Test
    void nestedFieldsMustAllMatch() {
        assertTrue(EventFilter.matches(Map.of("data", Map.of(
                "compartmentName", "example_name", "resourceName", "my_bucket")), event()));
        assertFalse(EventFilter.matches(Map.of("data", Map.of(
                "compartmentName", "example_name", "resourceName", "my_bucket",
                "additionalDetails", Map.of("publicAccessType", "PublicAccess"))), event()));
    }

    @Test
    void fieldMissingFromTheEventDoesNotMatch() {
        assertFalse(EventFilter.matches(Map.of("data", Map.of("freeFormTags", Map.of("env", "dev"))), event()));
    }

    @Test
    void wildcardsAtStartMiddleAndEnd() {
        assertTrue(EventFilter.matches(Map.of("data", Map.of("resourceName", "my_bucket*")), event()));
        assertTrue(EventFilter.matches(Map.of("eventType", "com.oraclecloud.objectstorage.*bucket"), event()));
        assertTrue(EventFilter.matches(Map.of("eventType", "*.deletebucket"), event()));
        assertFalse(EventFilter.matches(Map.of("eventType", "*.createbucket"), event()));
    }

    @Test
    void periodIsNotSpecial() {
        assertFalse(EventFilter.matches(Map.of("eventType", "com.oraclecloud.objectstorageXdeletebucket"), event()));
        assertFalse(EventFilter.matches(Map.of("source", "objectstorag."), event()));
    }

    @Test
    void loneWildcardMatchesAnyValueButNull() {
        assertTrue(EventFilter.matches(Map.of("data", Map.of("additionalDetails",
                Map.of("publicAccessType", "*"))), event()));
        assertFalse(EventFilter.matches(Map.of("data", Map.of("additionalDetails",
                Map.of("archivalState", "*"))), event()));
    }

    @Test
    void loneWildcardMatchesAnyNonNullValueOfAnyType() {
        Map<String, Object> event = Map.of("data", Map.of("size", 42, "public", false,
                "tags", Map.of("team", "a"), "names", List.of("x")));
        assertTrue(EventFilter.matches(Map.of("data", Map.of("size", "*")), event));
        assertTrue(EventFilter.matches(Map.of("data", Map.of("public", "*")), event));
        assertTrue(EventFilter.matches(Map.of("data", Map.of("tags", "*")), event));
        assertTrue(EventFilter.matches(Map.of("data", Map.of("names", "*")), event));
        assertFalse(EventFilter.matches(Map.of("data", Map.of("additionalDetails", Map.of("archivalState", "*"))),
                event()));
    }
}
