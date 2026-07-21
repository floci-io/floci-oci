package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A pre-authenticated request granting anonymous access via /p/{token}/… URLs. */
@RegisterForReflection
public class StoredPar {

    private String id;
    private String name;
    private String token;
    private String namespace;
    private String bucket;
    private String objectName;
    private String accessType;
    private String bucketListingAction;
    private String timeCreated;
    private String timeExpires;

    public StoredPar() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public String getBucketListingAction() {
        return bucketListingAction;
    }

    public void setBucketListingAction(String bucketListingAction) {
        this.bucketListingAction = bucketListingAction;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeExpires() {
        return timeExpires;
    }

    public void setTimeExpires(String timeExpires) {
        this.timeExpires = timeExpires;
    }
}
