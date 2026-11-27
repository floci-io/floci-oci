package io.floci.oci.services.database;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-body parsing and list responses for the Database service. */
final class DatabaseRequests {

    private DatabaseRequests() {
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

    /** A whole number of at least 1, or null when absent. */
    static Integer positiveInteger(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                && number.doubleValue() >= 1) {
            return number.intValue();
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a whole number of at least 1");
    }

    /** A number above 0 (ECPU and OCPU counts may be fractional), or null when absent. */
    static Number positiveNumber(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() > 0) {
            return number;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a number above 0");
    }

    static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    /** A bare JSON array, paged with {@code limit} and {@code page}. */
    static Response list(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
