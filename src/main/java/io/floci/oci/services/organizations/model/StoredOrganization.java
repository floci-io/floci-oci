package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An organization, stored in its parent tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredOrganization {

    private String id;
    private String compartmentId;
    private String defaultUcmSubscriptionId;
    private String lifecycleState;
    private String timeCreated;
    private String displayName;
    private String parentName;
    private String timeUpdated;
    private String etag;

    /** The OCI {@code Organization} wire shape. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("defaultUcmSubscriptionId", defaultUcmSubscriptionId);
        wire.put("lifecycleState", lifecycleState);
        wire.put("timeCreated", timeCreated);
        wire.put("displayName", displayName);
        wire.put("parentName", parentName);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
        }
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

    public String getDefaultUcmSubscriptionId() {
        return defaultUcmSubscriptionId;
    }

    public void setDefaultUcmSubscriptionId(String defaultUcmSubscriptionId) {
        this.defaultUcmSubscriptionId = defaultUcmSubscriptionId;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getParentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
