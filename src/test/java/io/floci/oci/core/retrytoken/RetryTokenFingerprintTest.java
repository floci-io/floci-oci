package io.floci.oci.core.retrytoken;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RetryTokenFingerprintTest {

    private static final MultivaluedMap<String, String> NO_QUERY = new MultivaluedHashMap<>();

    @Test
    void fieldOrderDoesNotChangeTheFingerprintAtAnyDepth() {
        String first = "{\"name\":\"a\",\"tags\":{\"x\":\"1\",\"y\":\"2\"},\"compartmentId\":\"c\"}";
        String reordered = "{\"compartmentId\":\"c\",\"tags\":{\"y\":\"2\",\"x\":\"1\"},\"name\":\"a\"}";

        assertEquals(fingerprint(first, NO_QUERY), fingerprint(reordered, NO_QUERY));
    }

    @Test
    void aChangedValueChangesTheFingerprint() {
        assertNotEquals(fingerprint("{\"name\":\"a\"}", NO_QUERY), fingerprint("{\"name\":\"b\"}", NO_QUERY));
    }

    @Test
    void arrayOrderStillMatters() {
        assertNotEquals(fingerprint("{\"ids\":[\"a\",\"b\"]}", NO_QUERY),
                fingerprint("{\"ids\":[\"b\",\"a\"]}", NO_QUERY));
    }

    @Test
    void theQueryStringIsPartOfTheRequestInAnyOrder() {
        MultivaluedMap<String, String> one = new MultivaluedHashMap<>();
        one.add("a", "1");
        one.add("b", "2");
        MultivaluedMap<String, String> sameReordered = new MultivaluedHashMap<>();
        sameReordered.add("b", "2");
        sameReordered.add("a", "1");
        MultivaluedMap<String, String> other = new MultivaluedHashMap<>();
        other.add("a", "9");

        assertEquals(fingerprint("{}", one), fingerprint("{}", sameReordered));
        assertNotEquals(fingerprint("{}", one), fingerprint("{}", other));
    }

    @Test
    void aBodyThatIsNotJsonIsComparedByteForByte() {
        assertEquals(fingerprint("not json", NO_QUERY), fingerprint("not json", NO_QUERY));
        assertNotEquals(fingerprint("not json", NO_QUERY), fingerprint("not  json", NO_QUERY));
        assertEquals("", RetryTokenFilter.canonicalBody(new byte[0]));
    }

    private static String fingerprint(String body, MultivaluedMap<String, String> query) {
        return RetryTokenFilter.fingerprint(body.getBytes(StandardCharsets.UTF_8), query);
    }
}
