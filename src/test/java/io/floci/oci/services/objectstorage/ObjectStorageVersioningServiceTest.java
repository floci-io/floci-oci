package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.ObjectStorageService.ObjectDeletion;
import io.floci.oci.services.objectstorage.ObjectStorageService.VersionListing;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ObjectStorageVersioningServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";
    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..testcompartment";

    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultNamespace()).thenReturn(NS);
        service = new ObjectStorageService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), config,
                new WorkRequestService(new InMemoryStorage<>(), config));
    }

    private StoredBucket bucket(String name, String versioning) {
        return service.createBucket(NS, name, COMPARTMENT, null, null, null, null, null, null, versioning);
    }

    private StoredOsObject put(String bucket, String name, String content) {
        return service.putObject(NS, bucket, name, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", null, null, null, null);
    }

    private String content(String bucket, String name, String versionId) {
        return new String(service.getObject(NS, bucket, name, versionId).getData(), StandardCharsets.UTF_8);
    }

    private List<StoredOsObject> versions(String bucket) {
        return service.listObjectVersions(NS, bucket, null, null, null, null, null, null, null).items();
    }

    private static void assertError(int status, String code, Executable call) {
        OciException e = assertThrows(OciException.class, call);
        assertEquals(status, e.getHttpStatus());
        assertEquals(code, e.getCode());
    }

    @Test
    void bucketsDefaultToDisabledAndAcceptEnabled() {
        assertEquals("Disabled", bucket("plain", null).getVersioning());
        assertEquals("Enabled", bucket("versioned", "enabled").getVersioning());
        assertError(400, "InvalidParameter", () -> bucket("bad", "Suspended"));
    }

    @Test
    void versioningCanBeSuspendedButNotDisabled() {
        bucket("b", "Enabled");
        assertEquals("Suspended",
                service.updateBucket(NS, "b", null, null, null, null, null, "Suspended", null).getVersioning());
        assertEquals("Enabled",
                service.updateBucket(NS, "b", null, null, null, null, null, "Enabled", null).getVersioning());
        assertError(400, "InvalidParameter",
                () -> service.updateBucket(NS, "b", null, null, null, null, null, "Disabled", null));
    }

    @Test
    void everyObjectHasAVersionIdEvenWithoutVersioning() {
        bucket("plain", null);
        StoredOsObject first = put("plain", "o", "one");
        StoredOsObject second = put("plain", "o", "two");
        assertNotNull(first.getVersionId());
        assertNotEquals(first.getVersionId(), second.getVersionId());
        assertEquals(List.of(second.getVersionId()), versions("plain").stream().map(StoredOsObject::getVersionId).toList());
        assertError(404, "ObjectNotFound", () -> service.getObject(NS, "plain", "o", first.getVersionId()));
    }

    @Test
    void overwritingKeepsPreviousVersionsNewestFirst() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        StoredOsObject v2 = put("b", "o", "two");
        StoredOsObject v3 = put("b", "o", "three");

        assertEquals("three", content("b", "o", null));
        assertEquals("one", content("b", "o", v1.getVersionId()));
        assertEquals(List.of(v3.getVersionId(), v2.getVersionId(), v1.getVersionId()),
                versions("b").stream().map(StoredOsObject::getVersionId).toList());
        assertEquals(1, service.listObjects(NS, "b", null, null, null, null, null).objects().size());
    }

    @Test
    void deleteLeavesAMarkerAndDeletingTheMarkerRestoresTheObject() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");

        ObjectDeletion deletion = service.deleteObject(NS, "b", "o", null, null);
        assertNotNull(deletion.markerVersionId());
        assertError(404, "ObjectNotFound", () -> service.getObject(NS, "b", "o"));
        assertTrue(service.listObjects(NS, "b", null, null, null, null, null).objects().isEmpty());
        assertError(404, "ObjectNotFound", () -> service.getObject(NS, "b", "o", deletion.markerVersionId()));

        List<StoredOsObject> listed = versions("b");
        assertEquals(2, listed.size());
        assertTrue(listed.get(0).isDeleteMarker());
        assertEquals(v1.getVersionId(), listed.get(1).getVersionId());

        assertNull(service.deleteObject(NS, "b", "o", null, deletion.markerVersionId()).markerVersionId());
        assertEquals("one", content("b", "o", null));
        assertEquals(v1.getVersionId(), service.getObject(NS, "b", "o").getVersionId());
    }

    @Test
    void deletingTheLatestByVersionIdPromotesThePreviousVersion() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        StoredOsObject v2 = put("b", "o", "two");

        service.deleteObject(NS, "b", "o", null, v2.getVersionId());
        assertEquals("one", content("b", "o", null));

        service.deleteObject(NS, "b", "o", null, v1.getVersionId());
        assertTrue(versions("b").isEmpty());
        assertError(404, "ObjectNotFound", () -> service.deleteObject(NS, "b", "o", null, v1.getVersionId()));
    }

    @Test
    void deletingAPreviousVersionKeepsTheLatest() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        put("b", "o", "two");
        service.deleteObject(NS, "b", "o", null, v1.getVersionId());
        assertEquals("two", content("b", "o", null));
        assertEquals(1, versions("b").size());
    }

    @Test
    void suspendedBucketsOverwriteTheLatestAndKeepEarlierVersions() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        put("b", "o", "two");
        service.updateBucket(NS, "b", null, null, null, null, null, "Suspended", null);

        StoredOsObject v3 = put("b", "o", "three");
        assertEquals(List.of(v3.getVersionId(), v1.getVersionId()),
                versions("b").stream().map(StoredOsObject::getVersionId).toList());

        assertNull(service.deleteObject(NS, "b", "o", null, null).markerVersionId());
        assertEquals("one", content("b", "o", null));
    }

    @Test
    void bucketWithOnlyPreviousVersionsOrMarkersIsNotEmpty() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        String marker = service.deleteObject(NS, "b", "o", null, null).markerVersionId();
        assertError(409, "BucketNotEmpty", () -> service.deleteBucket(NS, "b", null));

        service.deleteObject(NS, "b", "o", null, v1.getVersionId());
        assertError(409, "BucketNotEmpty", () -> service.deleteBucket(NS, "b", null));

        service.deleteObject(NS, "b", "o", null, marker);
        service.deleteBucket(NS, "b", null);
    }

    @Test
    void renameMovesOnlyTheLatestAndLeavesAMarkerOnTheSource() {
        bucket("b", "Enabled");
        put("b", "src", "one");
        put("b", "src", "two");
        StoredOsObject renamed = service.renameObject(NS, "b", "src", "dst", null, null, null);

        assertEquals("two", content("b", "dst", null));
        assertNotNull(renamed.getVersionId());
        assertError(404, "ObjectNotFound", () -> service.getObject(NS, "b", "src"));
        List<StoredOsObject> sourceVersions = service.listObjectVersions(NS, "b", "src", null, null, null, null,
                null, null).items();
        assertEquals(3, sourceVersions.size());
        assertTrue(sourceVersions.get(0).isDeleteMarker());
    }

    @Test
    void copyOfAPreviousVersionBecomesTheLatestAtTheDestination() {
        bucket("b", "Enabled");
        StoredOsObject v1 = put("b", "o", "one");
        put("b", "o", "two");
        service.copyObject(NS, "b", "o", "us-ashburn-1", NS, "b", "copy", null, v1.getVersionId());
        assertEquals("one", content("b", "copy", null));
    }

    @Test
    void aCopyReportsTheVersionItWroteEvenAfterALaterWrite() {
        bucket("b", "Enabled");
        put("b", "o", "one");

        StoredOsObject copied = service.copy(NS, "b", "o", "us-ashburn-1", NS, "b", "copy", null, null).object();
        StoredOsObject later = put("b", "copy", "two");

        assertNotNull(copied.getVersionId());
        assertNotEquals(later.getVersionId(), copied.getVersionId());
        assertEquals("one", content("b", "copy", copied.getVersionId()));
    }

    @Test
    void listingFiltersGroupsAndPagesWithoutSkippingAfterDeletes() {
        bucket("b", "Enabled");
        put("b", "a", "1");
        put("b", "a", "2");
        put("b", "dir/x", "3");
        put("b", "dir/y", "4");
        put("b", "z", "5");

        VersionListing grouped = service.listObjectVersions(NS, "b", null, null, null, null, "/", null, null);
        assertEquals(List.of("dir/"), grouped.prefixes());
        assertEquals(List.of("a", "a", "z"), grouped.items().stream().map(StoredOsObject::getName).toList());

        VersionListing first = service.listObjectVersions(NS, "b", null, null, null, null, null, 2, null);
        assertEquals(List.of("a", "a"), first.items().stream().map(StoredOsObject::getName).toList());
        assertNotNull(first.nextPage());

        for (StoredOsObject version : first.items()) {
            service.deleteObject(NS, "b", version.getName(), null, version.getVersionId());
        }
        VersionListing second = service.listObjectVersions(NS, "b", null, null, null, null, null, 2,
                first.nextPage());
        assertEquals(List.of("dir/x", "dir/y"), second.items().stream().map(StoredOsObject::getName).toList());

        VersionListing third = service.listObjectVersions(NS, "b", null, null, null, null, null, 2,
                second.nextPage());
        assertEquals(List.of("z"), third.items().stream().map(StoredOsObject::getName).toList());
        assertNull(third.nextPage());

        assertEquals(List.of("z"), service.listObjectVersions(NS, "b", null, null, null, "dir/y", null, null, null)
                .items().stream().map(StoredOsObject::getName).toList());
        assertError(400, "InvalidParameter",
                () -> service.listObjectVersions(NS, "b", null, null, null, null, null, null, "not-a-token"));
    }
}
