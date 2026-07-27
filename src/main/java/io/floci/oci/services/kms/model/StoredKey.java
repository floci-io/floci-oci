package io.floci.oci.services.kms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A KMS key with its versions and (emulator-held) key material. */
@RegisterForReflection
public class StoredKey {

    private String id;
    private String vaultId;
    private String compartmentId;
    private String displayName;
    private String algorithm;
    private Integer length;
    private String curveId;
    private String protectionMode;
    private String lifecycleState;
    private String timeCreated;
    private String timeOfDeletion;
    private String currentKeyVersionId;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;
    private List<StoredKeyVersion> versions = new ArrayList<>();

    public StoredKey() {
    }

    @RegisterForReflection
    public static class StoredKeyVersion {

        private String id;
        private String timeCreated;
        private String lifecycleState;
        private String origin;
        /** Base64 symmetric key material, or PKCS8 private key for RSA/ECDSA. */
        private String keyMaterial;
        /** PEM public key for asymmetric keys. */
        private String publicKey;

        public StoredKeyVersion() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTimeCreated() {
            return timeCreated;
        }

        public void setTimeCreated(String timeCreated) {
            this.timeCreated = timeCreated;
        }

        public String getLifecycleState() {
            return lifecycleState;
        }

        public void setLifecycleState(String lifecycleState) {
            this.lifecycleState = lifecycleState;
        }

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            this.origin = origin;
        }

        public String getKeyMaterial() {
            return keyMaterial;
        }

        public void setKeyMaterial(String keyMaterial) {
            this.keyMaterial = keyMaterial;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVaultId() {
        return vaultId;
    }

    public void setVaultId(String vaultId) {
        this.vaultId = vaultId;
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

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        this.length = length;
    }

    public String getCurveId() {
        return curveId;
    }

    public void setCurveId(String curveId) {
        this.curveId = curveId;
    }

    public String getProtectionMode() {
        return protectionMode;
    }

    public void setProtectionMode(String protectionMode) {
        this.protectionMode = protectionMode;
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

    public String getCurrentKeyVersionId() {
        return currentKeyVersionId;
    }

    public void setCurrentKeyVersionId(String currentKeyVersionId) {
        this.currentKeyVersionId = currentKeyVersionId;
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

    public List<StoredKeyVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<StoredKeyVersion> versions) {
        this.versions = versions;
    }
}
