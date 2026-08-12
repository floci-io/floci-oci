package io.floci.core.resource.storage;

import java.util.List;

/**
 * Universal object store contract across S3 (AWS), Blob Storage (Azure), and GCS (GCP).
 */
public interface ObjectStore {
    void putObject(String containerOrBucket, String key, byte[] data, String contentType);

    StorageObject readObject(String containerOrBucket, String key);

    StorageMetadata getMetadata(String containerOrBucket, String key);

    boolean removeObject(String containerOrBucket, String key);

    List<StorageMetadata> listObjects(String containerOrBucket, String prefix);

    boolean containerExists(String containerOrBucket);

    void createContainer(String containerOrBucket);
}
