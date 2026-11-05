package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A subscription assigned to a tenancy, stored in the subscription owner's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredSubscriptionMapping {

    private String id;
    private String subscriptionId;
    private String compartmentId;
    private String ownerId;
    private Boolean isExplicitlyAssigned;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String timeTerminated;
    private String etag;

    /** The OCI {@code SubscriptionMapping} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("subscriptionId", subscriptionId);
        wire.put("compartmentId", compartmentId);
        wire.put("isExplicitlyAssigned", isExplicitlyAssigned);
        wire.put("lifecycleState", lifecycleState);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        if (timeTerminated != null) {
            wire.put("timeTerminated", timeTerminated);
        }
        wire.put("systemTags", Map.of());
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
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

    public Boolean getIsExplicitlyAssigned() {
        return isExplicitlyAssigned;
    }

    public void setIsExplicitlyAssigned(Boolean isExplicitlyAssigned) {
        this.isExplicitlyAssigned = isExplicitlyAssigned;
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

    public String getTimeTerminated() {
        return timeTerminated;
    }

    public void setTimeTerminated(String timeTerminated) {
        this.timeTerminated = timeTerminated;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
