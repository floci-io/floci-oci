package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredPar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.floci.oci.services.objectstorage.ObjectStorageController.batchDeleteItems;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ObjectStorageServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";
    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..testcompartment";

    private ObjectStorageService service;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultNamespace()).thenReturn(NS);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new ObjectStorageService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), config, workRequests);
    }

    private StoredBucket bucket(String name) {
        return service.createBucket(NS, name, COMPARTMENT, null, null, null, null, null);
    }

    private StoredOsObject put(String bucket, String name, String content) {
        return service.putObject(NS, bucket, name, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", null, null, null, null);
    }

    @Test
    void bucketLifecycle() {
        StoredBucket b = bucket("b1");
        assertEquals(NS, b.getNamespace());
        assertEquals("NoPublicAccess", b.getPublicAccessType());
        assertEquals("Standard", b.getStorageTier());
        assertTrue(b.getId().startsWith("ocid1.bucket.oc1.iad."));
        assertNotNull(b.getEtag());

        assertThrows(OciException.class, () -> bucket("b1"));

        assertEquals(1, service.listBuckets(NS, COMPARTMENT).size());
        service.deleteBucket(NS, "b1", null);
        assertThrows(OciException.class, () -> service.getBucket(NS, "b1"));
    }

    @Test
    void bucketNotFoundUsesBucketNotFoundCode() {
        OciException e = assertThrows(OciException.class, () -> service.getBucket(NS, "missing"));
        assertEquals("BucketNotFound", e.getCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void deleteNonEmptyBucketIsConflict() {
        bucket("full");
        put("full", "o1", "data");
        OciException e = assertThrows(OciException.class,
                () -> service.deleteBucket(NS, "full", null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void putGetDeleteObjectWithMd5() {
        bucket("data");
        StoredOsObject o = put("data", "hello.txt", "hello world");
        assertEquals(ObjectStorageService.md5Base64("hello world".getBytes(StandardCharsets.UTF_8)),
                o.getMd5());

        StoredOsObject fetched = service.getObject(NS, "data", "hello.txt");
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), fetched.getData());

        service.deleteObject(NS, "data", "hello.txt", null);
        OciException e = assertThrows(OciException.class,
                () -> service.getObject(NS, "data", "hello.txt"));
        assertEquals("ObjectNotFound", e.getCode());
    }

    public static Stream<Arguments> invalidBatchDeleteItems() {
        return Stream.of(
                argumentSet("invalid objects",
                        new HashMap<String, Object>() {
                            {
                                put("namespaceName", NS);
                                put("bucketName", "data");
                                put("objects", List.of("foo"));
                            }}
                ),
                argumentSet("invalid objects - not a list",
                        new HashMap<String, Object>() {
                            {
                                put("namespaceName", NS);
                                put("bucketName", "data");
                                put("objects", "not a list");
                            }}
                ),
                argumentSet("missing object name",
                        new HashMap<String, Object>() {{
                            put("namespaceName", NS);
                            put("bucketName", "data");
                            put("objects", List.of(
                                    Map.of("objectName", "hello.txt"),
                                    Map.of("name", "world.txt")
                            ));
                        }}
                ),
                argumentSet("invalid ifMatch",
                        new HashMap<String, Object>() {{
                            put("namespaceName", NS);
                            put("bucketName", "data");
                            put("objects", List.of(
                                    Map.of("objectName", "hello.txt", "ifMatch", 2137)
                            ));
                        }}
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidBatchDeleteItems")
    @NullAndEmptySource
    void test_batchDeleteItems_with_invalid_list(Map<String, Object> invalidObjects) {
        assertThrows(OciException.class, () -> batchDeleteItems(invalidObjects));
    }

    @Test
    void contentMd5MismatchIsRejected() {
        bucket("md5");
        OciException e = assertThrows(OciException.class,
                () -> service.putObject(NS, "md5", "x", "abc".getBytes(StandardCharsets.UTF_8),
                        null, "bm90LXRoZS1yaWdodC1tZDU=", null, null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void ifNoneMatchStarPreventsOverwrite() {
        bucket("cond");
        put("cond", "o", "v1");
        OciException e = assertThrows(OciException.class,
                () -> service.putObject(NS, "cond", "o", "v2".getBytes(StandardCharsets.UTF_8),
                        null, null, null, null, "*"));
        assertEquals(412, e.getHttpStatus());
    }

    @Test
    void listObjectsPrefixDelimiterAndTruncation() {
        bucket("list");
        put("list", "a/1.txt", "x");
        put("list", "a/2.txt", "x");
        put("list", "b/3.txt", "x");
        put("list", "top.txt", "x");

        var all = service.listObjects(NS, "list", null, null, null, null, null);
        assertEquals(4, all.objects().size());
        assertNull(all.nextStartWith());

        var grouped = service.listObjects(NS, "list", null, null, null, "/", null);
        assertEquals(List.of("a/", "b/"), grouped.prefixes());
        assertEquals(1, grouped.objects().size());
        assertEquals("top.txt", grouped.objects().get(0).getName());

        var prefixed = service.listObjects(NS, "list", "a/", null, null, null, null);
        assertEquals(2, prefixed.objects().size());

        var truncated = service.listObjects(NS, "list", null, null, null, null, 2);
        assertEquals(2, truncated.objects().size());
        assertEquals("b/3.txt", truncated.nextStartWith());

        var resumed = service.listObjects(NS, "list", null, truncated.nextStartWith(), null, null, null);
        assertEquals(2, resumed.objects().size());
    }

    @Test
    void renameObjectMovesData() {
        bucket("mv");
        put("mv", "old", "content");
        StoredOsObject renamed = service.renameObject(NS, "mv", "old", "new", null, null, null);
        assertEquals("new", renamed.getName());
        assertThrows(OciException.class, () -> service.getObject(NS, "mv", "old"));
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8),
                service.getObject(NS, "mv", "new").getData());
    }

    @Test
    void copyObjectCompletesViaWorkRequest() {
        bucket("src");
        bucket("dst");
        put("src", "o", "payload");
        String workRequestId = service.copyObject(NS, "src", "o", NS, "dst", "o-copy", null);
        assertEquals("COMPLETED", workRequests.get(workRequestId).getStatus());
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8),
                service.getObject(NS, "dst", "o-copy").getData());
    }

    @Test
    void multipartUploadAssemblesPartsInOrder() {
        bucket("mp");
        StoredMultipartUpload u = service.createMultipartUpload(NS, "mp", "big.bin", null, null);
        String etag2 = service.uploadPart(NS, "mp", "big.bin", u.getUploadId(), 2, "world".getBytes()).etag();
        String etag1 = service.uploadPart(NS, "mp", "big.bin", u.getUploadId(), 1, "hello ".getBytes()).etag();

        StoredOsObject o = service.commitMultipartUpload(NS, "mp", "big.bin", u.getUploadId(),
                List.of(Map.of("partNum", 2, "etag", etag2), Map.of("partNum", 1, "etag", etag1)));
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), o.getData());
        assertTrue(service.listMultipartUploads(NS, "mp").isEmpty());
    }

    @Test
    void commitWithWrongEtagFails() {
        bucket("mp2");
        StoredMultipartUpload u = service.createMultipartUpload(NS, "mp2", "x", null, null);
        service.uploadPart(NS, "mp2", "x", u.getUploadId(), 1, "data".getBytes());
        assertThrows(OciException.class,
                () -> service.commitMultipartUpload(NS, "mp2", "x", u.getUploadId(),
                        List.of(Map.of("partNum", 1, "etag", "wrong"))));
    }

    @Test
    void abortDiscardsUpload() {
        bucket("mp3");
        StoredMultipartUpload u = service.createMultipartUpload(NS, "mp3", "x", null, null);
        service.abortMultipartUpload(NS, "mp3", "x", u.getUploadId());
        assertThrows(OciException.class,
                () -> service.uploadPart(NS, "mp3", "x", u.getUploadId(), 1, "d".getBytes()));
    }

    @Test
    void parLifecycleAndExpiry() {
        bucket("par");
        put("par", "secret.txt", "s3cret");
        StoredPar par = service.createPar(NS, "par", "read-par", "ObjectRead",
                Instant.now().plusSeconds(3600).toString(), "secret.txt", null);
        assertNotNull(par.getToken());

        StoredPar resolved = service.resolveParToken(par.getToken());
        assertEquals("par", resolved.getBucket());

        assertEquals(1, service.listPars(NS, "par").size());
        service.deletePar(NS, "par", par.getId());
        assertThrows(OciException.class, () -> service.resolveParToken(par.getToken()));
    }

    @Test
    void expiredParIsRejected() {
        bucket("par2");
        StoredPar par = service.createPar(NS, "par2", "old", "ObjectRead",
                Instant.now().minusSeconds(60).toString(), null, null);
        OciException e = assertThrows(OciException.class,
                () -> service.resolveParToken(par.getToken()));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void namespaceIsConfigured() {
        assertEquals(NS, service.namespace());
        assertEquals(NS, service.namespaceMetadata(NS).get("namespace"));
    }
}
