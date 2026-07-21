package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An in-progress multipart upload with its uploaded parts. */
@RegisterForReflection
public class StoredMultipartUpload {

    private String namespace;
    private String bucket;
    private String object;
    private String uploadId;
    private String timeCreated;
    private String contentType;
    private Map<String, String> metadata;
    private Map<Integer, Part> parts = new LinkedHashMap<>();

    public StoredMultipartUpload() {
    }

    @RegisterForReflection
    public static class Part {

        private String etag;
        private byte[] data;

        public Part() {
        }

        public Part(String etag, byte[] data) {
            this.etag = etag;
            this.data = data;
        }

        public String getEtag() {
            return etag;
        }

        public void setEtag(String etag) {
            this.etag = etag;
        }

        public byte[] getData() {
            return data;
        }

        public void setData(byte[] data) {
            this.data = data;
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public Map<Integer, Part> getParts() {
        return parts;
    }

    public void setParts(Map<Integer, Part> parts) {
        this.parts = parts;
    }
}
