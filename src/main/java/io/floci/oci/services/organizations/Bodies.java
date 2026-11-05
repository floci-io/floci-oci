package io.floci.oci.services.organizations;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-body parsing and collection responses shared by the Tenant Manager controllers: the
 * SDK's field types are checked here, so each controller rejects a wrong shape the same way.
 */
final class Bodies {

    private Bodies() {
    }

    static Response collection(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a string");
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

    /** {@code freeformTags} as the SDK types it, {@code map[string]string}. */
    static Map<String, String> freeformTags(Map<String, Object> body) {
        Object value = body.get("freeformTags");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw OciException.invalidParameter("Invalid freeformTags: expected an object");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String tag)) {
                throw OciException.invalidParameter("Invalid freeformTags." + entry.getKey() + ": expected a string");
            }
            tags.put((String) entry.getKey(), tag);
        }
        return tags;
    }

    /** {@code definedTags} as the SDK types it, {@code map[string]map[string]interface{}}. */
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        Object value = body.get("definedTags");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw OciException.invalidParameter("Invalid definedTags: expected an object");
        }
        Map<String, Map<String, Object>> tags = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> namespace)) {
                throw OciException.invalidParameter("Invalid definedTags." + entry.getKey() + ": expected an object");
            }
            Map<String, Object> keys = new LinkedHashMap<>();
            namespace.forEach((key, tag) -> keys.put((String) key, tag));
            tags.put((String) entry.getKey(), keys);
        }
        return tags;
    }
}
