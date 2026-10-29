package io.floci.oci.services.oke.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage entity representing an OCI Container Engine for Kubernetes (OKE) Cluster.
 */
@RegisterForReflection
public class StoredOkeCluster {

    private String id;
    private String name;
    private String compartmentId;
    private String vcnId;
    private String kubernetesVersion;
    private String kmsKeyId;
    private String lifecycleState;
    private String lifecycleDetails;
    private Map<String, String> endpoints;
    private int hostPort;
    private String apiToken;
    private String tenancyId;
    private String region;
    private String caCertificate;
    private String createWorkRequestId;
    private ClusterMetadata metadata;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String type;
    private Map<String, Object> endpointConfig;
    private Map<String, Object> options;
    private Map<String, Object> imagePolicyConfig;
    private List<Map<String, Object>> clusterPodNetworkOptions;
    private String etag;

    /**
     * The OCI wire shape: excludes internal properties like {@code hostPort}, {@code apiToken},
     * {@code tenancyId}, {@code region}, {@code caCertificate}, {@code createWorkRequestId} and
     * {@code etag} (a response header). The optional settings read back exactly as they were
     * sent; {@code availableKubernetesUpgrades} and {@code systemTags} are always present.
     */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("name", name);
        wire.put("compartmentId", compartmentId);
        wire.put("vcnId", vcnId);
        wire.put("kubernetesVersion", kubernetesVersion);
        if (kmsKeyId != null) {
            wire.put("kmsKeyId", kmsKeyId);
        }
        wire.put("lifecycleState", lifecycleState);
        if (lifecycleDetails != null) {
            wire.put("lifecycleDetails", lifecycleDetails);
        }
        if (endpointConfig != null) {
            wire.put("endpointConfig", endpointConfig);
        }
        if (endpoints != null) {
            wire.put("endpoints", wireEndpoints());
        }
        if (metadata != null) {
            wire.put("metadata", metadata);
        }
        if (freeformTags != null) {
            wire.put("freeformTags", freeformTags);
        }
        if (definedTags != null) {
            wire.put("definedTags", definedTags);
        }
        wire.put("systemTags", Map.of());
        if (options != null) {
            wire.put("options", options);
        }
        if (imagePolicyConfig != null) {
            wire.put("imagePolicyConfig", imagePolicyConfig);
        }
        if (clusterPodNetworkOptions != null) {
            wire.put("clusterPodNetworkOptions", clusterPodNetworkOptions);
        }
        if (type != null) {
            wire.put("type", type);
        }
        wire.put("availableKubernetesUpgrades", List.of());
        return wire;
    }

    /**
     * The endpoints, plus {@code publicEndpoint} (host:port, like {@code privateEndpoint}) when the
     * endpoint config asks for a public IP: the one reachable address is the Kubernetes API's.
     */
    private Map<String, String> wireEndpoints() {
        boolean publicIp = endpointConfig != null && Boolean.TRUE.equals(endpointConfig.get("isPublicIpEnabled"));
        String kubernetes = endpoints.get("kubernetes");
        if (!publicIp || kubernetes == null) {
            return endpoints;
        }
        Map<String, String> wire = new LinkedHashMap<>(endpoints);
        wire.put("publicEndpoint", kubernetes.replaceFirst("^https://", ""));
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getVcnId() {
        return vcnId;
    }

    public void setVcnId(String vcnId) {
        this.vcnId = vcnId;
    }

    public String getKubernetesVersion() {
        return kubernetesVersion;
    }

    public void setKubernetesVersion(String kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getLifecycleDetails() {
        return lifecycleDetails;
    }

    public void setLifecycleDetails(String lifecycleDetails) {
        this.lifecycleDetails = lifecycleDetails;
    }

    public Map<String, String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, String> endpoints) {
        this.endpoints = endpoints;
    }

    public int getHostPort() {
        return hostPort;
    }

    public void setHostPort(int hostPort) {
        this.hostPort = hostPort;
    }

    /**
     * Bearer token the k3s sidecar accepts and {@code CreateKubeconfig} hands out. Internal: never
     * part of {@link #toWire()}.
     */
    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    /** Owning tenancy, so async workers outside a request can address the cluster's partition. */
    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    /** Region name (e.g. {@code us-ashburn-1}) the cluster was created in. */
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /** Base64 PEM of the k3s server CA, captured once the API answers. */
    public String getCaCertificate() {
        return caCertificate;
    }

    public void setCaCertificate(String caCertificate) {
        this.caCertificate = caCertificate;
    }

    /** The CLUSTER_CREATE work request, finished when the cluster becomes ACTIVE or FAILED. */
    public String getCreateWorkRequestId() {
        return createWorkRequestId;
    }

    public void setCreateWorkRequestId(String createWorkRequestId) {
        this.createWorkRequestId = createWorkRequestId;
    }

    public ClusterMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ClusterMetadata metadata) {
        this.metadata = metadata;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getEndpointConfig() {
        return endpointConfig;
    }

    public void setEndpointConfig(Map<String, Object> endpointConfig) {
        this.endpointConfig = endpointConfig;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public Map<String, Object> getImagePolicyConfig() {
        return imagePolicyConfig;
    }

    public void setImagePolicyConfig(Map<String, Object> imagePolicyConfig) {
        this.imagePolicyConfig = imagePolicyConfig;
    }

    public List<Map<String, Object>> getClusterPodNetworkOptions() {
        return clusterPodNetworkOptions;
    }

    public void setClusterPodNetworkOptions(List<Map<String, Object>> clusterPodNetworkOptions) {
        this.clusterPodNetworkOptions = clusterPodNetworkOptions;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    @RegisterForReflection
    public static class ClusterMetadata {
        private Instant timeCreated;

        public ClusterMetadata() {}

        public ClusterMetadata(Instant timeCreated) {
            this.timeCreated = timeCreated;
        }

        public Instant getTimeCreated() {
            return timeCreated;
        }

        public void setTimeCreated(Instant timeCreated) {
            this.timeCreated = timeCreated;
        }
    }
}
