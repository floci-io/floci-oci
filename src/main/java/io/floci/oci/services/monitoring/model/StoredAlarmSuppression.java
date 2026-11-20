package io.floci.oci.services.monitoring.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An alarm suppression (oci-go-sdk monitoring/alarm_suppression.go). {@code spec} holds the
 * create fields in wire casing. Deleting one leaves a DELETED tombstone.
 */
@RegisterForReflection
public class StoredAlarmSuppression {

    private String id;
    private String compartmentId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private Map<String, Object> spec = new LinkedHashMap<>();

    public StoredAlarmSuppression() {
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Map<String, Object> getSpec() {
        return spec;
    }

    public void setSpec(Map<String, Object> spec) {
        this.spec = spec;
    }
}
