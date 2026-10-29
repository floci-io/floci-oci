package io.floci.oci.services.governancerules.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Which tenancies a governance rule applies to, stored in the parent tenancy's partition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredInclusionCriterion {

    private String id;
    private String governanceRuleId;
    private String type;
    private String tenancyId;
    private String lifecycleState;
    private String timeCreated;
    private String timeUpdated;
    private String etag;

    /** The OCI {@code InclusionCriterion} wire shape, also used for list summaries. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("governanceRuleId", governanceRuleId);
        wire.put("type", type);
        if (tenancyId != null) {
            Map<String, Object> association = new LinkedHashMap<>();
            association.put("type", "TENANCY");
            association.put("tenancyId", tenancyId);
            wire.put("association", association);
        }
        wire.put("lifecycleState", lifecycleState);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGovernanceRuleId() {
        return governanceRuleId;
    }

    public void setGovernanceRuleId(String governanceRuleId) {
        this.governanceRuleId = governanceRuleId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
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
}
