package io.floci.oci.services.monitoring.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Monitoring alarm. {@code spec} holds the client-settable Alarm fields in wire casing
 * (oci-go-sdk monitoring/alarm.go), defaults filled in; the rest is evaluation state that the
 * status, history and dimension-state operations read. A deleted alarm stays as a DELETED
 * tombstone, the state Terraform waits for.
 */
@RegisterForReflection
public class StoredAlarm {

    private String id;
    private String compartmentId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private Map<String, Object> spec = new LinkedHashMap<>();
    private String status = "OK";
    private String timestampTriggered;
    private String firingSeverity;
    private String firingRuleName;
    private long lastNotified;
    private Map<String, Map<String, Object>> streams = new LinkedHashMap<>();
    private List<Map<String, Object>> history = new ArrayList<>();

    public StoredAlarm() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTimestampTriggered() {
        return timestampTriggered;
    }

    public void setTimestampTriggered(String timestampTriggered) {
        this.timestampTriggered = timestampTriggered;
    }

    public String getFiringSeverity() {
        return firingSeverity;
    }

    public void setFiringSeverity(String firingSeverity) {
        this.firingSeverity = firingSeverity;
    }

    public String getFiringRuleName() {
        return firingRuleName;
    }

    public void setFiringRuleName(String firingRuleName) {
        this.firingRuleName = firingRuleName;
    }

    public long getLastNotified() {
        return lastNotified;
    }

    public void setLastNotified(long lastNotified) {
        this.lastNotified = lastNotified;
    }

    public Map<String, Map<String, Object>> getStreams() {
        return streams;
    }

    public void setStreams(Map<String, Map<String, Object>> streams) {
        this.streams = streams;
    }

    public List<Map<String, Object>> getHistory() {
        return history;
    }

    public void setHistory(List<Map<String, Object>> history) {
        this.history = history;
    }
}
