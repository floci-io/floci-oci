package io.floci.oci.services.streaming.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A stream pool: the compartment-scoped container of streams and their Kafka settings. A pool
 * marked {@code defaultPool} is the one CreateStream makes on demand for a compartment when the
 * request names no pool.
 */
@RegisterForReflection
public class StoredStreamPool {

    private String id;
    private String name;
    private String compartmentId;
    private String tenancyId;
    private String region;
    private String lifecycleState;
    private String lifecycleStateDetails;
    private String timeCreated;
    private boolean defaultPool;
    private Boolean autoCreateTopicsEnable;
    private Integer logRetentionHours;
    private Integer numPartitions;
    private String bootstrapServers;
    private String kmsKeyId;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    public StoredStreamPool() {
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

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getLifecycleStateDetails() {
        return lifecycleStateDetails;
    }

    public void setLifecycleStateDetails(String lifecycleStateDetails) {
        this.lifecycleStateDetails = lifecycleStateDetails;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public boolean isDefaultPool() {
        return defaultPool;
    }

    public void setDefaultPool(boolean defaultPool) {
        this.defaultPool = defaultPool;
    }

    public Boolean getAutoCreateTopicsEnable() {
        return autoCreateTopicsEnable;
    }

    public void setAutoCreateTopicsEnable(Boolean autoCreateTopicsEnable) {
        this.autoCreateTopicsEnable = autoCreateTopicsEnable;
    }

    public Integer getLogRetentionHours() {
        return logRetentionHours;
    }

    public void setLogRetentionHours(Integer logRetentionHours) {
        this.logRetentionHours = logRetentionHours;
    }

    public Integer getNumPartitions() {
        return numPartitions;
    }

    public void setNumPartitions(Integer numPartitions) {
        this.numPartitions = numPartitions;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
