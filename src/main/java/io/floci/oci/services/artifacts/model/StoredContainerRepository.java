package io.floci.oci.services.artifacts.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A container repository. Image counts and sizes are not stored: they are read from the
 * registry each time, so they can never disagree with what was actually pushed.
 */
@RegisterForReflection
public class StoredContainerRepository {

    private String id;
    private String compartmentId;
    private String displayName;
    private String namespace;
    private String createdBy;
    private boolean immutable;
    private boolean publicRepository;
    private String lifecycleState;
    private String timeCreated;
    private String timeLastPushed;
    private String etag;
    private Map<String, Object> readme;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();

    public StoredContainerRepository() {
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

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public void setImmutable(boolean immutable) {
        this.immutable = immutable;
    }

    public boolean isPublicRepository() {
        return publicRepository;
    }

    public void setPublicRepository(boolean publicRepository) {
        this.publicRepository = publicRepository;
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

    public String getTimeLastPushed() {
        return timeLastPushed;
    }

    public void setTimeLastPushed(String timeLastPushed) {
        this.timeLastPushed = timeLastPushed;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    /** {@code {content, format}}; null when the client never set one, because Terraform reads absence as "no readme". */
    public Map<String, Object> getReadme() {
        return readme;
    }

    public void setReadme(Map<String, Object> readme) {
        this.readme = readme;
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
}
