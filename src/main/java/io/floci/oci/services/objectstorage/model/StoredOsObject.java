package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** A stored object: bytes plus wire attributes. Never serialized to clients directly. */
@RegisterForReflection
public class StoredOsObject {

    private String name;
    private byte[] data;
    private String md5;
    private String etag;
    private String contentType;
    private String storageTier;
    private String timeCreated;
    private String timeModified;
    private Map<String, String> metadata;

    public StoredOsObject() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStorageTier() {
        return storageTier;
    }

    public void setStorageTier(String storageTier) {
        this.storageTier = storageTier;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeModified() {
        return timeModified;
    }

    public void setTimeModified(String timeModified) {
        this.timeModified = timeModified;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
