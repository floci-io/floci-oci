package io.floci.oci.core.common;

/**
 * Implemented by a service that keeps state derived from an organization's members (governance
 * rule attachments), so Organizations can tell it a member changed without knowing that service.
 */
public interface OrganizationMembershipListener {

    /**
     * A member of the organization {@code parentTenancyId} owns joined, left, came back, or
     * changed its governance status.
     */
    void membershipsChanged(String parentTenancyId);
}
