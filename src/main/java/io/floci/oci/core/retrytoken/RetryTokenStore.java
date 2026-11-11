package io.floci.oci.core.retrytoken;

import com.fasterxml.jackson.core.type.TypeReference;
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

/**
 * Receipts of requests that carried an {@code opc-retry-token}. OCI: "Retry tokens expire after
 * 24 hours" (oci-go-sdk, every operation declaring the header). The window is fixed from the first
 * use and does not slide.
 *
 * <p>Backed by {@link StorageFactory#createGlobal}, so receipts are partitioned by tenancy, follow
 * the storage mode the emulator runs in (a persistent emulator keeps them across restarts, as it
 * keeps the resources they created) and go with {@code POST /_floci-oci/state/reset}. Keys already
 * carry the region, so one global store serves every region.
 */
@ApplicationScoped
public class RetryTokenStore {

    static final Duration TTL = Duration.ofHours(24);

    private final StorageBackend<String, StoredRetryReceipt> receipts;
    private final Clock clock;

    @Inject
    public RetryTokenStore(StorageFactory storageFactory, Clock clock) {
        this(storageFactory.createGlobal("retrytokens", "retrytokens.json",
                new TypeReference<Map<String, StoredRetryReceipt>>() {}), clock);
    }

    RetryTokenStore(StorageBackend<String, StoredRetryReceipt> receipts, Clock clock) {
        this.receipts = receipts;
        this.clock = clock;
    }

    /** The unexpired receipt for {@code key}, if a request with that token completed. */
    public Optional<StoredRetryReceipt> find(String key) {
        Instant now = clock.instant();
        return receipts.get(key).filter(receipt -> now.isBefore(receipt.expiresAt()));
    }

    /** Records a completed request; expired receipts in the same tenancy are dropped on the way. */
    public StoredRetryReceipt save(String key, String fingerprint, int status, String contentType,
                                   Map<String, List<String>> headers, String body) {
        Instant now = clock.instant();
        StoredRetryReceipt receipt = new StoredRetryReceipt(fingerprint, status, contentType, headers, body,
                now, now.plus(TTL));
        List<String> expired = receipts.keys().stream()
                .filter(existing -> receipts.get(existing)
                        .map(stored -> !now.isBefore(stored.expiresAt()))
                        .orElse(false))
                .toList();
        receipts.putAllAndDelete(Map.of(key, receipt), expired);
        return receipt;
    }
}
