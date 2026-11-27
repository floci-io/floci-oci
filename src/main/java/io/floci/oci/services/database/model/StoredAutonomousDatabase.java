package io.floci.oci.services.database.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Autonomous Database (oci-go-sdk/database AutonomousDatabase). The identity and lifecycle
 * fields are typed; every other wire field the database carries, sent or defaulted, lives in
 * {@code attributes} under its wire name. {@code tenancyId}, {@code region}, {@code containerName},
 * {@code hostPort} and {@code workRequestId} are emulator internals that never reach the wire.
 * The admin password and the container's wallet password are never stored.
 */
@RegisterForReflection
public class StoredAutonomousDatabase {

    private String id;
    private String compartmentId;
    private String lifecycleState;
    private String lifecycleDetails;
    private String dbName;
    private String displayName;
    private String timeCreated;
    private String etag;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    private String tenancyId;
    private String region;
    private String containerName;
    private int hostPort;
    private String workRequestId;

    /**
     * The AutonomousDatabase body, which AutonomousDatabaseSummary shares field for field: the
     * mandatory fields first, then every attribute that has a value.
     */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("lifecycleState", lifecycleState);
        if (lifecycleDetails != null) {
            wire.put("lifecycleDetails", lifecycleDetails);
        }
        wire.put("dbName", dbName);
        wire.put("displayName", displayName);
        wire.put("timeCreated", timeCreated);
        attributes.forEach((key, value) -> {
            if (value != null) {
                wire.put(key, value);
            }
        });
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new LinkedHashMap<>();
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

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    /** The host port the container's mTLS listener is published on; 0 before the first start. */
    public int getHostPort() {
        return hostPort;
    }

    public void setHostPort(int hostPort) {
        this.hostPort = hostPort;
    }

    /** The work request of the operation under way on the container, or null when none is. */
    public String getWorkRequestId() {
        return workRequestId;
    }

    public void setWorkRequestId(String workRequestId) {
        this.workRequestId = workRequestId;
    }
}
