package io.floci.oci.services.objectstorage;

import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectVersionsTest {

    private static final String PARTITION =
            TenancyAwareStorageBackend.regionalPartition("ocid1.tenancy.oc1..versions", "us-ashburn-1");

    private final InMemoryStorage<String, StoredOsObject> objects = new InMemoryStorage<>();
    private final InMemoryStorage<String, StoredOsObject> previous = new InMemoryStorage<>();
    private final ObjectVersions versions = new ObjectVersions(objects, previous);

    private static StoredOsObject object(String name, String etag, String versionId) {
        StoredOsObject o = new StoredOsObject();
        o.setName(name);
        o.setEtag(etag);
        o.setVersionId(versionId);
        o.setTimeCreated("2026-11-18T00:00:00Z");
        o.setTimeModified("2026-11-18T00:00:00Z");
        return o;
    }

    @Test
    void objectStoredBeforeVersionsGetsTheSameIdOnEveryReadWithoutAWrite() {
        TenancyAwareStorageBackend.putIn(objects, PARTITION, "b/legacy", object("legacy", "etag-1", null));

        String first = versions.latest(PARTITION, "b", "legacy").orElseThrow().getVersionId();
        String second = versions.latest(PARTITION, "b", "legacy").orElseThrow().getVersionId();

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(ObjectVersions.legacyVersionId(object("legacy", "etag-1", null)), first);
        assertTrue(versions.find(PARTITION, "b", "legacy", first).isPresent());
    }

    @Test
    void aVersionLeftInBothStoresByAnInterruptedMoveIsListedOnce() {
        StoredOsObject live = object("o", "etag-1", "v1");
        TenancyAwareStorageBackend.putIn(objects, PARTITION, "b/o", live);
        TenancyAwareStorageBackend.putIn(previous, PARTITION, "b/v1", object("o", "etag-1", "v1"));
        TenancyAwareStorageBackend.putIn(previous, PARTITION, "b/v0", object("o", "etag-0", "v0"));

        List<String> listed = versions.all(PARTITION, "b").stream().map(StoredOsObject::getVersionId).toList();
        assertEquals(2, listed.size());
        assertTrue(listed.containsAll(List.of("v0", "v1")));
    }

    @Test
    void deletingAVersionLeftInBothStoresRemovesBothCopies() {
        TenancyAwareStorageBackend.putIn(objects, PARTITION, "b/o", object("o", "etag-1", "v1"));
        TenancyAwareStorageBackend.putIn(previous, PARTITION, "b/v1", object("o", "etag-1", "v1"));

        assertTrue(versions.deleteVersion(PARTITION, "b", "o", "v1", "not found").isEmpty());
        assertTrue(versions.all(PARTITION, "b").isEmpty());
        assertFalse(versions.hasPrevious(PARTITION, "b"));
    }
}
