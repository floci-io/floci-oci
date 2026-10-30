package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The optional cluster settings CreateCluster and UpdateCluster carry, kept as the client sent
 * them: each object is reduced to the fields of its oci-go-sdk {@code containerengine} type and
 * its enums are checked, so a GET reads back exactly what was set and nothing floci-oci made up.
 */
final class OkeClusterSettings {

    static final Set<String> CLUSTER_TYPES = Set.of("BASIC_CLUSTER", "ENHANCED_CLUSTER");
    static final Set<String> CNI_TYPES = Set.of("OCI_VCN_IP_NATIVE", "FLANNEL_OVERLAY");
    static final Set<String> IP_FAMILIES = Set.of("IPv4", "IPv6");

    /** ClusterCreateOptions. */
    private static final Set<String> CREATE_OPTIONS = Set.of("serviceLbSubnetIds", "ipFamilies",
            "kubernetesNetworkConfig", "addOns", "admissionControllerOptions", "persistentVolumeConfig",
            "serviceLbConfig", "openIdConnectTokenAuthenticationConfig", "openIdConnectDiscovery");
    /** UpdateClusterOptionsDetails: the options an update may change. */
    private static final Set<String> UPDATE_OPTIONS = Set.of("admissionControllerOptions",
            "persistentVolumeConfig", "serviceLbConfig", "openIdConnectTokenAuthenticationConfig",
            "openIdConnectDiscovery");
    /** ClusterEndpointConfig and CreateClusterEndpointConfigDetails. */
    private static final Set<String> ENDPOINT_CONFIG = Set.of("subnetId", "nsgIds", "isPublicIpEnabled",
            "securityAttributes");
    /** UpdateClusterEndpointConfigDetails: no subnetId. */
    private static final Set<String> UPDATE_ENDPOINT_CONFIG = Set.of("nsgIds", "isPublicIpEnabled",
            "securityAttributes");
    /** ImagePolicyConfig, CreateImagePolicyConfigDetails and UpdateImagePolicyConfigDetails. */
    private static final Set<String> IMAGE_POLICY_CONFIG = Set.of("isPolicyEnabled", "keyDetails");

    private OkeClusterSettings() {
    }

    static String type(String type) {
        if (type != null && !CLUSTER_TYPES.contains(type)) {
            throw OciException.invalidParameter("Invalid type: " + type);
        }
        return type;
    }

    static Map<String, Object> createOptions(Map<String, Object> options) {
        if (options == null) {
            return null;
        }
        if (options.get("ipFamilies") instanceof List<?> families) {
            for (Object family : families) {
                if (!IP_FAMILIES.contains(String.valueOf(family))) {
                    throw OciException.invalidParameter("Invalid options.ipFamilies: " + family);
                }
            }
        } else if (options.get("ipFamilies") != null) {
            throw OciException.invalidParameter("options.ipFamilies must be a list");
        }
        return only(options, CREATE_OPTIONS);
    }

    /** The options after an update: the updatable ones replaced, the create-only ones kept. */
    static Map<String, Object> updatedOptions(Map<String, Object> current, Map<String, Object> update) {
        if (update == null) {
            return current;
        }
        Map<String, Object> merged = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        merged.putAll(only(update, UPDATE_OPTIONS));
        return merged;
    }

    static Map<String, Object> endpointConfig(Map<String, Object> endpointConfig) {
        if (endpointConfig == null) {
            return null;
        }
        requireBoolean(endpointConfig, "isPublicIpEnabled", "endpointConfig");
        return only(endpointConfig, ENDPOINT_CONFIG);
    }

    /** The endpoint config after UpdateClusterEndpointConfig: its subnet stays as it was. */
    static Map<String, Object> updatedEndpointConfig(Map<String, Object> current, Map<String, Object> update) {
        requireBoolean(update, "isPublicIpEnabled", "endpointConfig");
        Map<String, Object> merged = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        merged.putAll(only(update, UPDATE_ENDPOINT_CONFIG));
        return merged;
    }

    static Map<String, Object> imagePolicyConfig(Map<String, Object> imagePolicyConfig) {
        if (imagePolicyConfig == null) {
            return null;
        }
        requireBoolean(imagePolicyConfig, "isPolicyEnabled", "imagePolicyConfig");
        return only(imagePolicyConfig, IMAGE_POLICY_CONFIG);
    }

    static List<Map<String, Object>> podNetworkOptions(List<Map<String, Object>> options) {
        if (options == null) {
            return null;
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> option : options) {
            Object cniType = option != null ? option.get("cniType") : null;
            if (cniType == null) {
                throw OciException.missingParameter("Missing required parameter: clusterPodNetworkOptions.cniType");
            }
            if (!CNI_TYPES.contains(cniType.toString())) {
                throw OciException.invalidParameter("Invalid clusterPodNetworkOptions.cniType: " + cniType);
            }
            normalized.add(Map.of("cniType", cniType.toString()));
        }
        return normalized;
    }

    private static void requireBoolean(Map<String, Object> object, String key, String owner) {
        Object value = object.get(key);
        if (value != null && !(value instanceof Boolean)) {
            throw OciException.invalidParameter(owner + "." + key + " must be a boolean");
        }
    }

    private static Map<String, Object> only(Map<String, Object> object, Set<String> keys) {
        Map<String, Object> kept = new LinkedHashMap<>();
        object.forEach((key, value) -> {
            if (keys.contains(key) && value != null) {
                kept.put(key, value);
            }
        });
        return kept;
    }
}
