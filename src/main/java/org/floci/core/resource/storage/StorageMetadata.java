package org.floci.core.resource.storage;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata associated with a stored object (ETag, size, content type, custom headers).
 */
public class StorageMetadata {
    private final String key;
    private final long size;
    private final String contentType;
    private final String etag;
    private final Instant lastModified;
    private final Map<String, String> userMetadata;

    public StorageMetadata(String key, long size, String contentType, String etag, Instant lastModified, Map<String, String> userMetadata) {
        this.key = key;
        this.size = size;
        this.contentType = contentType != null ? contentType : "application/octet-stream";
        this.etag = etag != null ? etag : "";
        this.lastModified = lastModified != null ? lastModified : Instant.now();
        this.userMetadata = userMetadata != null ? new HashMap<>(userMetadata) : new HashMap<>();
    }

    public StorageMetadata(String key, long size, String contentType, String etag, Instant lastModified) {
        this(key, size, contentType, etag, lastModified, Collections.emptyMap());
    }

    public String getKey() {
        return key;
    }

    public long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }

    public String getEtag() {
        return etag;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public Map<String, String> getUserMetadata() {
        return Collections.unmodifiableMap(userMetadata);
    }
}
