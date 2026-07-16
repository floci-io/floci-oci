package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtagsTest {

    @Test
    void newEtagsAreUnique() {
        assertNotEquals(Etags.newEtag(), Etags.newEtag());
    }

    @Test
    void ifMatchPassesOnMatchAndWildcard() {
        assertDoesNotThrow(() -> Etags.checkIfMatch(null, "abc"));
        assertDoesNotThrow(() -> Etags.checkIfMatch("abc", "abc"));
        assertDoesNotThrow(() -> Etags.checkIfMatch("\"abc\"", "abc"));
        assertDoesNotThrow(() -> Etags.checkIfMatch("*", "abc"));
        assertDoesNotThrow(() -> Etags.checkIfMatch("x, abc", "abc"));
    }

    @Test
    void ifMatchMismatchIs412NoEtagMatch() {
        OciException e = assertThrows(OciException.class, () -> Etags.checkIfMatch("other", "abc"));
        assertEquals("NoEtagMatch", e.getCode());
        assertEquals(412, e.getHttpStatus());
    }

    @Test
    void ifNoneMatchStarForbidsOverwriteOfExisting() {
        OciException e = assertThrows(OciException.class, () -> Etags.checkIfNoneMatch("*", "abc"));
        assertEquals("IfNoneMatchFailed", e.getCode());
        assertEquals(412, e.getHttpStatus());
        assertDoesNotThrow(() -> Etags.checkIfNoneMatch("*", null));
    }

    @Test
    void ifNoneMatchSpecificEtagFailsOnMatch() {
        assertThrows(OciException.class, () -> Etags.checkIfNoneMatch("abc", "abc"));
        assertDoesNotThrow(() -> Etags.checkIfNoneMatch("other", "abc"));
    }
}
