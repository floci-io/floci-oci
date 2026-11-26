package io.floci.oci.services.mysql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-body parsing and list responses shared by the MySQL HeatWave controllers and services. */
final class MysqlRequests {

    private MysqlRequests() {
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
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected an object");
    }

    static Integer integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a whole number");
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

    @SuppressWarnings("unchecked")
    static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body.get(key) instanceof Map<?, ?> value ? (Map<String, String>) value : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body.get("definedTags") instanceof Map<?, ?> value ? (Map<String, Map<String, Object>>) value : null;
    }

    static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    /** A bare JSON array, as every MySQL list returns, paged with {@code limit} and {@code page}. */
    static Response list(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
