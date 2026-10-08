package io.floci.oci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
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
}
