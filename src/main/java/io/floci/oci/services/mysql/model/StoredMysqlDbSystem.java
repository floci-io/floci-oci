package io.floci.oci.services.mysql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A MySQL HeatWave DB system. The fields after {@code etag} are emulator internals (the admin
 * user, the owning partition and the sidecar) and never reach the wire: controllers serialize
 * through {@link #toWire()} and {@link #toSummaryWire()}. The admin password is not stored.
 */
@RegisterForReflection
public class StoredMysqlDbSystem {

    private String id;
    private String displayName;
    private String description;
    private String compartmentId;
    private String subnetId;
    private List<String> nsgIds;
    private Boolean highlyAvailable;
    private String availabilityDomain;
    private String faultDomain;
    private String shapeName;
    private String mysqlVersion;
    private String configurationId;
    private Map<String, Object> backupPolicy;
    private Integer dataStorageSizeInGBs;
    private Map<String, Object> dataStorage;
    private String hostnameLabel;
    private String ipAddress;
    private Integer port;
    private Integer portX;
    private String lifecycleState;
    private String lifecycleDetails;
    private Map<String, Object> maintenance;
    private Map<String, Object> deletionPolicy;
    private String crashRecovery;
    private String databaseManagement;
    private String databaseMode;
    private String accessMode;
    private Map<String, Object> secureConnections;
    private String timeCreated;
    private String timeUpdated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    private String adminUsername;
    private String tenancyId;
    private String region;
    private int hostPort;
    private String containerName;
    private String volumeName;
    private String workRequestId;
    private String endpointHost;
    private int endpointPort;

    /** The DbSystem body: every mandatory field, and each optional one that has a value. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("displayName", displayName);
        putIfPresent(wire, "description", description);
        wire.put("compartmentId", compartmentId);
        wire.put("subnetId", subnetId);
        wire.put("nsgIds", nsgIds != null ? nsgIds : List.of());
        wire.put("isHighlyAvailable", Boolean.TRUE.equals(highlyAvailable));
        wire.put("currentPlacement", placement());
        wire.put("isHeatWaveClusterAttached", false);
        putIfPresent(wire, "availabilityDomain", availabilityDomain);
        wire.put("faultDomain", faultDomain);
        wire.put("shapeName", shapeName);
        wire.put("mysqlVersion", mysqlVersion);
        wire.put("backupPolicy", backupPolicy);
        // Only a fresh DB system is emulated; Terraform reads a missing source as NONE as well.
        wire.put("source", Map.of("sourceType", "NONE"));
        wire.put("configurationId", configurationId);
        wire.put("dataStorageSizeInGBs", dataStorageSizeInGBs);
        wire.put("dataStorage", dataStorage);
        putIfPresent(wire, "hostnameLabel", hostnameLabel);
        putIfPresent(wire, "ipAddress", ipAddress);
        wire.put("port", port);
        wire.put("portX", portX);
        wire.put("endpoints", endpoints());
        wire.put("channels", List.of());
        wire.put("lifecycleState", lifecycleState);
        putIfPresent(wire, "lifecycleDetails", lifecycleDetails);
        wire.put("maintenance", maintenance);
        wire.put("deletionPolicy", deletionPolicy);
        wire.put("crashRecovery", crashRecovery);
        wire.put("databaseManagement", databaseManagement);
        wire.put("databaseMode", databaseMode);
        wire.put("accessMode", accessMode);
        wire.put("secureConnections", secureConnections);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
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
        putIfPresent(wire, "description", description);
        wire.put("compartmentId", compartmentId);
        wire.put("isHighlyAvailable", Boolean.TRUE.equals(highlyAvailable));
        wire.put("currentPlacement", placement());
        wire.put("isHeatWaveClusterAttached", false);
        putIfPresent(wire, "availabilityDomain", availabilityDomain);
        wire.put("faultDomain", faultDomain);
        wire.put("endpoints", endpoints());
        wire.put("lifecycleState", lifecycleState);
        wire.put("mysqlVersion", mysqlVersion);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        wire.put("deletionPolicy", deletionPolicy);
        wire.put("backupPolicy", backupPolicy);
        wire.put("shapeName", shapeName);
        wire.put("crashRecovery", crashRecovery);
        wire.put("databaseManagement", databaseManagement);
        wire.put("databaseMode", databaseMode);
        wire.put("accessMode", accessMode);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }

    private Map<String, Object> placement() {
        Map<String, Object> placement = new LinkedHashMap<>();
        putIfPresent(placement, "availabilityDomain", availabilityDomain);
        placement.put("faultDomain", faultDomain);
        return placement;
    }

    /** The one read-write endpoint, once the server has an address; none before it is up. */
    private List<Map<String, Object>> endpoints() {
        if (endpointHost == null) {
            return List.of();
        }
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("hostname", endpointHost);
        endpoint.put("ipAddress", endpointHost);
        endpoint.put("port", endpointPort);
        endpoint.put("portX", portX);
        endpoint.put("modes", List.of("READ", "WRITE"));
        endpoint.put("status", "ACTIVE".equals(lifecycleState) ? "ACTIVE" : "INACTIVE");
        endpoint.put("resourceType", "DBSYSTEM");
        endpoint.put("resourceId", id);
        return List.of(endpoint);
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

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public List<String> getNsgIds() {
        return nsgIds;
    }

    public void setNsgIds(List<String> nsgIds) {
        this.nsgIds = nsgIds;
    }

    public Boolean getHighlyAvailable() {
        return highlyAvailable;
    }

    public void setHighlyAvailable(Boolean highlyAvailable) {
        this.highlyAvailable = highlyAvailable;
    }

    public String getAvailabilityDomain() {
        return availabilityDomain;
    }

    public void setAvailabilityDomain(String availabilityDomain) {
        this.availabilityDomain = availabilityDomain;
    }

    public String getFaultDomain() {
        return faultDomain;
    }

    public void setFaultDomain(String faultDomain) {
        this.faultDomain = faultDomain;
    }

    public String getShapeName() {
        return shapeName;
    }

    public void setShapeName(String shapeName) {
        this.shapeName = shapeName;
    }

    public String getMysqlVersion() {
        return mysqlVersion;
    }

    public void setMysqlVersion(String mysqlVersion) {
        this.mysqlVersion = mysqlVersion;
    }

    public String getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(String configurationId) {
        this.configurationId = configurationId;
    }

    public Map<String, Object> getBackupPolicy() {
        return backupPolicy;
    }

    public void setBackupPolicy(Map<String, Object> backupPolicy) {
        this.backupPolicy = backupPolicy;
    }

    public Integer getDataStorageSizeInGBs() {
        return dataStorageSizeInGBs;
    }

    public void setDataStorageSizeInGBs(Integer dataStorageSizeInGBs) {
        this.dataStorageSizeInGBs = dataStorageSizeInGBs;
    }

    public Map<String, Object> getDataStorage() {
        return dataStorage;
    }

    public void setDataStorage(Map<String, Object> dataStorage) {
        this.dataStorage = dataStorage;
    }

    public String getHostnameLabel() {
        return hostnameLabel;
    }

    public void setHostnameLabel(String hostnameLabel) {
        this.hostnameLabel = hostnameLabel;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getPortX() {
        return portX;
    }

    public void setPortX(Integer portX) {
        this.portX = portX;
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

    public Map<String, Object> getMaintenance() {
        return maintenance;
    }

    public void setMaintenance(Map<String, Object> maintenance) {
        this.maintenance = maintenance;
    }

    public Map<String, Object> getDeletionPolicy() {
        return deletionPolicy;
    }

    public void setDeletionPolicy(Map<String, Object> deletionPolicy) {
        this.deletionPolicy = deletionPolicy;
    }

    public String getCrashRecovery() {
        return crashRecovery;
    }

    public void setCrashRecovery(String crashRecovery) {
        this.crashRecovery = crashRecovery;
    }

    public String getDatabaseManagement() {
        return databaseManagement;
    }

    public void setDatabaseManagement(String databaseManagement) {
        this.databaseManagement = databaseManagement;
    }

    public String getDatabaseMode() {
        return databaseMode;
    }

    public void setDatabaseMode(String databaseMode) {
        this.databaseMode = databaseMode;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public Map<String, Object> getSecureConnections() {
        return secureConnections;
    }

    public void setSecureConnections(Map<String, Object> secureConnections) {
        this.secureConnections = secureConnections;
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

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
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
