package io.floci.oci.services.objectstorage.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** A bucket. Unlike most OCI resources the etag is part of the wire body as well as the header. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredBucket {

    private String namespace;
    private String name;
    private String compartmentId;
    private Map<String, String> metadata;
    private String createdBy;
    private String timeCreated;
    private String etag;
    private String publicAccessType;
    private String storageTier;
    private Boolean objectEventsEnabled;
    private String versioning;
    private String id;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;

    public StoredBucket() {
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

    public String getPublicAccessType() {
        return publicAccessType;
    }

    public void setPublicAccessType(String publicAccessType) {
        this.publicAccessType = publicAccessType;
    }

    public String getStorageTier() {
        return storageTier;
    }

    public void setStorageTier(String storageTier) {
        this.storageTier = storageTier;
    }

    public Boolean getObjectEventsEnabled() {
        return objectEventsEnabled;
    }

    public void setObjectEventsEnabled(Boolean objectEventsEnabled) {
        this.objectEventsEnabled = objectEventsEnabled;
    }

    public String getVersioning() {
        return versioning;
    }

    public void setVersioning(String versioning) {
        this.versioning = versioning;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
