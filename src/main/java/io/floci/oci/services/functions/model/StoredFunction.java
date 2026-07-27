package io.floci.oci.services.functions.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** An OCI function. */
@RegisterForReflection
public class StoredFunction {

    private String id;
    private String applicationId;
    private String compartmentId;
    private String displayName;
    private String lifecycleState;
    private String image;
    private String imageDigest;
    private String shape;
    private long memoryInMBs;
    private int timeoutInSeconds;
    private Map<String, String> config;
    private String timeCreated;
    private String timeUpdated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;
    /** Mirrored Fn-server fn id, when the sidecar has been provisioned. */
    private String fnFnId;

    public StoredFunction() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
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

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
    }

    public String getShape() {
        return shape;
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    public long getMemoryInMBs() {
        return memoryInMBs;
    }

    public void setMemoryInMBs(long memoryInMBs) {
        this.memoryInMBs = memoryInMBs;
    }

    public int getTimeoutInSeconds() {
        return timeoutInSeconds;
    }

    public void setTimeoutInSeconds(int timeoutInSeconds) {
        this.timeoutInSeconds = timeoutInSeconds;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
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

    public String getFnFnId() {
        return fnFnId;
    }

    public void setFnFnId(String fnFnId) {
        this.fnFnId = fnFnId;
    }
}
