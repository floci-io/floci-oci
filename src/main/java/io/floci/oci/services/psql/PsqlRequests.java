package io.floci.oci.services.psql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-body parsing shared by the Database with PostgreSQL services. */
final class PsqlRequests {

    private PsqlRequests() {
    }

    static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    static String requireString(Map<String, Object> body, String key) {
        String value = string(body, key);
        if (value == null) {
            throw OciException.missingParameter("Missing required parameter: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> body, String key) {
        return body.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : null;
    }

    static Map<String, Object> requireMap(Map<String, Object> body, String key) {
        Map<String, Object> value = map(body, key);
        if (value == null) {
            throw OciException.missingParameter("Missing required parameter: " + key);
        }
        return value;
    }

    /** A list of JSON objects; an entry that is not one is {@code 400 InvalidParameter}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof List<?> value)) {
            return List.of();
        }
        for (Object entry : value) {
            if (!(entry instanceof Map<?, ?>)) {
                throw OciException.invalidParameter("Every entry of " + key + " must be an object");
            }
        }
        return (List<Map<String, Object>>) value;
    }

    /**
     * A JSON number that is a whole number fitting a long, as an int64 field needs; anything
     * else is {@code 400 InvalidParameter}, rather than truncated.
     */
    static long wholeNumber(Number value, String key) {
        try {
            return switch (value) {
                case Integer i -> i;
                case Long l -> l;
                case Short sh -> sh;
                case Byte b -> b;
                case BigInteger big -> big.longValueExact();
                case BigDecimal decimal -> decimal.longValueExact();
                default -> new BigDecimal(value.toString()).longValueExact();
            };
        } catch (ArithmeticException | NumberFormatException e) {
            throw OciException.invalidParameter(key + " must be a whole number: " + value);
        }
    }

    static Integer integer(Map<String, Object> body, String key) {
        return body.get(key) instanceof Number number ? number.intValue() : null;
    }

    static int positive(Map<String, Object> body, String key, int fallback) {
        Integer value = integer(body, key);
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw OciException.invalidParameter(key + " must be at least 1");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body.get(key) instanceof Map<?, ?> value ? (Map<String, String>) value : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body.get("definedTags") instanceof Map<?, ?> value ? (Map<String, Map<String, Object>>) value : null;
    }

    static Boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a boolean");
    }

    static List<String> stringList(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a list of strings");
    }

    static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    /** A {@code {"items": [...]}} collection, paged with {@code limit} and {@code page}. */
    static Response collection(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
