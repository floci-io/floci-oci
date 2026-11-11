package io.floci.oci.core.retrytoken;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
 * <p>{@code POST /_floci-oci/state/reset} runs {@link #clear()} and then {@code StorageFactory.clearAll()}.
 * {@link #clear()} opens every service's store first, because stores open lazily and {@code clearAll()}
 * only empties stores already open: after a restart with persistent storage, receipt files not yet
 * touched would otherwise survive the reset. It also starts a new generation, so a request that began
 * before the reset cannot record its receipt after it. Either way, a retry never replays a response for
 * a resource the reset removed.
 */
@ApplicationScoped
public class RetryTokenStore implements Resettable {

    static final Duration TTL = Duration.ofHours(24);
    static final String DEFAULT_STORAGE_KEY = "retrytokens";

    private final Function<String, StorageBackend<String, StoredRetryReceipt>> backendFor;
    private final Map<String, StorageBackend<String, StoredRetryReceipt>> backends = new ConcurrentHashMap<>();
    private final Supplier<List<String>> serviceStorageKeys;
    private final Clock clock;
    private long generation;

    @Inject
    public RetryTokenStore(StorageFactory storageFactory, ServiceRegistry serviceRegistry, Clock clock) {
        this(storageKey -> storageFactory.createGlobal(storageKey, storageKey + "-retrytokens.json",
                        new TypeReference<Map<String, StoredRetryReceipt>>() {}),
                () -> serviceRegistry.all().stream()
                        .map(ServiceDescriptor::storageKey)
                        .filter(Objects::nonNull)
                        .filter(key -> !key.isBlank())
                        .toList(),
                clock);
    }

    RetryTokenStore(Function<String, StorageBackend<String, StoredRetryReceipt>> backendFor,
                    Supplier<List<String>> serviceStorageKeys, Clock clock) {
        this.backendFor = backendFor;
        this.serviceStorageKeys = serviceStorageKeys;
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
                                                          Map<String, List<String>> headers, String body,
                                                          String createdId) {
        if (startedIn != generation) {
            return Optional.empty();
        }
        StorageBackend<String, StoredRetryReceipt> receipts = receipts(storageKey);
        Instant now = clock.instant();
        StoredRetryReceipt receipt = new StoredRetryReceipt(fingerprint, status, contentType, headers, body,
                createdId, false, now, now.plus(TTL));
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
     * Marks every unexpired receipt in the caller's tenancy that created {@code resourceId} as invalidated:
     * "The provided retry token was used in an earlier request that resulted in a system update, but a
     * subsequent operation invalidated the token" ({@code 409 InvalidatedRetryToken}, Oracle's error
     * catalog). The receipts stay until they expire, so the retry keeps getting that answer.
     */
    public synchronized void invalidateCreatedBy(String storageKey, String resourceId) {
        StorageBackend<String, StoredRetryReceipt> receipts = receipts(storageKey);
        Instant now = clock.instant();
        Map<String, StoredRetryReceipt> invalidated = new LinkedHashMap<>();
        for (String key : receipts.keys()) {
            receipts.get(key)
                    .filter(receipt -> resourceId.equals(receipt.createdId()) && !receipt.invalidated()
                            && now.isBefore(receipt.expiresAt()))
                    .ifPresent(receipt -> invalidated.put(key, receipt.invalidate()));
        }
        if (!invalidated.isEmpty()) {
            receipts.putAllAndDelete(invalidated, List.of());
        }
    }

    /**
     * Opens every service's store, so the {@code StorageFactory.clearAll()} the reset runs next empties
     * receipt files this process never touched, and starts a new generation.
     */
    @Override
    public synchronized void clear() {
        Stream.concat(Stream.of(DEFAULT_STORAGE_KEY), serviceStorageKeys.get().stream())
                .distinct()
                .forEach(this::receipts);
        generation++;
    }

    private StorageBackend<String, StoredRetryReceipt> receipts(String storageKey) {
        return backends.computeIfAbsent(storageKey, backendFor);
    }
}
