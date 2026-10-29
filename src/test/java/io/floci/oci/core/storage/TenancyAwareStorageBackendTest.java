package io.floci.oci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenancyAwareStorageBackendTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..t";

    @Test
    void partitionsKeepRegionsApart() {
        AtomicReference<String> region = new AtomicReference<>("us-ashburn-1");
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":" + region.get());

        store.put("q1", "ashburn");
        region.set("us-phoenix-1");
        store.put("q2", "phoenix");

        assertTrue(store.get("q1").isEmpty());
        assertEquals(List.of("phoenix"), store.scan(k -> true));
        region.set("us-ashburn-1");
        assertEquals("ashburn", store.get("q1").orElseThrow());
    }

    @Test
    void legacyTenancyKeysMoveIntoTheGivenRegion() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put(TENANCY + "/q1", "legacy");
        raw.put(TENANCY + ":us-phoenix-1/q2", "already regional");
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                raw, () -> TENANCY + ":us-ashburn-1");

        assertEquals(1, store.migrateToRegion(TENANCY, "us-ashburn-1"));

        assertEquals("legacy", store.get("q1").orElseThrow());
        assertTrue(raw.get(TENANCY + "/q1").isEmpty());
        assertEquals("already regional", raw.get(TENANCY + ":us-phoenix-1/q2").orElseThrow());
        assertEquals(0, store.migrateToRegion(TENANCY, "us-ashburn-1"));
    }

    @Test
    void bareLegacyKeysMoveIntoTheDefaultTenancyAndRegion() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("bucket/object", "pre-tenancy");
        AtomicReference<String> region = new AtomicReference<>("us-phoenix-1");
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                raw, () -> TENANCY + ":" + region.get());

        assertEquals(1, store.migrateToRegion(TENANCY, "us-ashburn-1"));

        assertTrue(store.get("bucket/object").isEmpty(), "a Phoenix read must not claim it");
        region.set("us-ashburn-1");
        assertEquals("pre-tenancy", store.get("bucket/object").orElseThrow());
    }

    @Test
    void getInAndPutInReachAnotherPartition() {
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        String phoenix = TenancyAwareStorageBackend.regionalPartition(TENANCY, "us-phoenix-1");

        TenancyAwareStorageBackend.putIn(store, phoenix, "q1", "phoenix");

        assertTrue(store.get("q1").isEmpty());
        assertEquals("phoenix", TenancyAwareStorageBackend.getIn(store, phoenix, "q1").orElseThrow());
    }

    @Test
    void getInAndPutInTreatAPlainStoreAsOnePartition() {
        InMemoryStorage<String, String> plain = new InMemoryStorage<>();

        TenancyAwareStorageBackend.putIn(plain, "ignored", "q1", "value");

        assertEquals("value", plain.get("q1").orElseThrow());
        assertEquals("value", TenancyAwareStorageBackend.getIn(plain, "other", "q1").orElseThrow());
    }

    @Test
    void findInRegionLooksAcrossTenanciesButNotAcrossRegions() {
        String other = "ocid1.tenancy.oc1..other";
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        TenancyAwareStorageBackend.putIn(store, other + ":us-ashburn-1", "token", "ashburn");
        TenancyAwareStorageBackend.putIn(store, other + ":us-phoenix-1", "elsewhere", "phoenix");

        TenancyAwareStorageBackend.Owned<String> found = TenancyAwareStorageBackend
                .findInRegion(store, "us-ashburn-1", "token", TENANCY).orElseThrow();

        assertEquals(other, found.tenancyId());
        assertEquals("ashburn", found.value());
        assertTrue(TenancyAwareStorageBackend.findInRegion(store, "us-ashburn-1", "elsewhere", TENANCY).isEmpty());
    }

    @Test
    void regionOfReadsTheRegionOfARegionalPartition() {
        assertEquals(Optional.of("us-phoenix-1"), TenancyAwareStorageBackend.regionOf(
                TenancyAwareStorageBackend.regionalPartition(TENANCY, "us-phoenix-1")));
        assertTrue(TenancyAwareStorageBackend.regionOf(TENANCY).isEmpty());
    }

    @Test
    void putAllInAndDeleteAllInChangeOnlyTheGivenPartition() {
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        String phoenix = TenancyAwareStorageBackend.regionalPartition(TENANCY, "us-phoenix-1");
        store.put("q1", "ashburn");

        TenancyAwareStorageBackend.putAllIn(store, phoenix, Map.of("q1", "phoenix", "q2", "phoenix"));
        TenancyAwareStorageBackend.deleteAllIn(store, phoenix, List.of("q1"));

        assertEquals("ashburn", store.get("q1").orElseThrow());
        assertEquals(Set.of("q2"), store.keysForTenancy(phoenix));
    }

    @Test
    void putAllInAndDeleteAllInTreatAPlainStoreAsOnePartition() {
        InMemoryStorage<String, String> plain = new InMemoryStorage<>();

        TenancyAwareStorageBackend.putAllIn(plain, "ignored", Map.of("q1", "a", "q2", "b"));
        TenancyAwareStorageBackend.deleteAllIn(plain, "ignored", List.of("q1"));

        assertEquals(Set.of("q2"), plain.keys());
    }

    @Test
    void scanAllReadsEveryPartition() {
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        store.put("q1", "ashburn");
        TenancyAwareStorageBackend.putIn(store, "ocid1.tenancy.oc1..other:us-phoenix-1", "q2", "phoenix");
        InMemoryStorage<String, String> plain = new InMemoryStorage<>();
        plain.put("q1", "plain");

        assertEquals(Set.of("ashburn", "phoenix"), Set.copyOf(TenancyAwareStorageBackend.scanAll(store)));
        assertEquals(List.of("plain"), TenancyAwareStorageBackend.scanAll(plain));
    }

    @Test
    void findInTenancyLooksAcrossRegionsButNotAcrossTenancies() {
        String other = "ocid1.tenancy.oc1..other";
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        TenancyAwareStorageBackend.putIn(store, TENANCY + ":us-phoenix-1", "cluster", "phoenix");
        TenancyAwareStorageBackend.putIn(store, other + ":us-ashburn-1", "foreign", "other");

        assertEquals("phoenix", TenancyAwareStorageBackend.findInTenancy(store, TENANCY, "cluster").orElseThrow());
        assertTrue(TenancyAwareStorageBackend.findInTenancy(store, TENANCY, "foreign").isEmpty());
    }

    @Test
    void findInTenancyTreatsAPlainStoreAsOnePartition() {
        InMemoryStorage<String, String> plain = new InMemoryStorage<>();
        plain.put("cluster", "value");

        assertEquals("value", TenancyAwareStorageBackend.findInTenancy(plain, TENANCY, "cluster").orElseThrow());
    }

    @Test
    void entriesByPartitionGroupsEntriesByPartition() {
        TenancyAwareStorageBackend<String> store = new TenancyAwareStorageBackend<>(
                new InMemoryStorage<>(), () -> TENANCY + ":us-ashburn-1");
        store.put("q1", "ashburn");
        TenancyAwareStorageBackend.putIn(store, TENANCY + ":us-phoenix-1", "q2", "phoenix");

        assertEquals(Map.of(TENANCY + ":us-ashburn-1", Map.of("q1", "ashburn"),
                        TENANCY + ":us-phoenix-1", Map.of("q2", "phoenix")),
                TenancyAwareStorageBackend.entriesByPartition(store));
    }

    @Test
    void entriesByPartitionOfAPlainStoreIsEmptyUnlessASolePartitionIsNamed() {
        InMemoryStorage<String, String> plain = new InMemoryStorage<>();
        plain.put("q1", "value");

        assertTrue(TenancyAwareStorageBackend.entriesByPartition(plain).isEmpty());
        assertEquals(Map.of("sole", Map.of("q1", "value")),
                TenancyAwareStorageBackend.entriesByPartition(plain, "sole"));
        assertTrue(TenancyAwareStorageBackend.entriesByPartition(new InMemoryStorage<String, String>(), "sole")
                .isEmpty());
    }
}
