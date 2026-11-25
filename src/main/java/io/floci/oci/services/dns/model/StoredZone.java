package io.floci.oci.services.dns.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DNS zone and its records (oci-go-sdk dns/zone.go). The records live inside the zone so a
 * PATCH or PUT replaces them in one store write, which keeps every record change atomic.
 * {@code serial} doubles as the zone {@code version}.
 */
@RegisterForReflection
public class StoredZone {

    private String id;
    private String name;
    private String compartmentId;
    private String scope;
    private String zoneType;
    private String viewId;
    private String resolutionMode;
    private String dnssecState;
    private String lifecycleState;
    private String timeCreated;
    private long serial;
    private String etag;
    private boolean protectedZone;
    private Map<String, Object> freeformTags = new LinkedHashMap<>();
    private Map<String, Object> definedTags = new LinkedHashMap<>();
    private List<StoredRecord> records = new ArrayList<>();

    public StoredZone() {
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getZoneType() {
        return zoneType;
    }

    public void setZoneType(String zoneType) {
        this.zoneType = zoneType;
    }

    public String getViewId() {
        return viewId;
    }

    public void setViewId(String viewId) {
        this.viewId = viewId;
    }

    public String getResolutionMode() {
        return resolutionMode;
    }

    public void setResolutionMode(String resolutionMode) {
        this.resolutionMode = resolutionMode;
    }

    public String getDnssecState() {
        return dnssecState;
    }

    public void setDnssecState(String dnssecState) {
        this.dnssecState = dnssecState;
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

    public long getSerial() {
        return serial;
    }

    public void setSerial(long serial) {
        this.serial = serial;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public boolean isProtectedZone() {
        return protectedZone;
    }

    public void setProtectedZone(boolean protectedZone) {
        this.protectedZone = protectedZone;
    }

    public Map<String, Object> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, Object> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Object> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Object> definedTags) {
        this.definedTags = definedTags;
    }

    public List<StoredRecord> getRecords() {
        return records;
    }

    public void setRecords(List<StoredRecord> records) {
        this.records = records;
    }
}
