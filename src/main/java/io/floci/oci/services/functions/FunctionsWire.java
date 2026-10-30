package io.floci.oci.services.functions;

import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application and Function bodies for both API versions. They differ only in how a function
 * names its image: {@code /20181201} has top-level {@code image} and {@code imageDigest};
 * {@code /20260325} removed both for the mandatory {@code sourceDetails}.
 */
final class FunctionsWire {

    private FunctionsWire() {
    }

    /** Application; the summary drops config and syslogUrl. */
    static Map<String, Object> application(StoredApplication app, boolean full) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", app.getId());
        json.put("compartmentId", app.getCompartmentId());
        json.put("displayName", app.getDisplayName());
        json.put("lifecycleState", app.getLifecycleState());
        json.put("subnetIds", app.getSubnetIds());
        json.put("shape", app.getShape());
        json.put("timeCreated", app.getTimeCreated());
        json.put("timeUpdated", app.getTimeUpdated());
        if (full) {
            json.put("config", app.getConfig());
        }
        if (app.getFreeformTags() != null) {
            json.put("freeformTags", app.getFreeformTags());
        }
        if (app.getDefinedTags() != null) {
            json.put("definedTags", app.getDefinedTags());
        }
        if (app.getSettings() != null) {
            app.getSettings().forEach((key, value) -> {
                if (full || !"syslogUrl".equals(key)) {
                    json.put(key, value);
                }
            });
        }
        return json;
    }

    /** Function; the summary drops config. Exact casing: memoryInMBs. */
    static Map<String, Object> function(StoredFunction fn, boolean full, String invokeEndpoint, boolean v2) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", fn.getId());
        json.put("displayName", fn.getDisplayName());
        json.put("applicationId", fn.getApplicationId());
        json.put("compartmentId", fn.getCompartmentId());
        json.put("lifecycleState", fn.getLifecycleState());
        if (v2) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sourceType", "CONTAINER_IMAGE");
            source.put("image", fn.getImage());
            source.put("imageDigest", fn.getImageDigest());
            json.put("sourceDetails", source);
        } else {
            json.put("image", fn.getImage());
            json.put("imageDigest", fn.getImageDigest());
        }
        json.put("shape", fn.getShape());
        json.put("memoryInMBs", fn.getMemoryInMBs());
        json.put("timeoutInSeconds", fn.getTimeoutInSeconds());
        json.put("invokeEndpoint", invokeEndpoint);
        json.put("timeCreated", fn.getTimeCreated());
        json.put("timeUpdated", fn.getTimeUpdated());
        if (full) {
            json.put("config", fn.getConfig());
        }
        if (fn.getFreeformTags() != null) {
            json.put("freeformTags", fn.getFreeformTags());
        }
        if (fn.getDefinedTags() != null) {
            json.put("definedTags", fn.getDefinedTags());
        }
        if (fn.getSettings() != null) {
            json.putAll(fn.getSettings());
        }
        return json;
    }
}
