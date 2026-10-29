package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An invitation a parent tenancy sent, stored in the sender tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredSenderInvitation {

    private String id;
    private String compartmentId;
    private String recipientInvitationId;
    private String recipientTenancyId;
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

    /** The OCI {@code SenderInvitation} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("recipientInvitationId", recipientInvitationId);
        wire.put("recipientTenancyId", recipientTenancyId);
        wire.put("displayName", displayName);
        wire.put("subjects", subjects);
        if (features != null && !features.isEmpty()) {
            List<Map<String, Object>> invitationFeatures = new ArrayList<>();
            for (String feature : features) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", feature);
                entry.put("recipientInvitationId", recipientInvitationId);
                entry.put("status", status);
                invitationFeatures.add(entry);
            }
            wire.put("invitationFeatures", invitationFeatures);
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

    public String getRecipientInvitationId() {
        return recipientInvitationId;
    }

    public void setRecipientInvitationId(String recipientInvitationId) {
        this.recipientInvitationId = recipientInvitationId;
    }

    public String getRecipientTenancyId() {
        return recipientTenancyId;
    }

    public void setRecipientTenancyId(String recipientTenancyId) {
        this.recipientTenancyId = recipientTenancyId;
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
