package io.floci.oci.services.containerinstances.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One container of a container instance: the Container wire shape of
 * oci-go-sdk/containerinstances/container.go. What floci-oci acts on is typed; the fields it echoes
 * ({@code command}, {@code arguments}, {@code environmentVariables}, {@code volumeMounts},
 * {@code healthChecks}, {@code resourceConfig}, ...) live in {@link #attributes}.
 */
@RegisterForReflection
public class StoredContainer {

    private String id;
    private String containerInstanceId;
    private String compartmentId;
    private String availabilityDomain;
    private String displayName;
    private String lifecycleState;
    private String lifecycleDetails;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private String imageUrl;
    private Integer exitCode;
    private String timeTerminated;
    private int containerRestartAttemptCount;
    private String dockerName;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public StoredContainer() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContainerInstanceId() {
        return containerInstanceId;
    }

    public void setContainerInstanceId(String containerInstanceId) {
        this.containerInstanceId = containerInstanceId;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getAvailabilityDomain() {
        return availabilityDomain;
    }

    public void setAvailabilityDomain(String availabilityDomain) {
        this.availabilityDomain = availabilityDomain;
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

    public String getLifecycleDetails() {
        return lifecycleDetails;
    }

    public void setLifecycleDetails(String lifecycleDetails) {
        this.lifecycleDetails = lifecycleDetails;
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getTimeTerminated() {
        return timeTerminated;
    }

    public void setTimeTerminated(String timeTerminated) {
        this.timeTerminated = timeTerminated;
    }

    public int getContainerRestartAttemptCount() {
        return containerRestartAttemptCount;
    }

    public void setContainerRestartAttemptCount(int containerRestartAttemptCount) {
        this.containerRestartAttemptCount = containerRestartAttemptCount;
    }

    public String getDockerName() {
        return dockerName;
    }

    public void setDockerName(String dockerName) {
        this.dockerName = dockerName;
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

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
