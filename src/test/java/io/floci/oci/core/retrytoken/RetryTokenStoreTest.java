package io.floci.oci.core.retrytoken;

import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryTokenStoreTest {

    private static final String QUEUE = "queue";

    private final Map<String, InMemoryStorage<String, StoredRetryReceipt>> backends = new HashMap<>();
    private MutableClock clock;
    private RetryTokenStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new RetryTokenStore(storageKey -> backends.computeIfAbsent(storageKey, k -> new InMemoryStorage<>()),
                () -> List.of(QUEUE, "psql"), clock);
    }

    private Optional<StoredRetryReceipt> save(String storageKey, String key) {
        return store.save(storageKey, key, store.generation(), "fp", 200, null, Map.of(), null, null);
    }

    @Test
    void aSavedReceiptIsFoundWithEverythingItRecorded() {
        store.save(QUEUE, "k1", store.generation(), "fp", 202, null,
                Map.of("opc-work-request-id", List.of("ocid1.wr")), null, "ocid1.queue.oc1.iad.q1");

        StoredRetryReceipt receipt = store.find(QUEUE, "k1").orElseThrow();
        assertEquals("fp", receipt.fingerprint());
        assertEquals(202, receipt.status());
        assertEquals(List.of("ocid1.wr"), receipt.headers().get("opc-work-request-id"));
        assertEquals(receipt.createdAt().plus(RetryTokenStore.TTL), receipt.expiresAt());
    }

    @Test
    void aReceiptLastsTwentyFourHoursAndThenExpires() {
        save(QUEUE, "k1");

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertTrue(store.find(QUEUE, "k1").isPresent());

        clock.advance(Duration.ofSeconds(1));
        assertFalse(store.find(QUEUE, "k1").isPresent());
    }

    @Test
    void lookingAReceiptUpDoesNotExtendItsDeadline() {
        StoredRetryReceipt saved = save(QUEUE, "k1").orElseThrow();

        clock.advance(Duration.ofHours(12));
        store.find(QUEUE, "k1");

        assertEquals(saved.expiresAt(), store.find(QUEUE, "k1").orElseThrow().expiresAt());
    }

    @Test
    void savingDropsReceiptsThatHaveExpired() {
        save(QUEUE, "old");
        clock.advance(Duration.ofHours(25));

        save(QUEUE, "new");

        assertFalse(backends.get(QUEUE).get("old").isPresent());
        assertTrue(backends.get(QUEUE).get("new").isPresent());
    }

    @Test
    void reusingAnExpiredTokenKeepsTheReceiptItSavesNow() {
        save(QUEUE, "k1");
        clock.advance(Duration.ofHours(25));

        save(QUEUE, "k1");

        assertTrue(store.find(QUEUE, "k1").isPresent(), "the sweep must not delete the receipt being saved");
    }

    @Test
    void eachServiceKeepsItsReceiptsInItsOwnStore() {
        save(QUEUE, "k1");

        assertTrue(store.find(QUEUE, "k1").isPresent());
        assertFalse(store.find("psql", "k1").isPresent());
        assertFalse(backends.containsKey(RetryTokenStore.DEFAULT_STORAGE_KEY));
    }

    @Test
    void deletingTheCreatedResourceInvalidatesOnlyTheReceiptsThatCreatedIt() {
        store.save(QUEUE, "created-q1", store.generation(), "fp", 202, null, Map.of(), null, "ocid1.queue.oc1.iad.q1");
        store.save(QUEUE, "created-q2", store.generation(), "fp", 202, null, Map.of(), null, "ocid1.queue.oc1.iad.q2");

        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.q1", null);

        assertTrue(store.find(QUEUE, "created-q1").orElseThrow().invalidated());
        assertFalse(store.find(QUEUE, "created-q2").orElseThrow().invalidated());
        assertEquals("ocid1.queue.oc1.iad.q1", store.find(QUEUE, "created-q1").orElseThrow().createdId());
    }

    @Test
    void aDeleteWithAWorkRequestInvalidatesOnlyOnceItSucceeded() {
        store.save(QUEUE, "k1", store.generation(), "fp", 202, null, Map.of(), null, "ocid1.queue.oc1.iad.q1");

        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.q1", "ocid1.wr.delete");
        StoredRetryReceipt pending = store.find(QUEUE, "k1").orElseThrow();
        assertFalse(pending.invalidated());
        assertEquals("ocid1.wr.delete", pending.deletionWorkRequestId());

        assertFalse(store.resolveDeletion(QUEUE, "k1", "IN_PROGRESS").invalidated(), "still running");
        assertTrue(store.resolveDeletion(QUEUE, "k1", "SUCCEEDED").invalidated());
    }

    @Test
    void aFailedDeleteLeavesTheTokenReplaying() {
        store.save(QUEUE, "k1", store.generation(), "fp", 202, null, Map.of(), null, "ocid1.queue.oc1.iad.q1");
        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.q1", "ocid1.wr.delete");

        StoredRetryReceipt settled = store.resolveDeletion(QUEUE, "k1", "FAILED");

        assertFalse(settled.invalidated());
        assertEquals(null, settled.deletionWorkRequestId());
    }

    @Test
    void aReceiptSavedAfterItsResourceWasDeletedIsSavedInvalidated() {
        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.raced", null);

        StoredRetryReceipt late = store.save(QUEUE, "k1", store.generation(), "fp", 202, null, Map.of(), null,
                "ocid1.queue.oc1.iad.raced").orElseThrow();

        assertTrue(late.invalidated(), "the delete finished before the create recorded its receipt");
    }

    @Test
    void aDeletedResourceIsRememberedOnlyBriefly() {
        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.old", null);
        clock.advance(RetryTokenStore.DELETED_MEMORY.plusSeconds(1));

        StoredRetryReceipt later = store.save(QUEUE, "k1", store.generation(), "fp", 202, null, Map.of(), null,
                "ocid1.queue.oc1.iad.old").orElseThrow();

        assertFalse(later.invalidated());
    }

    @Test
    void anInvalidatedReceiptStaysUntilItExpires() {
        store.save(QUEUE, "k1", store.generation(), "fp", 202, null, Map.of(), null, "ocid1.queue.oc1.iad.q1");
        store.deletedResource(QUEUE, "ocid1.queue.oc1.iad.q1", null);

        clock.advance(Duration.ofHours(23));
        assertTrue(store.find(QUEUE, "k1").orElseThrow().invalidated());

        clock.advance(Duration.ofHours(2));
        assertFalse(store.find(QUEUE, "k1").isPresent(), "an expired token behaves as new again");
    }

    @Test
    void aResetOpensEveryServicesStoreSoTheStorageWipeReachesIt() {
        assertTrue(backends.isEmpty(), "stores open lazily");

        store.clear();

        assertTrue(backends.keySet().containsAll(List.of(QUEUE, "psql", RetryTokenStore.DEFAULT_STORAGE_KEY)),
                () -> "opened " + backends.keySet());
    }

    @Test
    void aRequestThatBeganBeforeAResetCannotRecordItsReceipt() {
        long startedIn = store.generation();
        store.clear();

        Optional<StoredRetryReceipt> saved = store.save(QUEUE, "k1", startedIn, "fp", 200, null, Map.of(), null, null);

        assertFalse(saved.isPresent());
        assertFalse(store.find(QUEUE, "k1").isPresent());
        assertTrue(save(QUEUE, "k2").isPresent(), "a request begun after the reset records normally");
    }
}
