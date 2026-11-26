package io.floci.oci.services.identity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An API signing key uploaded for a user (oci-go-sdk/identity/api_key.go). Requests whose keyId names it
 * are verified with it when {@code floci-oci.auth.signature-verification} is not {@code off}.
 */
@RegisterForReflection
public class StoredApiKey {

    private String keyId;
    private String keyValue;
    private String fingerprint;
    private String userId;
    private String timeCreated;
    private String lifecycleState;
    private String etag;

    public StoredApiKey() {
    }

    /** {@code TENANCY_OCID/USER_OCID/KEY_FINGERPRINT}, the {@code keyId} a request signs with. */
    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /** The public key in PEM format, as uploaded. */
    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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
