package io.floci.oci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The activation of an order, keyed by a hash of its activation token, stored with the activating tenancy. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredOrderActivation {

    private String tokenHash;
    private String orderNumber;
    private String compartmentId;
    private String timeActivated;

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getTimeActivated() {
        return timeActivated;
    }

    public void setTimeActivated(String timeActivated) {
        this.timeActivated = timeActivated;
    }
}
