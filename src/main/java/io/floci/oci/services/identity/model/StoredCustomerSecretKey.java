package io.floci.oci.services.identity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Customer Secret Key: the access key / secret pair the Amazon S3 Compatibility API signs with.
 * The secret is returned once, by the create call, and never stored, because floci-oci never
 * verifies a signature with it.
 */
@RegisterForReflection
public class StoredCustomerSecretKey {

    private String id;
    private String userId;
    private String displayName;
    private String timeCreated;
    private String lifecycleState;
    private String etag;

    public StoredCustomerSecretKey() {
    }

    /** The access key portion of the pair (identity/customer_secret_key.go). */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
