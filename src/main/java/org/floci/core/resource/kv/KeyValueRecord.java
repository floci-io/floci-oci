package org.floci.core.resource.kv;

import java.time.Instant;

/**
 * Record model representing key-value entries with optional TTL expiration.
 */
public class KeyValueRecord {
    private final String key;
    private final byte[] value;
    private final Instant expiration;

    public KeyValueRecord(String key, byte[] value, Instant expiration) {
        this.key = key;
        this.value = value != null ? value : new byte[0];
        this.expiration = expiration;
    }

    public String getKey() {
        return key;
    }

    public byte[] getValue() {
        return value.clone();
    }

    public Instant getExpiration() {
        return expiration;
    }

    public boolean isExpired() {
        return expiration != null && Instant.now().isAfter(expiration);
    }
}
