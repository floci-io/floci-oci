package io.floci.oci.services.oke.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
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
    private ClusterMetadata metadata;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;

    /**
     * The OCI wire shape: excludes internal properties like {@code hostPort}.
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
        if (endpoints != null) {
            wire.put("endpoints", endpoints);
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
