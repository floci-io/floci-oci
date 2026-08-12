package io.floci.core.resource.kv;

import java.time.Duration;

/**
 * Universal Key-Value store contract across DynamoDB (AWS), CosmosDB (Azure), and Datastore/Firestore (GCP).
 */
public interface KeyValueStore {
    void put(String tableOrNamespace, String key, byte[] value, Duration ttl);

    byte[] get(String tableOrNamespace, String key);

    boolean delete(String tableOrNamespace, String key);

    boolean exists(String tableOrNamespace, String key);
}
