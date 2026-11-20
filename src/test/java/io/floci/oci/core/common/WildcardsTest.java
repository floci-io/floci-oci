package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WildcardsTest {

    @Test
    void starMatchesAnyRunIncludingNone() {
        assertTrue(Wildcards.matches("error: disk full", "*disk*"));
        assertTrue(Wildcards.matches("abc", "a*c"));
        assertTrue(Wildcards.matches("ac", "a*c"));
        assertTrue(Wildcards.matches("", "*"));
        assertTrue(Wildcards.matches("abc", "abc"));
        assertFalse(Wildcards.matches("abc", "ab"));
        assertFalse(Wildcards.matches("abc", "*d*"));
        assertFalse(Wildcards.matches("abd", "a*c"));
        assertTrue(Wildcards.matches("line\nbreak", "line*break"));
    }

    @Test
    void pathologicalPatternsFinishQuickly() {
        String value = "a".repeat(20_000);
        long started = System.nanoTime();
        assertFalse(Wildcards.matches(value, "*a*a*a*a*a*a*a*a*a*a*b"));
        assertTrue(System.nanoTime() - started < 2_000_000_000L);
    }
}
