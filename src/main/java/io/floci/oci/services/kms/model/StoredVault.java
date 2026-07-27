package io.floci.oci.services.kms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** A KMS vault. Wire shapes are projected in the controller; endpoints are derived. */
@RegisterForReflection
public class StoredVault {

    private String id;
    private String compartmentId;
    private String displayName;
    private String vaultType;
    private String lifecycleState;
    private String timeCreated;
    private String timeOfDeletion;
    private String wrappingkeyId;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    public StoredVault() {
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getVaultType() {
        return vaultType;
    }

    public void setVaultType(String vaultType) {
        this.vaultType = vaultType;
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

    public String getTimeOfDeletion() {
        return timeOfDeletion;
    }

    public void setTimeOfDeletion(String timeOfDeletion) {
        this.timeOfDeletion = timeOfDeletion;
    }

    public String getWrappingkeyId() {
        return wrappingkeyId;
    }

    public void setWrappingkeyId(String wrappingkeyId) {
        this.wrappingkeyId = wrappingkeyId;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
