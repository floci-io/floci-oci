package io.floci.oci.services.containerinstances.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One container instance. The fields floci-oci acts on are typed; the ones it validates and echoes
 * as sent ({@code shapeConfig}, {@code volumes}, {@code dnsConfig}, {@code imagePullSecrets},
 * {@code securityContext}, ...) live in {@link #attributes} in their ContainerInstance wire shape
 * (oci-go-sdk/containerinstances/container_instance.go). Registry credentials are kept apart and
 * never returned.
 */
@RegisterForReflection
public class StoredContainerInstance {

    private String id;
    private String tenancyId;
    private String region;
    private String compartmentId;
    private String displayName;
    private String availabilityDomain;
    private String lifecycleState;
    private String lifecycleDetails;
    private String timeCreated;
    private String timeUpdated;
    private String etag;
    private String workRequestId;
    private List<String> containerIds = new ArrayList<>();
    private List<String> vnicIds = new ArrayList<>();
    private boolean vnicAddressFromWorkload;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private List<PullCredential> pullCredentials = new ArrayList<>();

    public StoredContainerInstance() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
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

    public String getAvailabilityDomain() {
        return availabilityDomain;
    }

    public void setAvailabilityDomain(String availabilityDomain) {
        this.availabilityDomain = availabilityDomain;
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

    public String getWorkRequestId() {
        return workRequestId;
    }

    public void setWorkRequestId(String workRequestId) {
        this.workRequestId = workRequestId;
    }

    public List<String> getContainerIds() {
        return containerIds;
    }

    public void setContainerIds(List<String> containerIds) {
        this.containerIds = containerIds;
    }

    public List<String> getVnicIds() {
        return vnicIds;
    }

    public void setVnicIds(List<String> vnicIds) {
        this.vnicIds = vnicIds;
    }

    public boolean isVnicAddressFromWorkload() {
        return vnicAddressFromWorkload;
    }

    public void setVnicAddressFromWorkload(boolean vnicAddressFromWorkload) {
        this.vnicAddressFromWorkload = vnicAddressFromWorkload;
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

    public List<PullCredential> getPullCredentials() {
        return pullCredentials;
    }

    public void setPullCredentials(List<PullCredential> pullCredentials) {
        this.pullCredentials = pullCredentials;
    }

    /** Registry credentials from {@code imagePullSecrets}, decoded; used for pulls, never returned. */
    @RegisterForReflection
    public record PullCredential(String registryEndpoint, String username, String password) {
    }
}
