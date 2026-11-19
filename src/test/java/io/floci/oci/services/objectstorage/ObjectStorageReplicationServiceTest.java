package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.ObjectStorageService.BatchDeleteItem;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredReplicationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Replication policies across two regions: every store is partitioned by the request region, which
 * the test switches the way a regional endpoint would.
 */
class ObjectStorageReplicationServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";
    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..testcompartment";
    private static final String ASHBURN = "us-ashburn-1";
    private static final String PHOENIX = "us-phoenix-1";

    private String region = ASHBURN;
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn(ASHBURN);
        lenient().when(config.defaultNamespace()).thenReturn(NS);
        OciContext context = new OciContext(config, null) {
            @Override
            public String region() {
                return region;
            }
        };
        Supplier<String> partition = () -> TenancyAwareStorageBackend.regionalPartition(TENANCY, region);
        service = new ObjectStorageService(
                new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), partition),
                new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), partition),
                new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), partition),
                new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), partition),
                new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), partition),
                config, new WorkRequestService(new InMemoryStorage<>(), config), context);
    }

    private void in(String regionName) {
        region = regionName;
    }

    private void bucket(String regionName, String name) {
        in(regionName);
        service.createBucket(NS, name, COMPARTMENT, null, null, null, null, null, null);
    }

    private StoredOsObject put(String bucket, String name, String content) {
        return service.putObject(NS, bucket, name, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", null, Map.of("owner", "test"), null, null);
    }

    /** Source {@code src} in Ashburn replicating to {@code dst} in Phoenix; leaves the request in Ashburn. */
    private StoredReplicationPolicy replicateAshburnToPhoenix() {
        bucket(PHOENIX, "dst");
        bucket(ASHBURN, "src");
        return service.createReplicationPolicy(NS, "src", "to-phoenix", PHOENIX, "dst");
    }

    private StoredBucket bucketIn(String regionName, String name) {
        in(regionName);
        return service.getBucket(NS, name);
    }

    private boolean existsIn(String regionName, String bucket, String object) {
        in(regionName);
        try {
            service.getObject(NS, bucket, object);
            return true;
        } catch (OciException e) {
            return false;
        }
    }

    private static void assertError(int status, String code, Executable call) {
        OciException e = assertThrows(OciException.class, call);
        assertEquals(status, e.getHttpStatus(), e.getMessage());
        assertEquals(code, e.getCode(), e.getMessage());
    }

    // ── Policies ───────────────────────────────────────────────────────────────

    @Test
    void createPolicy_marksBothBucketsAndStartsWithNoSync() {
        StoredReplicationPolicy policy = replicateAshburnToPhoenix();

        assertDoesNotThrow(() -> UUID.fromString(policy.getId()));
        assertEquals("ACTIVE", policy.getStatus());
        assertEquals("The policy is active.", policy.getStatusMessage());
        assertNull(policy.getTimeLastSync());
        assertNotNull(policy.getTimeCreated());
        assertTrue(bucketIn(ASHBURN, "src").getReplicationEnabled());
        assertFalse(bucketIn(ASHBURN, "src").getIsReadOnly());
        assertTrue(bucketIn(PHOENIX, "dst").getIsReadOnly());
        assertFalse(bucketIn(PHOENIX, "dst").getReplicationEnabled());

        in(ASHBURN);
        assertEquals(List.of(policy.getId()),
                service.listReplicationPolicies(NS, "src").stream().map(StoredReplicationPolicy::getId).toList());
        assertEquals("to-phoenix", service.getReplicationPolicy(NS, "src", policy.getId()).getName());
        in(PHOENIX);
        StoredReplicationPolicy source = service.listReplicationSources(NS, "dst").get(0);
        assertEquals(ASHBURN, source.getSourceRegionName());
        assertEquals("src", source.getSourceBucketName());
    }

    @Test
    void createPolicy_withinOneRegion() {
        bucket(ASHBURN, "dst");
        bucket(ASHBURN, "src");
        service.createReplicationPolicy(NS, "src", "local", ASHBURN, "dst");
        put("src", "a.txt", "same region");

        assertTrue(existsIn(ASHBURN, "dst", "a.txt"));
    }

    @Test
    void createPolicy_rejectsASecondPolicyOnTheSource() {
        replicateAshburnToPhoenix();
        bucket(PHOENIX, "dst2");
        in(ASHBURN);

        OciException e = assertThrows(OciException.class,
                () -> service.createReplicationPolicy(NS, "src", "again", PHOENIX, "dst2"));
        assertEquals("ReplicationPolicyConflict", e.getCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Replication policy exists for bucket 'src' in namespace 'floci-test'.", e.getMessage());
    }

    @Test
    void createPolicy_rejectsChainingAndASecondSource() {
        replicateAshburnToPhoenix();
        bucket(PHOENIX, "onward");
        bucket(ASHBURN, "other");

        in(PHOENIX);
        assertError(409, "ReplicationPolicyConflict",
                () -> service.createReplicationPolicy(NS, "dst", "chain", PHOENIX, "onward"));
        in(ASHBURN);
        assertError(409, "ReplicationPolicyConflict",
                () -> service.createReplicationPolicy(NS, "other", "second-source", PHOENIX, "dst"));
        bucket(ASHBURN, "third");
        in(ASHBURN);
        assertError(409, "ReplicationPolicyConflict",
                () -> service.createReplicationPolicy(NS, "third", "into-a-source", ASHBURN, "src"));
    }

    @Test
    void createPolicy_validatesItsInput() {
        bucket(ASHBURN, "src");
        assertError(400, "MissingParameter", () -> service.createReplicationPolicy(NS, "src", null, PHOENIX, "dst"));
        assertError(400, "MissingParameter", () -> service.createReplicationPolicy(NS, "src", "p", null, "dst"));
        assertError(400, "MissingParameter", () -> service.createReplicationPolicy(NS, "src", "p", PHOENIX, null));
        assertError(404, "BucketNotFound", () -> service.createReplicationPolicy(NS, "src", "p", PHOENIX, "missing"));
        assertError(400, "InvalidParameter", () -> service.createReplicationPolicy(NS, "src", "p", ASHBURN, "src"));
        assertError(404, "BucketNotFound", () -> service.createReplicationPolicy(NS, "nope", "p", PHOENIX, "dst"));
        assertError(400, "InvalidParameter", () -> service.createReplicationPolicy(NS, "src", "p", "xx-nowhere-1", "dst"));
    }

    @Test
    void getPolicy_unknownIdIsNotFound() {
        replicateAshburnToPhoenix();
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getReplicationPolicy(NS, "src", "no-such-id"));
        assertError(404, "NotAuthorizedOrNotFound", () -> service.deleteReplicationPolicy(NS, "src", "no-such-id"));
    }

    @Test
    void deletePolicy_restoresBothBucketsAndStopsReplicating() {
        StoredReplicationPolicy policy = replicateAshburnToPhoenix();
        service.deleteReplicationPolicy(NS, "src", policy.getId());

        assertFalse(bucketIn(ASHBURN, "src").getReplicationEnabled());
        assertFalse(bucketIn(PHOENIX, "dst").getIsReadOnly());
        in(ASHBURN);
        assertTrue(service.listReplicationPolicies(NS, "src").isEmpty());
        put("src", "after.txt", "not replicated");
        assertFalse(existsIn(PHOENIX, "dst", "after.txt"));
        in(PHOENIX);
        assertTrue(service.listReplicationSources(NS, "dst").isEmpty());
    }

    // ── Mirroring ──────────────────────────────────────────────────────────────

    @Test
    void put_isReplicatedWithTheSameEtagMd5AndMetadata() {
        StoredReplicationPolicy policy = replicateAshburnToPhoenix();
        StoredOsObject written = put("src", "a.txt", "hello");

        in(PHOENIX);
        StoredOsObject replica = service.getObject(NS, "dst", "a.txt");
        assertArrayEquals(written.getData(), replica.getData());
        assertEquals(written.getEtag(), replica.getEtag());
        assertEquals(written.getMd5(), replica.getMd5());
        assertEquals("text/plain", replica.getContentType());
        assertEquals(Map.of("owner", "test"), replica.getMetadata());
        in(ASHBURN);
        assertNotNull(service.getReplicationPolicy(NS, "src", policy.getId()).getTimeLastSync());
    }

    @Test
    void objectsWrittenBeforeThePolicyAreNotReplicated() {
        bucket(PHOENIX, "dst");
        bucket(ASHBURN, "src");
        put("src", "old.txt", "before");
        service.createReplicationPolicy(NS, "src", "p", PHOENIX, "dst");

        assertFalse(existsIn(PHOENIX, "dst", "old.txt"));
    }

    @Test
    void overwriteAndDelete_areReplicated() {
        replicateAshburnToPhoenix();
        put("src", "a.txt", "one");
        StoredOsObject second = put("src", "a.txt", "two");
        in(PHOENIX);
        assertEquals(second.getEtag(), service.getObject(NS, "dst", "a.txt").getEtag());

        in(ASHBURN);
        service.deleteObject(NS, "src", "a.txt", null);
        assertFalse(existsIn(PHOENIX, "dst", "a.txt"));
    }

    @Test
    void batchDelete_isReplicated() {
        replicateAshburnToPhoenix();
        put("src", "a", "1");
        put("src", "b", "2");
        service.batchDeleteObjects(NS, "src", List.of(new BatchDeleteItem("a", null)));

        assertFalse(existsIn(PHOENIX, "dst", "a"));
        assertTrue(existsIn(PHOENIX, "dst", "b"));
    }

    @Test
    void rename_isReplicatedAsAPutAndADelete() {
        replicateAshburnToPhoenix();
        put("src", "old", "content");
        service.renameObject(NS, "src", "old", "new", null, null, null);

        assertFalse(existsIn(PHOENIX, "dst", "old"));
        assertTrue(existsIn(PHOENIX, "dst", "new"));
    }

    @Test
    void multipartCommit_isReplicatedWithItsMultipartMd5() {
        replicateAshburnToPhoenix();
        String uploadId = service.createMultipartUpload(NS, "src", "big", null, null).getUploadId();
        String etag = service.uploadPart(NS, "src", "big", uploadId, 1,
                "part".getBytes(StandardCharsets.UTF_8)).etag();
        StoredOsObject committed = service.commitMultipartUpload(NS, "src", "big", uploadId,
                List.of(Map.of("partNum", 1, "etag", etag)));

        in(PHOENIX);
        assertEquals(committed.getMultipartMd5(), service.getObject(NS, "dst", "big").getMultipartMd5());
    }

    @Test
    void copyIntoASourceBucket_isReplicated() {
        replicateAshburnToPhoenix();
        bucket(ASHBURN, "plain");
        put("plain", "seed", "copied");
        service.copyObject(NS, "plain", "seed", ASHBURN, NS, "src", "seed-copy", null);

        assertTrue(existsIn(PHOENIX, "dst", "seed-copy"));
    }

    // ── The read-only destination ──────────────────────────────────────────────

    @Test
    void destination_refusesEveryClientWrite() {
        replicateAshburnToPhoenix();
        put("src", "replicated", "x");
        in(PHOENIX);

        assertError(409, "Conflict", () -> put("dst", "direct", "x"));
        assertError(409, "Conflict", () -> service.deleteObject(NS, "dst", "replicated", null));
        assertError(409, "Conflict", () -> service.batchDeleteObjects(NS, "dst",
                List.of(new BatchDeleteItem("replicated", null))));
        assertError(409, "Conflict", () -> service.renameObject(NS, "dst", "replicated", "moved", null, null, null));
        assertError(409, "Conflict", () -> service.createMultipartUpload(NS, "dst", "big", null, null));
        assertError(409, "Conflict", () -> service.deleteBucket(NS, "dst", null));
        in(ASHBURN);
        assertError(409, "Conflict", () -> service.copyObject(NS, "src", "replicated", PHOENIX, NS, "dst", "c", null));
    }

    @Test
    void sourceBucket_cannotBeDeletedWhileItHasAPolicy() {
        replicateAshburnToPhoenix();
        put("src", "a", "x");

        OciException e = assertThrows(OciException.class, () -> service.deleteBucket(NS, "src", null));
        assertEquals(403, e.getHttpStatus());
        assertEquals("BucketReplicationEnabled", e.getCode());
        assertEquals("Bucket named 'src' has replication enabled. ", e.getMessage());
    }

    @Test
    void makeBucketWritable_stopsReplicationAndLeavesTheSourcePolicyInClientError() {
        StoredReplicationPolicy policy = replicateAshburnToPhoenix();
        in(PHOENIX);
        service.makeBucketWritable(NS, "dst");

        assertFalse(bucketIn(PHOENIX, "dst").getIsReadOnly());
        assertTrue(service.listReplicationSources(NS, "dst").isEmpty());
        assertDoesNotThrow(() -> put("dst", "direct", "now writable"));

        in(ASHBURN);
        StoredReplicationPolicy stopped = service.getReplicationPolicy(NS, "src", policy.getId());
        assertEquals("CLIENT_ERROR", stopped.getStatus());
        assertTrue(bucketIn(ASHBURN, "src").getReplicationEnabled());
        put("src", "after", "not replicated");
        assertFalse(existsIn(PHOENIX, "dst", "after"));

        in(ASHBURN);
        service.deleteReplicationPolicy(NS, "src", policy.getId());
        assertFalse(bucketIn(ASHBURN, "src").getReplicationEnabled());
        service.deleteObject(NS, "src", "after", null);
        assertDoesNotThrow(() -> service.deleteBucket(NS, "src", null));
    }

    @Test
    void makeBucketWritable_onABucketThatIsNotADestinationIsAConflict() {
        bucket(ASHBURN, "plain");
        assertError(409, "Conflict", () -> service.makeBucketWritable(NS, "plain"));
    }

    // ── Concurrency ────────────────────────────────────────────────────────────

    /** Runs {@code calls} at once and returns how many succeeded; every failure must be a policy conflict. */
    private static int succeededTogether(List<Runnable> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Runnable call : calls) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        call.run();
                        return true;
                    } catch (OciException e) {
                        assertEquals("ReplicationPolicyConflict", e.getCode(), e.getMessage());
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            return succeeded;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreates_onOneSourceLetExactlyOneThrough() throws Exception {
        bucket(ASHBURN, "src");
        List<Runnable> creates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String destination = "dst-" + i;
            bucket(ASHBURN, destination);
            creates.add(() -> service.createReplicationPolicy(NS, "src", "p", ASHBURN, destination));
        }

        assertEquals(1, succeededTogether(creates));
        assertEquals(1, service.listReplicationPolicies(NS, "src").size());
        long readOnly = IntStream.range(0, 8)
                .filter(i -> service.getBucket(NS, "dst-" + i).getIsReadOnly()).count();
        assertEquals(1, readOnly);
    }

    @Test
    void concurrentCreates_intoOneDestinationLetExactlyOneThrough() throws Exception {
        bucket(ASHBURN, "dst");
        List<Runnable> creates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String source = "src-" + i;
            bucket(ASHBURN, source);
            creates.add(() -> service.createReplicationPolicy(NS, source, "p", ASHBURN, "dst"));
        }

        assertEquals(1, succeededTogether(creates));
        assertEquals(1, service.listReplicationSources(NS, "dst").size());
    }

    @Test
    void concurrentWrites_leaveTheReplicaEqualToTheSource() throws Exception {
        replicateAshburnToPhoenix();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String content = "version-" + i;
                boolean delete = i % 5 == 4;
                writes.add(pool.submit(() -> {
                    start.await();
                    if (delete) {
                        try {
                            service.deleteObject(NS, "src", "hot", null);
                        } catch (OciException expected) {
                            // Another writer may have deleted it already.
                        }
                    } else {
                        put("src", "hot", content);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        boolean inSource = existsIn(ASHBURN, "src", "hot");
        assertEquals(inSource, existsIn(PHOENIX, "dst", "hot"));
        if (inSource) {
            in(ASHBURN);
            String sourceEtag = service.getObject(NS, "src", "hot").getEtag();
            in(PHOENIX);
            assertEquals(sourceEtag, service.getObject(NS, "dst", "hot").getEtag());
        }
    }
}
