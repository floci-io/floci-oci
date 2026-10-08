package io.floci.oci.core.storage;

import io.floci.oci.core.common.RequestContext;
import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Decorator over {@link StorageBackend} that transparently prefixes every storage key
 * with the current tenancy OCID, providing per-tenancy resource isolation.
 *
 * <p>On the synchronous request path the tenancy OCID is read from {@link RequestContext},
 * which is populated by {@code the signature auth filter} before any handler runs.
 * Outside a request (async workers, startup) the {@code defaultTenancyId} is used.
 *
 * <p>Async workers that must access a specific tenancy's data should use the explicit
 * {@code *ForTenancy} overloads, passing the partition the resource lives in: the tenancy OCID
 * for a global store, {@link #regionalPartition} for a regional one.
 *
 * <p>Backward compatibility: on a {@link #get} miss for the prefixed key, the un-prefixed
 * key is tried and the entry is migrated on read. This covers existing persistent/WAL data
 * created before multi-tenancy support was added.
 */
public class TenancyAwareStorageBackend<V> implements StorageBackend<String, V> {

    private final StorageBackend<String, V> delegate;
    private final Supplier<String> partition;

    public TenancyAwareStorageBackend(StorageBackend<String, V> delegate,
                                      Instance<RequestContext> requestContextInstance,
                                      String defaultTenancyId) {
        this(delegate, () -> RequestContext.currentTenancyId(requestContextInstance,
                defaultTenancyId));
    }

    /**
     * @param partition the key prefix for the current call: the tenancy OCID for global
     *                  stores, {@code <tenancy>:<region>} for regional ones
     */
    public TenancyAwareStorageBackend(StorageBackend<String, V> delegate,
                                      Supplier<String> partition) {
        this.delegate = delegate;
        this.partition = partition;
    }

    /** The partition of a regional store: {@code <tenancy>:<region>}. */
    public static String regionalPartition(String tenancyId, String region) {
        return tenancyId + ":" + region;
    }

    /** The tenancy OCID a partition belongs to, for either a global or a regional store. */
    public static String tenancyOf(String partition) {
        int colon = partition.indexOf(':');
        return colon < 0 ? partition : partition.substring(0, colon);
    }

    /**
     * Moves entries written before region partitioning into {@code <tenancy>:<region>/<key>},
     * so existing persisted data lands in {@code region}: {@code <tenancy>/<key>} keeps its
     * tenancy, and a bare key from before multi-tenancy goes to {@code defaultTenancyId}, rather
     * than to whichever region first reads it. Tenancy OCIDs never contain {@code ':'}, which
     * tells the layouts apart.
     *
     * @return the number of entries moved
     */
    public int migrateToRegion(String defaultTenancyId, String region) {
        int moved = 0;
        for (String rawKey : delegate.keys()) {
            int slash = rawKey.indexOf('/');
            String head = slash < 0 ? rawKey : rawKey.substring(0, slash);
            String target;
            if (!head.startsWith("ocid1.tenancy.")) {
                target = regionalPartition(defaultTenancyId, region) + "/" + rawKey;
            } else if (slash > 0 && !head.contains(":")) {
                target = regionalPartition(head, region) + rawKey.substring(slash);
            } else {
                continue;
            }
            Optional<V> value = delegate.get(rawKey);
            if (value.isPresent()) {
                delegate.put(target, value.get());
                delegate.delete(rawKey);
                moved++;
            }
        }
        return moved;
    }

    @Override
    public void put(String key, V value) {
        delegate.put(prefixed(key), value);
    }

    @Override
    public Optional<V> get(String key) {
        String prefixedKey = prefixed(key);
        Optional<V> result = delegate.get(prefixedKey);
        if (result.isPresent()) {
            return result;
        }
        // Backward-compat: try un-prefixed key (pre-multi-tenancy data) and migrate on read.
        result = delegate.get(key);
        if (result.isPresent()) {
            delegate.put(prefixedKey, result.get());
            delegate.delete(key);
        }
        return result;
    }

    @Override
    public void delete(String key) {
        delegate.delete(prefixed(key));
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        String prefix = prefix() + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    @Override
    public Set<String> keys() {
        String prefix = prefix() + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    // --- Explicit-tenancy methods for async workers ---

    /** Scans all values across every tenancy, without any tenancy prefix filtering. */
    public List<V> scanAllTenancies() {
        return delegate.scan(k -> true);
    }

    /**
     * Returns all entries across every tenancy as a map of logical-key (tenancy prefix stripped)
     * to value. Entries without a slash-prefixed tenancy segment are skipped.
     */
    public Map<String, V> scanAllTenanciesAsMap() {
        Map<String, V> result = new LinkedHashMap<>();
        for (String rawKey : delegate.keys()) {
            int slash = rawKey.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String logicalKey = rawKey.substring(slash + 1);
            delegate.get(rawKey).ifPresent(v -> result.put(logicalKey, v));
        }
        return result;
    }

    public Optional<V> getForTenancy(String tenancyId, String key) {
        return delegate.get(tenancyId + "/" + key);
    }

    public void putForTenancy(String tenancyId, String key, V value) {
        delegate.put(tenancyId + "/" + key, value);
    }

    public void deleteForTenancy(String tenancyId, String key) {
        delegate.delete(tenancyId + "/" + key);
    }

    public List<V> scanForTenancy(String tenancyId, Predicate<String> keyFilter) {
        String prefix = tenancyId + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    /**
     * The partitions that own at least one entry: tenancy OCIDs in a global store,
     * {@code <tenancy>:<region>} in a regional one.
     */
    public Set<String> tenancies() {
        return delegate.keys().stream()
                .filter(k -> k.indexOf('/') > 0)
                .map(k -> k.substring(0, k.indexOf('/')))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> keysForTenancy(String tenancyId) {
        String prefix = tenancyId + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    // ---

    private String prefix() {
        return partition.get();
    }

    private String prefixed(String key) {
        return prefix() + "/" + key;
    }
}
