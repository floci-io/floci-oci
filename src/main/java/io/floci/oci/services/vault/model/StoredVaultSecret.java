package io.floci.oci.services.vault.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A vault secret with its versions. Content is only ever exposed via secret bundles. */
@RegisterForReflection
public class StoredVaultSecret {

    private String id;
    private String compartmentId;
    private String vaultId;
    private String keyId;
    private String secretName;
    private String description;
    private String lifecycleState;
    private String timeCreated;
    private String timeOfDeletion;
    private long currentVersionNumber;
    private Map<String, Object> metadata;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;
    private List<StoredSecretVersion> versions = new ArrayList<>();

    public StoredVaultSecret() {
    }

    @RegisterForReflection
    public static class StoredSecretVersion {

        private long versionNumber;
        private String name;
        private String contentType;
        private String content;
        private String timeCreated;
        private List<String> stages = new ArrayList<>();

        public StoredSecretVersion() {
        }

        public long getVersionNumber() {
            return versionNumber;
        }

        public void setVersionNumber(long versionNumber) {
            this.versionNumber = versionNumber;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getTimeCreated() {
            return timeCreated;
        }

        public void setTimeCreated(String timeCreated) {
            this.timeCreated = timeCreated;
        }

        public List<String> getStages() {
            return stages;
        }

        public void setStages(List<String> stages) {
            this.stages = stages;
        }
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

    public String getVaultId() {
        return vaultId;
    }

    public void setVaultId(String vaultId) {
        this.vaultId = vaultId;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public long getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public void setCurrentVersionNumber(long currentVersionNumber) {
        this.currentVersionNumber = currentVersionNumber;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
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

    public List<StoredSecretVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<StoredSecretVersion> versions) {
        this.versions = versions;
    }
}
