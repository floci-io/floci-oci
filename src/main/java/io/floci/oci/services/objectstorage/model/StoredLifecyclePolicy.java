package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A bucket's object lifecycle policy: one per bucket, replaced as a whole by every Put. */
@RegisterForReflection
public class StoredLifecyclePolicy {

    private String bucketName;
    private String timeCreated;
    private String etag;
    private List<StoredLifecycleRule> items = new ArrayList<>();

    public StoredLifecyclePolicy() {
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public List<StoredLifecycleRule> getItems() {
        return items;
    }

    public void setItems(List<StoredLifecycleRule> items) {
        this.items = items;
    }
}
