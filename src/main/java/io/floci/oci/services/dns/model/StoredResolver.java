package io.floci.oci.services.dns.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A VCN-dedicated resolver (oci-go-sdk dns/resolver.go). It exists for as long as its VCN does and
 * owns a protected default view; {@code attachedViewIds} are the other views it consults, and
 * {@code rules} its FORWARD rules in wire casing.
 */
@RegisterForReflection
public class StoredResolver {

    private String id;
    private String compartmentId;
    private String displayName;
    private String attachedVcnId;
    private String defaultViewId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private List<String> attachedViewIds = new ArrayList<>();
    private List<Map<String, Object>> rules = new ArrayList<>();
    private Map<String, Object> freeformTags = new LinkedHashMap<>();
    private Map<String, Object> definedTags = new LinkedHashMap<>();

    public StoredResolver() {
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

    public String getAttachedVcnId() {
        return attachedVcnId;
    }

    public void setAttachedVcnId(String attachedVcnId) {
        this.attachedVcnId = attachedVcnId;
    }

    public String getDefaultViewId() {
        return defaultViewId;
    }

    public void setDefaultViewId(String defaultViewId) {
        this.defaultViewId = defaultViewId;
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

    public List<String> getAttachedViewIds() {
        return attachedViewIds;
    }

    public void setAttachedViewIds(List<String> attachedViewIds) {
        this.attachedViewIds = attachedViewIds;
    }

    public List<Map<String, Object>> getRules() {
        return rules;
    }

    public void setRules(List<Map<String, Object>> rules) {
        this.rules = rules;
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
