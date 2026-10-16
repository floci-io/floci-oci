package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A parent-child tenancy link for one feature, stored in the parent tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredLink {

    private String id;
    private String parentTenancyId;
    private String childTenancyId;
    private String timeCreated;
    private String lifecycleState;
    private String timeUpdated;
    private String timeTerminated;
    private String feature;
    private String etag;

    /** The OCI {@code Link} wire shape. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("parentTenancyId", parentTenancyId);
        wire.put("childTenancyId", childTenancyId);
        wire.put("timeCreated", timeCreated);
        wire.put("lifecycleState", lifecycleState);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
        }
        if (timeTerminated != null) {
            wire.put("timeTerminated", timeTerminated);
        }
        wire.put("feature", feature);
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentTenancyId() {
        return parentTenancyId;
    }

    public void setParentTenancyId(String parentTenancyId) {
        this.parentTenancyId = parentTenancyId;
    }

    public String getChildTenancyId() {
        return childTenancyId;
    }

    public void setChildTenancyId(String childTenancyId) {
        this.childTenancyId = childTenancyId;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getTimeTerminated() {
        return timeTerminated;
    }

    public void setTimeTerminated(String timeTerminated) {
        this.timeTerminated = timeTerminated;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
