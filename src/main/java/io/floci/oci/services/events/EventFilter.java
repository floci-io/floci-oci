package io.floci.oci.services.events;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OCI Events rule conditions (Events/Concepts/filterevents.htm): fields the condition does not
 * name are ignored and {@code {}} matches everything; every named field must be present in the
 * event with the same nesting; an array matches when any of its values matches; a string value may
 * hold {@code *} wildcards anywhere, a lone {@code *} matches any value but null, and the period has
 * no special meaning.
 */
final class EventFilter {

    private EventFilter() {
    }

    static boolean matches(Map<String, Object> condition, Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            if (!event.containsKey(entry.getKey())) {
                return false;
            }
            if (!matchesValue(entry.getValue(), event.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesValue(Object expected, Object actual) {
        if (expected instanceof Map<?, ?> nested) {
            return actual instanceof Map<?, ?> actualMap
                    && matches((Map<String, Object>) nested, (Map<String, Object>) actualMap);
        }
        if (expected instanceof List<?> anyOf) {
            return anyOf.stream().anyMatch(candidate -> matchesValue(candidate, actual));
        }
        if (actual instanceof List<?> actualValues) {
            return actualValues.stream().anyMatch(value -> matchesScalar(expected, value));
        }
        return matchesScalar(expected, actual);
    }

    private static boolean matchesScalar(Object expected, Object actual) {
        if (actual == null) {
            return expected == null;
        }
        if (expected instanceof String pattern && actual instanceof String value) {
            return pattern.contains("*") ? glob(pattern).matcher(value).matches() : pattern.equals(value);
        }
        if (expected instanceof Number n && actual instanceof Number a) {
            return n.doubleValue() == a.doubleValue();
        }
        return expected != null && expected.equals(actual);
    }

    private static Pattern glob(String pattern) {
        String regex = Arrays.stream(pattern.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return Pattern.compile(regex, Pattern.DOTALL);
    }
}
