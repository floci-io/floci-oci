package io.floci.oci.services.containerinstances;

import io.floci.oci.core.common.OciException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the polymorphic parts of CreateContainerInstanceDetails
 * (oci-go-sdk/containerinstances/create_container_instance_details.go) into their read shapes:
 * volumes by {@code volumeType}, image pull secrets by {@code secretType}, health checks by
 * {@code healthCheckType}, security contexts by {@code securityContextType}. Everything a client
 * sends is checked before anything is stored.
 */
final class ContainerInstancesRequests {

    static final Set<String> RESTART_POLICIES = Set.of("ALWAYS", "NEVER", "ON_FAILURE");
    private static final Set<String> BACKING_STORES = Set.of("EPHEMERAL_STORAGE", "MEMORY");
    private static final Set<String> FAILURE_ACTIONS = Set.of("KILL", "NONE");

    /** A container to create, its read-shape attributes already built. */
    record ContainerRequest(String displayName, String imageUrl, Map<String, Object> attributes,
                            Map<String, String> freeformTags, Map<String, Map<String, Object>> definedTags) {
    }

    private ContainerInstancesRequests() {
    }

    static String string(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw OciException.invalidParameter(key + " must be a string: " + value);
        }
        return text;
    }

    static String requireString(Map<String, Object> body, String key, String path) {
        String value = string(body, key);
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + path);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw OciException.invalidParameter(key + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw OciException.invalidParameter(key + " must be a list");
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) {
                throw OciException.invalidParameter(key + " must be a list of objects");
            }
            objects.add((Map<String, Object>) item);
        }
        return objects;
    }

    static List<String> strings(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> items)) {
            throw OciException.invalidParameter(key + " must be a list of strings");
        }
        List<String> strings = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text)) {
                throw OciException.invalidParameter(key + " must be a list of strings");
            }
            strings.add(text);
        }
        return strings;
    }

    static Map<String, String> stringMap(Map<String, Object> body, String key) {
        Map<String, Object> value = map(body, key);
        if (value == null) {
            return null;
        }
        Map<String, String> strings = new LinkedHashMap<>();
        value.forEach((k, v) -> {
            if (!(v instanceof String text)) {
                throw OciException.invalidParameter(key + "." + k + " must be a string");
            }
            strings.put(k, text);
        });
        return strings;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        Map<String, Object> value = map(body, "definedTags");
        if (value == null) {
            return null;
        }
        Map<String, Map<String, Object>> tags = new LinkedHashMap<>();
        value.forEach((namespace, keys) -> {
            if (!(keys instanceof Map<?, ?>)) {
                throw OciException.invalidParameter("definedTags." + namespace + " must be an object");
            }
            tags.put(namespace, new LinkedHashMap<>((Map<String, Object>) keys));
        });
        return tags;
    }

    static void requireEnum(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + field + ": " + value);
        }
    }

    /** CreateContainerVolumeDetails, read as ContainerVolume: EMPTYDIR and CONFIGFILE. */
    static List<Map<String, Object>> volumes(List<Map<String, Object>> requested) {
        List<Map<String, Object>> volumes = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> volume : requested) {
            String name = requireString(volume, "name", "volumes.name");
            if (!names.add(name)) {
                throw OciException.invalidParameter("Duplicate volume name: " + name);
            }
            String type = requireString(volume, "volumeType", "volumes.volumeType");
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("volumeType", type);
            read.put("name", name);
            switch (type) {
                case "EMPTYDIR" -> {
                    String backingStore = string(volume, "backingStore");
                    requireEnum(backingStore, BACKING_STORES, "volumes.backingStore");
                    read.put("backingStore", backingStore != null ? backingStore : "EPHEMERAL_STORAGE");
                }
                case "CONFIGFILE" -> {
                    List<Map<String, Object>> configs = new ArrayList<>();
                    for (Map<String, Object> config : objects(volume, "configs")) {
                        Map<String, Object> file = new LinkedHashMap<>();
                        String fileName = requireString(config, "fileName", "volumes.configs.fileName");
                        if (fileName.contains("/")) {
                            throw OciException.invalidParameter("volumes.configs.fileName must not contain '/': "
                                    + fileName);
                        }
                        String data = requireString(config, "data", "volumes.configs.data");
                        try {
                            Base64.getDecoder().decode(data);
                        } catch (IllegalArgumentException e) {
                            throw OciException.invalidParameter("volumes.configs.data must be base64: " + fileName);
                        }
                        file.put("fileName", fileName);
                        file.put("data", data);
                        String path = string(config, "path");
                        if (path != null) {
                            file.put("path", path);
                        }
                        configs.add(file);
                    }
                    read.put("configs", configs);
                }
                case "OCI_FSS_FILE_SYSTEM" -> throw OciException.invalidParameter(
                        "File Storage volumes (OCI_FSS_FILE_SYSTEM) are not supported by floci-oci: " + name);
                default -> throw OciException.invalidParameter("Invalid volumes.volumeType: " + type);
            }
            volumes.add(read);
        }
        return volumes;
    }

    /**
     * CreateContainerDetails, read as Container: the command, arguments and environment as sent,
     * volume mounts against the instance's volumes, the resource limits and health checks.
     */
    static ContainerRequest container(Map<String, Object> requested, Set<String> volumeNames) {
        String imageUrl = requireString(requested, "imageUrl", "containers.imageUrl");
        Map<String, Object> attributes = new LinkedHashMap<>();
        List<String> command = strings(requested, "command");
        if (command != null) {
            attributes.put("command", command);
        }
        List<String> arguments = strings(requested, "arguments");
        if (arguments != null) {
            attributes.put("arguments", arguments);
        }
        String workingDirectory = string(requested, "workingDirectory");
        if (workingDirectory != null) {
            attributes.put("workingDirectory", workingDirectory);
        }
        Map<String, String> environment = stringMap(requested, "environmentVariables");
        if (environment != null) {
            attributes.put("environmentVariables", environment);
        }
        List<Map<String, Object>> mounts = new ArrayList<>();
        for (Map<String, Object> mount : objects(requested, "volumeMounts")) {
            String volumeName = requireString(mount, "volumeName", "containers.volumeMounts.volumeName");
            if (!volumeNames.contains(volumeName)) {
                throw OciException.invalidParameter("containers.volumeMounts.volumeName " + volumeName
                        + " is not one of the container instance's volumes");
            }
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("mountPath", requireString(mount, "mountPath", "containers.volumeMounts.mountPath"));
            read.put("volumeName", volumeName);
            copyIfPresent(mount, read, "subPath");
            copyIfPresent(mount, read, "isReadOnly");
            copyIfPresent(mount, read, "partition");
            mounts.add(read);
        }
        if (!mounts.isEmpty()) {
            attributes.put("volumeMounts", mounts);
        }
        Object principalDisabled = requested.get("isResourcePrincipalDisabled");
        if (principalDisabled != null && !(principalDisabled instanceof Boolean)) {
            throw OciException.invalidParameter("isResourcePrincipalDisabled must be a boolean");
        }
        attributes.put("isResourcePrincipalDisabled", Boolean.TRUE.equals(principalDisabled));
        Map<String, Object> resourceConfig = map(requested, "resourceConfig");
        if (resourceConfig != null) {
            Map<String, Object> read = new LinkedHashMap<>();
            for (String key : List.of("vcpusLimit", "memoryLimitInGBs")) {
                Object value = resourceConfig.get(key);
                if (value != null) {
                    if (!(value instanceof Number number) || number.doubleValue() <= 0) {
                        throw OciException.invalidParameter("resourceConfig." + key + " must be a positive number");
                    }
                    read.put(key, number.doubleValue());
                }
            }
            attributes.put("resourceConfig", read);
        }
        List<Map<String, Object>> healthChecks = new ArrayList<>();
        for (Map<String, Object> check : objects(requested, "healthChecks")) {
            healthChecks.add(healthCheck(check));
        }
        if (!healthChecks.isEmpty()) {
            attributes.put("healthChecks", healthChecks);
        }
        Map<String, Object> securityContext = securityContext(map(requested, "securityContext"));
        if (securityContext != null) {
            attributes.put("securityContext", securityContext);
        }
        return new ContainerRequest(string(requested, "displayName"), imageUrl, attributes,
                stringMap(requested, "freeformTags"), definedTags(requested));
    }

    private static Map<String, Object> healthCheck(Map<String, Object> check) {
        String type = requireString(check, "healthCheckType", "containers.healthChecks.healthCheckType");
        Map<String, Object> read = new LinkedHashMap<>(check);
        switch (type) {
            case "HTTP" -> {
                requireString(check, "path", "containers.healthChecks.path");
                requirePort(check);
            }
            case "TCP" -> requirePort(check);
            default -> throw OciException.invalidParameter("Invalid containers.healthChecks.healthCheckType: " + type);
        }
        requireEnum(string(check, "failureAction"), FAILURE_ACTIONS, "containers.healthChecks.failureAction");
        return read;
    }

    private static void requirePort(Map<String, Object> check) {
        if (!(check.get("port") instanceof Number port) || port.intValue() < 1 || port.intValue() > 65535) {
            throw OciException.missingParameter("Missing required parameter: containers.healthChecks.port");
        }
    }

    /** CreateLinuxSecurityContextDetails, the one {@code securityContextType}, echoed as sent. */
    static Map<String, Object> securityContext(Map<String, Object> requested) {
        if (requested == null) {
            return null;
        }
        String type = requireString(requested, "securityContextType", "securityContext.securityContextType");
        if (!"LINUX".equals(type)) {
            throw OciException.invalidParameter("Invalid securityContext.securityContextType: " + type);
        }
        return new LinkedHashMap<>(requested);
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }
}
