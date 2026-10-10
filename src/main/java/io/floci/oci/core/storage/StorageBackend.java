package io.floci.oci.core.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generic storage abstraction for emulator services.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface StorageBackend<K, V> {

    void put(K key, V value);

    Optional<V> get(K key);

    void delete(K key);

    /**
     * Return a new mutable list of values whose keys pass the filter. Callers may sort,
     * filter, or otherwise mutate the returned list without affecting the underlying store.
     */
    List<V> scan(Predicate<K> keyFilter);

    /**
     * Puts {@code puts}, then deletes {@code deletes}, as one change. Write-through backends
     * override this to persist once instead of once per entry.
     */
    default void putAllAndDelete(Map<K, V> puts, Collection<K> deletes) {
        puts.forEach(this::put);
        deletes.forEach(this::delete);
    }

    /** Return all keys in this store. */
    Set<K> keys();

    /** Persist data to disk if applicable. */
    void flush();

    /** Load data from disk on startup. */
    void load();

    /** Clear all data. */
    void clear();
}
