package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tenancy's membership in an organization, stored in the parent tenancy's partition.
 * {@code organizationId} and {@code etag} are internal and stay off the wire.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredOrganizationTenancy {

    private String organizationId;
    private String tenancyId;
    private String governanceStatus;
    private String name;
    private String lifecycleState;
    private String role;
    private String timeJoined;
    private String timeLeft;
    private Boolean isApprovedForTransfer;
    private String etag;

    /** The OCI {@code OrganizationTenancy} wire shape. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("tenancyId", tenancyId);
        wire.put("governanceStatus", governanceStatus);
        wire.put("name", name);
        wire.put("lifecycleState", lifecycleState);
        wire.put("role", role);
        wire.put("timeJoined", timeJoined);
        if (timeLeft != null) {
            wire.put("timeLeft", timeLeft);
        }
        wire.put("isApprovedForTransfer", isApprovedForTransfer);
        return wire;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getGovernanceStatus() {
        return governanceStatus;
    }

    public void setGovernanceStatus(String governanceStatus) {
        this.governanceStatus = governanceStatus;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTimeJoined() {
        return timeJoined;
    }

    public void setTimeJoined(String timeJoined) {
        this.timeJoined = timeJoined;
    }

    public String getTimeLeft() {
        return timeLeft;
    }

    public void setTimeLeft(String timeLeft) {
        this.timeLeft = timeLeft;
    }

    public Boolean getIsApprovedForTransfer() {
        return isApprovedForTransfer;
    }

    public void setIsApprovedForTransfer(Boolean isApprovedForTransfer) {
        this.isApprovedForTransfer = isApprovedForTransfer;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
