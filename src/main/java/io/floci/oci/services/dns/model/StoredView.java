package io.floci.oci.services.dns.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A private DNS view (oci-go-sdk dns/view.go). */
@RegisterForReflection
public class StoredView {

    private String id;
    private String compartmentId;
    private String displayName;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private boolean protectedView;
    private Map<String, Object> freeformTags = new LinkedHashMap<>();
    private Map<String, Object> definedTags = new LinkedHashMap<>();

    public StoredView() {
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    public boolean isProtectedView() {
        return protectedView;
    }

    public void setProtectedView(boolean protectedView) {
        this.protectedView = protectedView;
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
}
