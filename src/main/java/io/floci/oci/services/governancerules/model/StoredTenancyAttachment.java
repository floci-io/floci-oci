package io.floci.oci.services.governancerules.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A governance rule applied to one child tenancy, stored in the parent tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredTenancyAttachment {

    private String id;
    private String compartmentId;
    private String governanceRuleId;
    private String tenancyId;
    private String enforcedGovernanceRuleId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String timeLastAttempted;
    private String etag;

    /** The OCI {@code TenancyAttachment} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("governanceRuleId", governanceRuleId);
        wire.put("tenancyId", tenancyId);
        wire.put("lifecycleState", lifecycleState);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        if (timeLastAttempted != null) {
            wire.put("timeLastAttempted", timeLastAttempted);
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

    public String getGovernanceRuleId() {
        return governanceRuleId;
    }

    public void setGovernanceRuleId(String governanceRuleId) {
        this.governanceRuleId = governanceRuleId;
    }

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getEnforcedGovernanceRuleId() {
        return enforcedGovernanceRuleId;
    }

    public void setEnforcedGovernanceRuleId(String enforcedGovernanceRuleId) {
        this.enforcedGovernanceRuleId = enforcedGovernanceRuleId;
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

    public String getTimeLastAttempted() {
        return timeLastAttempted;
    }

    public void setTimeLastAttempted(String timeLastAttempted) {
        this.timeLastAttempted = timeLastAttempted;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
