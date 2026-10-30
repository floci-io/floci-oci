package io.floci.oci.services.functions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The function runtimes {@code /20260325} lists, for clients that discover them. Only the names
 * are grounded (the OCI CLI's own tests use {@code java17.ol9} and {@code python312.ol9}); the
 * other values are floci-oci's. floci-oci runs container images only, so no function uses them.
 */
final class FunctionsCatalog {

    private static final String TIME_CREATED = "2025-01-01T00:00:00Z";
    private static final String TIME_DEPRECATED = "2030-12-31T00:00:00Z";
    private static final String TIME_DECOMMISSIONED = "2031-06-30T00:00:00Z";

    private record Runtime(String name, String language, String languageVersion) {
    }

    private static final List<Runtime> RUNTIMES = List.of(
            new Runtime("java17.ol9", "java", "17"),
            new Runtime("python312.ol9", "python", "3.12"));

    private FunctionsCatalog() {
    }

    static List<Map<String, Object>> runtimes(String realm, String regionCode) {
        return RUNTIMES.stream().map(runtime -> runtime(runtime, realm, regionCode)).toList();
    }

    static List<Map<String, Object>> runtimeVersions(String realm, String regionCode) {
        return RUNTIMES.stream().map(runtime -> version(runtime, realm, regionCode)).toList();
    }

    private static Map<String, Object> runtime(Runtime runtime, String realm, String regionCode) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", runtimeId(runtime, realm, regionCode));
        wire.put("name", runtime.name());
        wire.put("os", "OL9");
        wire.put("language", runtime.language());
        wire.put("timeDeprecated", TIME_DEPRECATED);
        wire.put("timeDecommissioned", TIME_DECOMMISSIONED);
        wire.put("lifecycleState", "ACTIVE");
        wire.put("currentFunctionsRuntimeVersionId", versionId(runtime, realm, regionCode));
        wire.put("timeCreated", TIME_CREATED);
        wire.put("timeUpdated", TIME_CREATED);
        wire.put("freeformTags", Map.of());
        wire.put("definedTags", Map.of());
        return wire;
    }

    private static Map<String, Object> version(Runtime runtime, String realm, String regionCode) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", versionId(runtime, realm, regionCode));
        wire.put("functionsRuntimeId", runtimeId(runtime, realm, regionCode));
        wire.put("displayName", runtime.name() + ".1");
        wire.put("osVersion", "9");
        wire.put("languageVersion", runtime.languageVersion());
        wire.put("supportedArchitectures", List.of("X86", "ARM"));
        wire.put("metadata", "{}");
        wire.put("lifecycleState", "ACTIVE");
        wire.put("timeCreated", TIME_CREATED);
        wire.put("timeUpdated", TIME_CREATED);
        wire.put("freeformTags", Map.of());
        wire.put("definedTags", Map.of());
        return wire;
    }

    private static String runtimeId(Runtime runtime, String realm, String regionCode) {
        return "ocid1.fnruntime." + realm + "." + regionCode + "." + slug(runtime);
    }

    private static String versionId(Runtime runtime, String realm, String regionCode) {
        return "ocid1.fnruntimeversion." + realm + "." + regionCode + "." + slug(runtime) + "1";
    }

    private static String slug(Runtime runtime) {
        return runtime.name().replace(".", "").toLowerCase(Locale.ROOT);
    }
}
