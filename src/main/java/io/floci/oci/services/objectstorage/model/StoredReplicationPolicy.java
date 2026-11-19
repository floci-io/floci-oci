package io.floci.oci.services.objectstorage.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A replication policy, stored in its source bucket's region. The source region and bucket are kept
 * here too, so a destination can find the policy that writes into it.
 */
@RegisterForReflection
public class StoredReplicationPolicy {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLIENT_ERROR = "CLIENT_ERROR";

    private String id;
    private String name;
    private String sourceRegionName;
    private String sourceBucketName;
    private String destinationRegionName;
    private String destinationBucketName;
    private String timeCreated;
    private String timeLastSync;
    private String status;
    private String statusMessage;

    public StoredReplicationPolicy() {
    }

    @JsonIgnore
    public boolean isActive() {
        return ACTIVE.equals(status);
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

    public String getSourceRegionName() {
        return sourceRegionName;
    }

    public void setSourceRegionName(String sourceRegionName) {
        this.sourceRegionName = sourceRegionName;
    }

    public String getSourceBucketName() {
        return sourceBucketName;
    }

    public void setSourceBucketName(String sourceBucketName) {
        this.sourceBucketName = sourceBucketName;
    }

    public String getDestinationRegionName() {
        return destinationRegionName;
    }

    public void setDestinationRegionName(String destinationRegionName) {
        this.destinationRegionName = destinationRegionName;
    }

    public String getDestinationBucketName() {
        return destinationBucketName;
    }

    public void setDestinationBucketName(String destinationBucketName) {
        this.destinationBucketName = destinationBucketName;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeLastSync() {
        return timeLastSync;
    }

    public void setTimeLastSync(String timeLastSync) {
        this.timeLastSync = timeLastSync;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }
}
