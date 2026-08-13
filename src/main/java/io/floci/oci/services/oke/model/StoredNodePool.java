package io.floci.oci.services.oke.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * Storage entity representing an OCI Container Engine for Kubernetes (OKE) Node Pool.
 */
@RegisterForReflection
public class StoredNodePool {

    private String id;
    private String name;
    private String compartmentId;
    private String clusterId;
    private String kubernetesVersion;
    private String nodeShape;
    private int quantityPerSubnet;
    private String lifecycleState;
    @JsonIgnore
    private Instant timeCreated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;

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

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getKubernetesVersion() {
        return kubernetesVersion;
    }

    public void setKubernetesVersion(String kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public String getNodeShape() {
        return nodeShape;
    }

    public void setNodeShape(String nodeShape) {
        this.nodeShape = nodeShape;
    }

    public int getQuantityPerSubnet() {
        return quantityPerSubnet;
    }

    public void setQuantityPerSubnet(int quantityPerSubnet) {
        this.quantityPerSubnet = quantityPerSubnet;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public Instant getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(Instant timeCreated) {
        this.timeCreated = timeCreated;
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
}
