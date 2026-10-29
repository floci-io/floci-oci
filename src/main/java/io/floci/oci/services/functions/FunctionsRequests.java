package io.floci.oci.services.functions;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request parsing and list paging shared by the Functions controllers of both API versions. */
final class FunctionsRequests {

    /** The optional Application fields floci-oci stores and returns as sent. */
    static final Set<String> APPLICATION_SETTINGS = Set.of("networkSecurityGroupIds", "syslogUrl", "traceConfig",
            "logging", "imagePolicyConfig", "securityAttributes");
    /** The optional Function fields floci-oci stores and returns as sent. */
    static final Set<String> FUNCTION_SETTINGS = Set.of("provisionedConcurrencyConfig",
            "detachedModeTimeoutInSeconds", "failureDestination", "successDestination", "traceConfig");

    private static final Set<String> LINE_FORMATS = Set.of("JSON", "PLAIN_TEXT");
    private static final Set<String> CONCURRENCY_STRATEGIES = Set.of("CONSTANT", "NONE");
    private static final Set<String> DESTINATION_KINDS = Set.of("NOTIFICATION", "QUEUE", "STREAM", "NONE");

    private FunctionsRequests() {
    }

    static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    static Integer integer(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.intValue() : null;
    }

    static Long longValue(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.longValue() : null;
    }

    static List<String> stringList(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return null;
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }

    /**
     * The optional settings among {@code keys} that the body carries, with the enums of the
     * polymorphic ones checked: {@code logging.lineFormat}, {@code provisionedConcurrencyConfig.strategy}
     * and the destinations' {@code kind}.
     */
    static Map<String, Object> settings(Map<String, Object> body, Set<String> keys) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (body == null) {
            return settings;
        }
        for (String key : keys) {
            if (body.get(key) != null) {
                settings.put(key, body.get(key));
            }
        }
        requireEnum(map(settings, "logging"), "lineFormat", LINE_FORMATS, "logging");
        requireEnum(map(settings, "provisionedConcurrencyConfig"), "strategy", CONCURRENCY_STRATEGIES,
                "provisionedConcurrencyConfig");
        requireEnum(map(settings, "failureDestination"), "kind", DESTINATION_KINDS, "failureDestination");
        requireEnum(map(settings, "successDestination"), "kind", DESTINATION_KINDS, "successDestination");
        if (settings.containsKey("detachedModeTimeoutInSeconds")
                && !(settings.get("detachedModeTimeoutInSeconds") instanceof Number)) {
            throw OciException.invalidParameter("detachedModeTimeoutInSeconds must be a number");
        }
        return settings;
    }

    static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    private static void requireEnum(Map<String, Object> object, String key, Set<String> allowed, String owner) {
        if (object != null && object.get(key) != null) {
            requireEnum(String.valueOf(object.get(key)), allowed, owner + "." + key);
        }
    }

    /** A list body: a bare array, or {@code {"items": [...]}} when {@code wrapped}. */
    static Response paged(List<Map<String, Object>> items, Integer limit, String page, boolean wrapped) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(wrapped ? Map.of("items", result.items()) : result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
