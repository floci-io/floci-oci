package io.floci.oci.core.common;

import jakarta.enterprise.context.RequestScoped;

/**
 * Holds per-request derived values — tenancy OCID, user OCID and region — extracted
 * from the incoming OCI Signature {@code Authorization} header. Populated by the
 * signature auth filter before any handler runs.
 */
@RequestScoped
public class RequestContext {

    private String tenancyId;
    private String userId;
    private String region;

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
