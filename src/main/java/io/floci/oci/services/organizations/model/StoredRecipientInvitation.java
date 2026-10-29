package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An invitation a tenancy received, stored in the recipient tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredRecipientInvitation {

    private String id;
    private String compartmentId;
    private String senderInvitationId;
    private String senderTenancyId;
    private String displayName;
    private String recipientEmailAddress;
    private List<String> subjects;
    private List<String> features;
    private String lifecycleState;
    private String status;
    private String timeCreated;
    private String timeUpdated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    /** The OCI {@code RecipientInvitation} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("senderInvitationId", senderInvitationId);
        wire.put("senderTenancyId", senderTenancyId);
        wire.put("displayName", displayName);
        wire.put("subjects", subjects);
        if (features != null && !features.isEmpty()) {
            wire.put("features", features);
        }
        wire.put("lifecycleState", lifecycleState);
        wire.put("status", status);
        wire.put("timeCreated", timeCreated);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
        }
        if (recipientEmailAddress != null) {
            wire.put("recipientEmailAddress", recipientEmailAddress);
        }

        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
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

    public String getSenderInvitationId() {
        return senderInvitationId;
    }

    public void setSenderInvitationId(String senderInvitationId) {
        this.senderInvitationId = senderInvitationId;
    }

    public String getSenderTenancyId() {
        return senderTenancyId;
    }

    public void setSenderTenancyId(String senderTenancyId) {
        this.senderTenancyId = senderTenancyId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRecipientEmailAddress() {
        return recipientEmailAddress;
    }

    public void setRecipientEmailAddress(String recipientEmailAddress) {
        this.recipientEmailAddress = recipientEmailAddress;
    }

    public List<String> getSubjects() {
        return subjects;
    }

    public void setSubjects(List<String> subjects) {
        this.subjects = subjects;
    }

    public List<String> getFeatures() {
        return features;
    }

    public void setFeatures(List<String> features) {
        this.features = features;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
