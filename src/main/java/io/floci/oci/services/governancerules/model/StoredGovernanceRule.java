package io.floci.oci.services.governancerules.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A governance rule, stored in the parent tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredGovernanceRule {

    private String id;
    private String compartmentId;
    private String displayName;
    private String description;
    private String type;
    private String creationOption;
    private Map<String, Object> template;
    private String relatedResourceId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    /** The OCI {@code GovernanceRuleSummary} wire shape. */
    public Map<String, Object> toSummaryWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("displayName", displayName);
        wire.put("type", type);
        wire.put("creationOption", creationOption);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        wire.put("lifecycleState", lifecycleState);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** The OCI {@code GovernanceRule} wire shape. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = toSummaryWire();
        if (description != null) {
            wire.put("description", description);
        }
        wire.put("template", template);
        if (relatedResourceId != null) {
            wire.put("relatedResourceId", relatedResourceId);
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCreationOption() {
        return creationOption;
    }

    public void setCreationOption(String creationOption) {
        this.creationOption = creationOption;
    }

    public Map<String, Object> getTemplate() {
        return template;
    }

    public void setTemplate(Map<String, Object> template) {
        this.template = template;
    }

    public String getRelatedResourceId() {
        return relatedResourceId;
    }

    public void setRelatedResourceId(String relatedResourceId) {
        this.relatedResourceId = relatedResourceId;
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
}
