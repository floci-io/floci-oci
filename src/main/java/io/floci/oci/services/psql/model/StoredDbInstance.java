package io.floci.oci.services.psql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A database instance node of a DB system. Every node is served by the system's one container. */
@RegisterForReflection
public class StoredDbInstance {

    private String id;
    private String displayName;
    private String description;
    private String availabilityDomain;
    private String lifecycleState;
    private String lifecycleDetails;
    private String timeCreated;
    private String timeUpdated;

    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        putIfPresent(wire, "displayName", displayName);
        putIfPresent(wire, "description", description);
        wire.put("availabilityDomain", availabilityDomain);
        wire.put("lifecycleState", lifecycleState);
        putIfPresent(wire, "lifecycleDetails", lifecycleDetails);
        wire.put("timeCreated", timeCreated);
        putIfPresent(wire, "timeUpdated", timeUpdated);
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

    public String getAvailabilityDomain() {
        return availabilityDomain;
    }

    public void setAvailabilityDomain(String availabilityDomain) {
        this.availabilityDomain = availabilityDomain;
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
}
