package io.floci.oci.core.retrytoken;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Receipts of requests that carried an {@code opc-retry-token}. OCI: "Retry tokens expire after
 * 24 hours" (oci-go-sdk, every operation declaring the header). The window is fixed from the first
 * use and does not slide.
 *
 * <p>Each service's receipts live beside its resources: one {@link StorageFactory#createGlobal} store
 * per service storage key, so they follow that service's storage mode (a restart keeps or drops
 * receipts exactly when it keeps or drops the resources they describe). Stores are partitioned by
 * tenancy; keys already carry the region. Requests outside a registered service use
 * {@link #DEFAULT_STORAGE_KEY}.
 *
 * <p>{@code POST /_floci-oci/state/reset} empties the stores through {@code StorageFactory.clearAll()}
 * and, through {@link #clear()}, starts a new generation: a request that began before the reset cannot
 * record its receipt after it, so a retry never replays a response for a resource the reset removed.
 */
@ApplicationScoped
public class RetryTokenStore implements Resettable {

    static final Duration TTL = Duration.ofHours(24);
    static final String DEFAULT_STORAGE_KEY = "retrytokens";

    private final Function<String, StorageBackend<String, StoredRetryReceipt>> backendFor;
    private final Map<String, StorageBackend<String, StoredRetryReceipt>> backends = new ConcurrentHashMap<>();
    private final Clock clock;
    private long generation;

    @Inject
    public RetryTokenStore(StorageFactory storageFactory, Clock clock) {
        this(storageKey -> storageFactory.createGlobal(storageKey, storageKey + "-retrytokens.json",
                new TypeReference<Map<String, StoredRetryReceipt>>() {}), clock);
    }

    RetryTokenStore(Function<String, StorageBackend<String, StoredRetryReceipt>> backendFor, Clock clock) {
        this.backendFor = backendFor;
        this.clock = clock;
    }

    /** The current reset generation; a request records its receipt only within the one it began in. */
    public synchronized long generation() {
        return generation;
    }

    /** The unexpired receipt for {@code key}, if a request with that token completed. */
    public Optional<StoredRetryReceipt> find(String storageKey, String key) {
        Instant now = clock.instant();
        return receipts(storageKey).get(key).filter(receipt -> now.isBefore(receipt.expiresAt()));
    }

    /**
     * Records a completed request begun in {@code startedIn}; empty when a reset happened since.
     * Expired receipts in the same tenancy are dropped on the way, never the key being saved.
     * Synchronized so a sweep cannot delete a receipt another request has just saved.
     */
    public synchronized Optional<StoredRetryReceipt> save(String storageKey, String key, long startedIn,
                                                          String fingerprint, int status, String contentType,
                                                          Map<String, List<String>> headers, String body) {
        if (startedIn != generation) {
            return Optional.empty();
        }
        StorageBackend<String, StoredRetryReceipt> receipts = receipts(storageKey);
        Instant now = clock.instant();
        StoredRetryReceipt receipt = new StoredRetryReceipt(fingerprint, status, contentType, headers, body,
                now, now.plus(TTL));
        List<String> expired = receipts.keys().stream()
                .filter(existing -> !existing.equals(key))
                .filter(existing -> receipts.get(existing)
                        .map(stored -> !now.isBefore(stored.expiresAt()))
                        .orElse(false))
                .toList();
        receipts.putAllAndDelete(Map.of(key, receipt), expired);
        return Optional.of(receipt);
    }

    /**
     * Starts a new generation. The receipts themselves are emptied by {@code StorageFactory.clearAll()},
     * which the reset runs after every {@link Resettable}.
     */
    @Override
    public synchronized void clear() {
        generation++;
    }

    private StorageBackend<String, StoredRetryReceipt> receipts(String storageKey) {
        return backends.computeIfAbsent(storageKey, backendFor);
    }
}
