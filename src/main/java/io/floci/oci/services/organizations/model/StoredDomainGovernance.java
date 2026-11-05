package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Governance on a claimed domain, stored in the owning tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredDomainGovernance {

    private String id;
    private String compartmentId;
    private String ownerId;
    private String domainId;
    private String domainName;
    private String lifecycleState;
    private String onsTopicId;
    private String onsSubscriptionId;
    private String subscriptionEmail;
    private Boolean isGovernanceEnabled;
    private String timeCreated;
    private String timeUpdated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    /** The OCI {@code DomainGovernance} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("ownerId", ownerId);
        wire.put("compartmentId", compartmentId);
        wire.put("domainId", domainId);
        wire.put("lifecycleState", lifecycleState);
        wire.put("isGovernanceEnabled", isGovernanceEnabled);
        if (subscriptionEmail != null) {
            wire.put("subscriptionEmail", subscriptionEmail);
        }
        wire.put("onsTopicId", onsTopicId);
        wire.put("onsSubscriptionId", onsSubscriptionId);
        wire.put("timeCreated", timeCreated);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getOnsTopicId() {
        return onsTopicId;
    }

    public void setOnsTopicId(String onsTopicId) {
        this.onsTopicId = onsTopicId;
    }

    public String getOnsSubscriptionId() {
        return onsSubscriptionId;
    }

    public void setOnsSubscriptionId(String onsSubscriptionId) {
        this.onsSubscriptionId = onsSubscriptionId;
    }

    public String getSubscriptionEmail() {
        return subscriptionEmail;
    }

    public void setSubscriptionEmail(String subscriptionEmail) {
        this.subscriptionEmail = subscriptionEmail;
    }

    public Boolean getIsGovernanceEnabled() {
        return isGovernanceEnabled;
    }

    public void setIsGovernanceEnabled(Boolean isGovernanceEnabled) {
        this.isGovernanceEnabled = isGovernanceEnabled;
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
