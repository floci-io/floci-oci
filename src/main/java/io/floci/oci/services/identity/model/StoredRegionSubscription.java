package io.floci.oci.services.identity.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredRegionSubscription {

    private String regionKey;
    private String regionName;
    private String status;
    private Boolean isHomeRegion;

    public StoredRegionSubscription() {
    }

    public StoredRegionSubscription(String regionKey, String regionName, String status,
                                    Boolean isHomeRegion) {
        this.regionKey = regionKey;
        this.regionName = regionName;
        this.status = status;
        this.isHomeRegion = isHomeRegion;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(String regionKey) {
        this.regionKey = regionKey;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsHomeRegion() {
        return isHomeRegion;
    }

    public void setIsHomeRegion(Boolean isHomeRegion) {
        this.isHomeRegion = isHomeRegion;
    }
}
