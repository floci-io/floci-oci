package io.floci.oci.services.oke;

import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.NetworkUsage;
import io.floci.oci.core.common.OciException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node placement a node pool carries, kept as the client sent it: {@code nodeConfigDetails}
 * is reduced to the fields of its oci-go-sdk {@code containerengine} type, so GetNodePool reads
 * back exactly what was set. Also lists and checks the subnets and network security groups it
 * names.
 */
final class OkeNodePoolSettings {

    static final String SUBNET_IDS = "subnetIds";
    static final String PLACEMENT_SUBNET = "nodeConfigDetails.placementConfigs.subnetId";
    static final String NSG_IDS = "nodeConfigDetails.nsgIds";
    static final String POD_SUBNET_IDS = "nodeConfigDetails.nodePoolPodNetworkOptionDetails.podSubnetIds";
    static final String POD_NSG_IDS = "nodeConfigDetails.nodePoolPodNetworkOptionDetails.podNsgIds";

    /** CreateNodePoolNodeConfigDetails, which NodePoolNodeConfigDetails also reads back. */
    private static final Set<String> CREATE_NODE_CONFIG = Set.of("size", "placementConfigs", "nsgIds", "kmsKeyId",
            "isPvEncryptionInTransitEnabled", "computeClusterId", "freeformTags", "definedTags",
            "nodePoolPodNetworkOptionDetails");
    /** UpdateNodePoolNodeConfigDetails: no computeClusterId. */
    private static final Set<String> UPDATE_NODE_CONFIG = Set.of("size", "placementConfigs", "nsgIds", "kmsKeyId",
            "isPvEncryptionInTransitEnabled", "freeformTags", "definedTags", "nodePoolPodNetworkOptionDetails");
    /** NodePoolPlacementConfigDetails. */
    private static final Set<String> PLACEMENT_CONFIG = Set.of("availabilityDomain", "subnetId",
            "capacityReservationId", "hostGroupId", "preemptibleNodeConfig", "faultDomains");
    /** OciVcnIpNativeNodePoolPodNetworkOptionDetails; FLANNEL_OVERLAY carries only its cniType. */
    private static final Set<String> VCN_IP_NATIVE_POD_NETWORK = Set.of("cniType", "maxPodsPerNode", "podNsgIds",
            "podSubnetIds");

    private OkeNodePoolSettings() {
    }

    /** The {@code subnetIds} of a request, or null when it has none. */
    static List<String> subnetIds(Object subnetIds) {
        return subnetIds == null ? null : NetworkReferences.ids(subnetIds, SUBNET_IDS);
    }

    /** CreateNodePoolNodeConfigDetails: {@code size} and {@code placementConfigs} are mandatory. */
    static Map<String, Object> createNodeConfig(Map<String, Object> nodeConfig) {
        if (nodeConfig == null) {
            return null;
        }
        if (nodeConfig.get("size") == null) {
            throw OciException.missingParameter("Missing required parameter: nodeConfigDetails.size");
        }
        if (nodeConfig.get("placementConfigs") == null) {
            throw OciException.missingParameter("Missing required parameter: nodeConfigDetails.placementConfigs");
        }
        return normalize(nodeConfig, CREATE_NODE_CONFIG);
    }

    /** The node config after UpdateNodePool: the fields it carries replaced, the others kept. */
    static Map<String, Object> updatedNodeConfig(Map<String, Object> current, Map<String, Object> update) {
        if (update == null) {
            return current;
        }
        Map<String, Object> merged = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        merged.putAll(normalize(update, UPDATE_NODE_CONFIG));
        return merged;
    }

    /** Every subnet and NSG OCID the node pool names, malformed values skipped. */
    static Set<String> networkIds(List<String> subnetIds, Map<String, Object> nodeConfig) {
        Set<String> ids = new LinkedHashSet<>(NetworkUsage.ids(subnetIds));
        ids.addAll(placementSubnets(nodeConfig));
        ids.addAll(NetworkUsage.ids(NetworkUsage.field(nodeConfig, "nsgIds")));
        Map<String, Object> podNetwork = podNetwork(nodeConfig);
        ids.addAll(NetworkUsage.ids(NetworkUsage.field(podNetwork, "podSubnetIds"),
                NetworkUsage.field(podNetwork, "podNsgIds")));
        return ids;
    }

    /**
     * Checks the subnets and NSGs {@link #networkIds} lists: each must exist, and each subnet
     * must be in the cluster's VCN.
     */
    static void requireNetwork(NetworkReferences references, String vcnId, List<String> subnetIds,
                               Map<String, Object> nodeConfig) {
        if (subnetIds != null) {
            references.requireSubnetsInVcn(subnetIds, vcnId, SUBNET_IDS);
        }
        if (nodeConfig == null) {
            return;
        }
        references.requireSubnetsInVcn(new ArrayList<>(placementSubnets(nodeConfig)), vcnId, PLACEMENT_SUBNET);
        references.requireNsgs(NetworkReferences.ids(nodeConfig.get("nsgIds"), NSG_IDS));
        Map<String, Object> podNetwork = podNetwork(nodeConfig);
        if (podNetwork != null) {
            references.requireSubnetsInVcn(NetworkReferences.ids(podNetwork.get("podSubnetIds"), POD_SUBNET_IDS),
                    vcnId, POD_SUBNET_IDS);
            references.requireNsgs(NetworkReferences.ids(podNetwork.get("podNsgIds"), POD_NSG_IDS));
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> nodeConfig, Set<String> keys) {
        Object size = nodeConfig.get("size");
        if (size != null && !(size instanceof Integer)) {
            throw OciException.invalidParameter("nodeConfigDetails.size must be an integer");
        }
        Object isPvEncryption = nodeConfig.get("isPvEncryptionInTransitEnabled");
        if (isPvEncryption != null && !(isPvEncryption instanceof Boolean)) {
            throw OciException.invalidParameter("nodeConfigDetails.isPvEncryptionInTransitEnabled must be a boolean");
        }
        NetworkReferences.ids(nodeConfig.get("nsgIds"), NSG_IDS);
        Map<String, Object> kept = only(nodeConfig, keys);
        if (nodeConfig.get("placementConfigs") != null) {
            kept.put("placementConfigs", placementConfigs(nodeConfig.get("placementConfigs")));
        }
        if (nodeConfig.get("nodePoolPodNetworkOptionDetails") != null) {
            kept.put("nodePoolPodNetworkOptionDetails",
                    podNetworkOption(nodeConfig.get("nodePoolPodNetworkOptionDetails")));
        }
        return kept;
    }

    private static List<Map<String, Object>> placementConfigs(Object value) {
        if (!(value instanceof List<?> items)) {
            throw OciException.invalidParameter("nodeConfigDetails.placementConfigs must be a list");
        }
        List<Map<String, Object>> placements = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> placement = object(item, "nodeConfigDetails.placementConfigs");
            if (placement.get("availabilityDomain") == null) {
                throw OciException.missingParameter(
                        "Missing required parameter: nodeConfigDetails.placementConfigs.availabilityDomain");
            }
            if (placement.get("subnetId") == null) {
                throw OciException.missingParameter("Missing required parameter: " + PLACEMENT_SUBNET);
            }
            if (!(placement.get("subnetId") instanceof String)) {
                throw OciException.invalidParameter(PLACEMENT_SUBNET + " must be an OCID");
            }
            placements.add(only(placement, PLACEMENT_CONFIG));
        }
        return placements;
    }

    private static Map<String, Object> podNetworkOption(Object value) {
        Map<String, Object> option = object(value, "nodeConfigDetails.nodePoolPodNetworkOptionDetails");
        Object cniType = option.get("cniType");
        if (cniType == null) {
            throw OciException.missingParameter(
                    "Missing required parameter: nodeConfigDetails.nodePoolPodNetworkOptionDetails.cniType");
        }
        if (!OkeClusterSettings.CNI_TYPES.contains(cniType.toString())) {
            throw OciException.invalidParameter(
                    "Invalid nodeConfigDetails.nodePoolPodNetworkOptionDetails.cniType: " + cniType);
        }
        if (!"OCI_VCN_IP_NATIVE".equals(cniType)) {
            return Map.of("cniType", cniType.toString());
        }
        NetworkReferences.ids(option.get("podSubnetIds"), POD_SUBNET_IDS);
        NetworkReferences.ids(option.get("podNsgIds"), POD_NSG_IDS);
        return only(option, VCN_IP_NATIVE_POD_NETWORK);
    }

    private static Set<String> placementSubnets(Map<String, Object> nodeConfig) {
        Set<String> subnets = new LinkedHashSet<>();
        if (NetworkUsage.field(nodeConfig, "placementConfigs") instanceof List<?> placements) {
            for (Object placement : placements) {
                if (placement instanceof Map<?, ?> map && map.get("subnetId") instanceof String subnetId) {
                    subnets.add(subnetId);
                }
            }
        }
        return subnets;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podNetwork(Map<String, Object> nodeConfig) {
        return NetworkUsage.field(nodeConfig, "nodePoolPodNetworkOptionDetails") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw OciException.invalidParameter(field + " must be an object");
        }
        return (Map<String, Object>) map;
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
