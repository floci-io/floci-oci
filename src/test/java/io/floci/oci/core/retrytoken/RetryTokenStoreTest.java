package io.floci.oci.core.retrytoken;

import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryTokenStoreTest {

    private InMemoryStorage<String, StoredRetryReceipt> receipts;
    private MutableClock clock;
    private RetryTokenStore store;

    @BeforeEach
    void setUp() {
        receipts = new InMemoryStorage<>();
        clock = new MutableClock();
        store = new RetryTokenStore(receipts, clock);
    }

    @Test
    void aSavedReceiptIsFoundWithEverythingItRecorded() {
        store.save("k1", "fp", 202, null, Map.of("opc-work-request-id", List.of("ocid1.wr")), null);

        StoredRetryReceipt receipt = store.find("k1").orElseThrow();
        assertEquals("fp", receipt.fingerprint());
        assertEquals(202, receipt.status());
        assertEquals(List.of("ocid1.wr"), receipt.headers().get("opc-work-request-id"));
        assertEquals(receipt.createdAt().plus(RetryTokenStore.TTL), receipt.expiresAt());
    }

    @Test
    void aReceiptLastsTwentyFourHoursAndThenExpires() {
        store.save("k1", "fp", 200, "application/json", Map.of(), "e30=");

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertTrue(store.find("k1").isPresent());

        clock.advance(Duration.ofSeconds(1));
        assertFalse(store.find("k1").isPresent());
    }

    @Test
    void lookingAReceiptUpDoesNotExtendItsDeadline() {
        StoredRetryReceipt saved = store.save("k1", "fp", 200, null, Map.of(), null);

        clock.advance(Duration.ofHours(12));
        store.find("k1");

        assertEquals(saved.expiresAt(), store.find("k1").orElseThrow().expiresAt());
    }

    @Test
    void savingDropsReceiptsThatHaveExpired() {
        store.save("old", "fp", 200, null, Map.of(), null);
        clock.advance(Duration.ofHours(25));

        store.save("new", "fp", 200, null, Map.of(), null);

        assertFalse(receipts.get("old").isPresent());
        assertTrue(receipts.get("new").isPresent());
    }
}
