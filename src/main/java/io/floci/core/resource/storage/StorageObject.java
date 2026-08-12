package io.floci.core.resource.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Universal object storage item holding binary content stream and metadata.
 */
public class StorageObject {
    private final StorageMetadata metadata;
    private final byte[] content;

    public StorageObject(StorageMetadata metadata, byte[] content) {
        this.metadata = metadata;
        this.content = content != null ? content : new byte[0];
    }

    public StorageMetadata getMetadata() {
        return metadata;
    }

    public byte[] getContent() {
        return content.clone();
    }

    public InputStream getContentStream() {
        return new ByteArrayInputStream(content);
    }
}
