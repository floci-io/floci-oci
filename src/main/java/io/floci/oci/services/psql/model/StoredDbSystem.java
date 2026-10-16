package io.floci.oci.services.psql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OCI Database with PostgreSQL DB system. The fields after {@code etag} are emulator
 * internals (the owning partition, the sidecar and its certificate) and never reach the wire:
 * controllers serialize through {@link #toWire()} and {@link #toSummaryWire()}. The master
 * password is not stored at all.
 */
@RegisterForReflection
public class StoredDbSystem {

    private String id;
    private String displayName;
    private String description;
    private String compartmentId;
    private String lifecycleState;
    private String lifecycleDetails;
    private String timeCreated;
    private String timeUpdated;
    private String systemType;
    private String systemRole;
    private String dbVersion;
    private String shape;
    private Integer instanceOcpuCount;
    private Integer instanceMemorySizeInGBs;
    private Integer instanceCount;
    private Map<String, Object> storageDetails;
    private Map<String, Object> networkDetails;
    private Map<String, Object> managementPolicy;
    private Map<String, Object> source;
    private String configId;
    private String adminUsername;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private List<StoredDbInstance> instances = new ArrayList<>();
    private String etag;

    private String tenancyId;
    private String region;
    private int hostPort;
    private String containerName;
    private String volumeName;
    private String workRequestId;
    private String certificatePem;
    private String privateKeyPem;
    private String endpointHost;
    private int endpointPort;

    /** The DbSystem body: every mandatory field, and each optional one that has a value. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("displayName", displayName);
        putIfPresent(wire, "description", description);
        wire.put("compartmentId", compartmentId);
        wire.put("timeCreated", timeCreated);
        putIfPresent(wire, "timeUpdated", timeUpdated);
        wire.put("lifecycleState", lifecycleState);
        putIfPresent(wire, "lifecycleDetails", lifecycleDetails);
        putIfPresent(wire, "adminUsername", adminUsername);
        wire.put("systemType", systemType);
        wire.put("systemRole", systemRole);
        wire.put("dbVersion", dbVersion);
        putIfPresent(wire, "configId", configId);
        wire.put("shape", shape);
        wire.put("instanceOcpuCount", instanceOcpuCount);
        wire.put("instanceMemorySizeInGBs", instanceMemorySizeInGBs);
        wire.put("instanceCount", instanceCount);
        wire.put("instances", instances.stream().map(StoredDbInstance::toWire).toList());
        wire.put("storageDetails", storageDetails);
        wire.put("networkDetails", networkDetails);
        wire.put("managementPolicy", managementPolicy);
        putIfPresent(wire, "source", source);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        // Always present: Terraform treats a missing systemTags as a value still to be computed.
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** The DbSystemSummary body returned by ListDbSystems. */
    public Map<String, Object> toSummaryWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("displayName", displayName);
        wire.put("compartmentId", compartmentId);
        wire.put("timeCreated", timeCreated);
        putIfPresent(wire, "timeUpdated", timeUpdated);
        wire.put("lifecycleState", lifecycleState);
        putIfPresent(wire, "lifecycleDetails", lifecycleDetails);
        wire.put("systemType", systemType);
        wire.put("dbVersion", dbVersion);
        putIfPresent(wire, "configId", configId);
        wire.put("shape", shape);
        wire.put("instanceCount", instanceCount);
        wire.put("instanceOcpuCount", instanceOcpuCount);
        wire.put("instanceMemorySizeInGBs", instanceMemorySizeInGBs);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        // Always present: Terraform treats a missing systemTags as a value still to be computed.
        wire.put("systemTags", Map.of());
        return wire;
    }

    private static void putIfPresent(Map<String, Object> wire, String key, Object value) {
        if (value != null) {
            wire.put(key, value);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
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

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getSystemType() {
        return systemType;
    }

    public void setSystemType(String systemType) {
        this.systemType = systemType;
    }

    public String getSystemRole() {
        return systemRole;
    }

    public void setSystemRole(String systemRole) {
        this.systemRole = systemRole;
    }

    public String getDbVersion() {
        return dbVersion;
    }

    public void setDbVersion(String dbVersion) {
        this.dbVersion = dbVersion;
    }

    public String getShape() {
        return shape;
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    public Integer getInstanceOcpuCount() {
        return instanceOcpuCount;
    }

    public void setInstanceOcpuCount(Integer instanceOcpuCount) {
        this.instanceOcpuCount = instanceOcpuCount;
    }

    public Integer getInstanceMemorySizeInGBs() {
        return instanceMemorySizeInGBs;
    }

    public void setInstanceMemorySizeInGBs(Integer instanceMemorySizeInGBs) {
        this.instanceMemorySizeInGBs = instanceMemorySizeInGBs;
    }

    public Integer getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(Integer instanceCount) {
        this.instanceCount = instanceCount;
    }

    public Map<String, Object> getStorageDetails() {
        return storageDetails;
    }

    public void setStorageDetails(Map<String, Object> storageDetails) {
        this.storageDetails = storageDetails;
    }

    public Map<String, Object> getNetworkDetails() {
        return networkDetails;
    }

    public void setNetworkDetails(Map<String, Object> networkDetails) {
        this.networkDetails = networkDetails;
    }

    public Map<String, Object> getManagementPolicy() {
        return managementPolicy;
    }

    public void setManagementPolicy(Map<String, Object> managementPolicy) {
        this.managementPolicy = managementPolicy;
    }

    public Map<String, Object> getSource() {
        return source;
    }

    public void setSource(Map<String, Object> source) {
        this.source = source;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
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

    public List<StoredDbInstance> getInstances() {
        return instances;
    }

    public void setInstances(List<StoredDbInstance> instances) {
        this.instances = instances;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
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

    public int getHostPort() {
        return hostPort;
    }

    public void setHostPort(int hostPort) {
        this.hostPort = hostPort;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public void setVolumeName(String volumeName) {
        this.volumeName = volumeName;
    }

    public String getWorkRequestId() {
        return workRequestId;
    }

    public void setWorkRequestId(String workRequestId) {
        this.workRequestId = workRequestId;
    }

    public String getCertificatePem() {
        return certificatePem;
    }

    public void setCertificatePem(String certificatePem) {
        this.certificatePem = certificatePem;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getEndpointHost() {
        return endpointHost;
    }

    public void setEndpointHost(String endpointHost) {
        this.endpointHost = endpointHost;
    }

    public int getEndpointPort() {
        return endpointPort;
    }

    public void setEndpointPort(int endpointPort) {
        this.endpointPort = endpointPort;
    }
}
