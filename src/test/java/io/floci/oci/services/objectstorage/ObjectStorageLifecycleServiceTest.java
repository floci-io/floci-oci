package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredLifecyclePolicy;
import io.floci.oci.services.objectstorage.model.StoredLifecycleRule;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ObjectStorageLifecycleServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";
    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..testcompartment";

    private EmulatorConfig config;
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultNamespace()).thenReturn(NS);
        service = new ObjectStorageService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), config,
                new WorkRequestService(new InMemoryStorage<>(), config));
    }

    private void bucket(String name, String versioning) {
        service.createBucket(NS, name, COMPARTMENT, null, null, null, null, null, null, versioning);
    }

    private StoredOsObject put(String bucket, String name, String content) {
        return service.putObject(NS, bucket, name, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", null, null, null, null);
    }

    private static Map<String, Object> rule(String action, long days, String target) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "rule-" + action);
        rule.put("action", action);
        rule.put("timeAmount", days);
        rule.put("timeUnit", "DAYS");
        rule.put("isEnabled", true);
        if (target != null) {
            rule.put("target", target);
        }
        return rule;
    }

    private static String code(Runnable call) {
        return assertThrows(OciException.class, call::run).getCode();
    }

    @Test
    void putReplacesThePolicyAndDeleteRemovesIt() {
        bucket("b", null);
        StoredLifecyclePolicy first = service.putLifecyclePolicy(NS, "b", List.of(rule("DELETE", 30, null)),
                null, null);
        assertEquals(1, first.getItems().size());
        assertNull(first.getItems().get(0).getTarget());

        StoredLifecyclePolicy empty = service.putLifecyclePolicy(NS, "b", List.of(), first.getEtag(), null);
        assertTrue(empty.getItems().isEmpty());
        assertNotEquals(first.getEtag(), empty.getEtag());
        assertTrue(service.getLifecyclePolicy(NS, "b").getItems().isEmpty());

        assertEquals("NoEtagMatch", code(() -> service.deleteLifecyclePolicy(NS, "b", first.getEtag())));
        service.deleteLifecyclePolicy(NS, "b", empty.getEtag());
        assertEquals("LifecyclePolicyNotFound", code(() -> service.getLifecyclePolicy(NS, "b")));
        assertEquals("LifecyclePolicyNotFound", code(() -> service.deleteLifecyclePolicy(NS, "b", null)));
    }

    @Test
    void absentItemsIsAPolicyWithoutRules() {
        bucket("b", null);
        assertTrue(service.putLifecyclePolicy(NS, "b", null, null, null).getItems().isEmpty());
    }

    @Test
    void ifNoneMatchStarRefusesToReplaceAPolicy() {
        bucket("b", null);
        service.putLifecyclePolicy(NS, "b", List.of(), null, "*");
        assertEquals("IfNoneMatchFailed", code(() -> service.putLifecyclePolicy(NS, "b", List.of(), null, "*")));
    }

    @Test
    void ifMatchWithoutAPolicyIsNotFound() {
        bucket("b", null);
        assertEquals("LifecyclePolicyNotFound",
                code(() -> service.putLifecyclePolicy(NS, "b", List.of(), "some-etag", null)));
    }

    @Test
    void anUnknownBucketIsBucketNotFound() {
        assertEquals("BucketNotFound", code(() -> service.getLifecyclePolicy(NS, "missing")));
        assertEquals("BucketNotFound", code(() -> service.putLifecyclePolicy(NS, "missing", List.of(), null, null)));
    }

    @Test
    void rulesAreValidated() {
        bucket("b", null);
        Map<String, Object> noName = rule("DELETE", 1, null);
        noName.remove("name");
        Map<String, Object> noAmount = rule("DELETE", 1, null);
        noAmount.remove("timeAmount");
        Map<String, Object> noEnabled = rule("DELETE", 1, null);
        noEnabled.remove("isEnabled");
        for (Map<String, Object> missing : List.of(noName, noAmount, noEnabled)) {
            assertEquals("MissingParameter",
                    code(() -> service.putLifecyclePolicy(NS, "b", List.of(missing), null, null)), missing.toString());
        }

        Map<String, Object> badUnit = rule("DELETE", 1, null);
        badUnit.put("timeUnit", "WEEKS");
        Map<String, Object> negative = rule("DELETE", -1, null);
        Map<String, Object> badPattern = rule("DELETE", 1, null);
        badPattern.put("objectNameFilter", Map.of("inclusionPatterns", List.of("[z-a]")));
        Map<String, Object> fractional = rule("DELETE", 1, null);
        fractional.put("timeAmount", 1.5);
        List<Map<String, Object>> invalid = List.of(rule("MOVE", 1, null), rule("DELETE", 1, "everything"),
                rule("ABORT", 1, null), rule("DELETE", 1, BucketLifecycle.MULTIPART_UPLOADS),
                badUnit, negative, badPattern, fractional);
        for (Map<String, Object> bad : invalid) {
            assertEquals("InvalidParameter",
                    code(() -> service.putLifecyclePolicy(NS, "b", List.of(bad), null, null)), bad.toString());
        }
        List<Map<String, Object>> tooMany = Collections.nCopies(BucketLifecycle.MAX_RULES + 1,
                rule("DELETE", 1, null));
        assertEquals("InvalidParameter", code(() -> service.putLifecyclePolicy(NS, "b", tooMany, null, null)));

        Map<String, Object> years = rule("ARCHIVE", 2, BucketLifecycle.OBJECTS);
        years.put("timeUnit", "years");
        assertEquals("years", service.putLifecyclePolicy(NS, "b", List.of(years), null, null)
                .getItems().get(0).getTimeUnit());
    }

    @Test
    void ageCountsFromTheNextMidnightUtc() {
        StoredLifecycleRule oneDay = new StoredLifecycleRule();
        oneDay.setTimeAmount(1);
        oneDay.setTimeUnit("DAYS");
        Instant written = Instant.parse("2026-03-10T15:00:00Z");
        assertFalse(BucketLifecycle.isDue(oneDay, written, Instant.parse("2026-03-11T23:59:59Z")));
        assertTrue(BucketLifecycle.isDue(oneDay, written, Instant.parse("2026-03-12T00:00:00Z")));

        StoredLifecycleRule sameDay = new StoredLifecycleRule();
        sameDay.setTimeAmount(0);
        sameDay.setTimeUnit("DAYS");
        assertFalse(BucketLifecycle.isDue(sameDay, written, Instant.parse("2026-03-10T23:00:00Z")));
        assertTrue(BucketLifecycle.isDue(sameDay, written, Instant.parse("2026-03-11T00:00:00Z")));
        assertTrue(BucketLifecycle.isDue(sameDay, Instant.parse("2026-03-10T00:00:00Z"),
                Instant.parse("2026-03-10T00:00:00Z")));

        StoredLifecycleRule oneYear = new StoredLifecycleRule();
        oneYear.setTimeAmount(1);
        oneYear.setTimeUnit("YEARS");
        // 365.2425 days after 2026-01-01T00:00Z is 2027-01-01T05:49:12Z, which rounds up to 2027-01-02.
        Instant newYear = Instant.parse("2026-01-01T00:00:00Z");
        assertFalse(BucketLifecycle.isDue(oneYear, newYear, Instant.parse("2027-01-01T23:00:00Z")));
        assertTrue(BucketLifecycle.isDue(oneYear, newYear, Instant.parse("2027-01-02T00:00:00Z")));

        StoredLifecycleRule forever = new StoredLifecycleRule();
        forever.setTimeAmount(Long.MAX_VALUE);
        forever.setTimeUnit("YEARS");
        assertFalse(BucketLifecycle.isDue(forever, newYear, Instant.MAX));
    }

    @Test
    void globPatternsFollowOraclesSyntax() {
        assertTrue(LifecycleGlob.compile("*.tmp").matches("foo/bar/baz.tmp"));
        assertFalse(LifecycleGlob.compile("*.tmp").matches("Atmp"));
        assertTrue(LifecycleGlob.compile("temp/*.tmp").matches("temp/new/file.tmp"));
        assertFalse(LifecycleGlob.compile("temp/*.tmp").matches("temp/new.draft"));
        assertTrue(LifecycleGlob.compile("X?Z").matches("X_Z"));
        assertFalse(LifecycleGlob.compile("X?Z").matches("XYYZ"));
        assertTrue(LifecycleGlob.compile("\\\\dir\\\\sub\\\\*").matches("\\dir\\sub\\ABC"));
        assertTrue(LifecycleGlob.compile("[-ab3]").matches("-"));
        assertFalse(LifecycleGlob.compile("[-ab3]").matches("3b"));
        assertTrue(LifecycleGlob.compile("backup.tar.gz.[0-9]").matches("backup.tar.gz.5"));
        assertTrue(LifecycleGlob.compile("page-[0-9]*").matches("page-2X"));
        assertFalse(LifecycleGlob.compile("page-[0-9]*").matches("page-A1"));
        assertTrue(LifecycleGlob.compile("\\[a-z\\]").matches("[a-z]"));
        assertFalse(LifecycleGlob.compile("\\[a-z\\]").matches("a"));
        assertTrue(LifecycleGlob.compile("a*b*c*d*e*f*g").matches("a".repeat(500) + "bcdefg"));
        assertFalse(LifecycleGlob.compile("a*a*a*a*a*a*a*b").matches("a".repeat(1000)));

        for (String invalid : List.of("\\", "[^a-z]", "[z-a]", "[:isalpha:]", "[ab-yz]", "[a-mn-z]", "[abc",
                "[]", "", "x".repeat(LifecycleGlob.MAX_LENGTH + 1))) {
            assertThrows(IllegalArgumentException.class, () -> LifecycleGlob.compile(invalid), invalid);
        }
    }

    @Test
    void exclusionsWinOverInclusions() {
        StoredLifecycleRule rule = BucketLifecycle.parseRules(List.of(Map.of("name", "r", "action", "DELETE",
                "timeAmount", 1, "timeUnit", "DAYS", "isEnabled", true,
                "objectNameFilter", Map.of("inclusionPatterns", List.of("*.log"),
                        "inclusionPrefixes", List.of("tmp/"), "exclusionPatterns", List.of("keep/*"))))).get(0);
        assertTrue(BucketLifecycle.matches(rule, "app.log"));
        assertTrue(BucketLifecycle.matches(rule, "tmp/x.bin"));
        assertFalse(BucketLifecycle.matches(rule, "keep/app.log"));
        assertFalse(BucketLifecycle.matches(rule, "data.bin"));

        StoredLifecycleRule onlyExclusions = BucketLifecycle.parseRules(List.of(Map.of("name", "r",
                "action", "DELETE", "timeAmount", 1, "timeUnit", "DAYS", "isEnabled", true,
                "objectNameFilter", Map.of("exclusionPatterns", List.of("*.keep"))))).get(0);
        assertTrue(BucketLifecycle.matches(onlyExclusions, "a.txt"));
        assertFalse(BucketLifecycle.matches(onlyExclusions, "a.keep"));
    }

    @Test
    void deleteRulesRemoveDueObjectsThatMatch() {
        bucket("b", null);
        put("b", "logs/a.log", "a");
        put("b", "data/b.bin", "b");
        Map<String, Object> logs = rule("DELETE", 1, BucketLifecycle.OBJECTS);
        logs.put("objectNameFilter", Map.of("inclusionPrefixes", List.of("logs/")));
        service.putLifecyclePolicy(NS, "b", List.of(logs), null, null);

        assertEquals(0, service.applyLifecycle(Instant.now()));
        assertEquals(1, service.applyLifecycle(Instant.now().plus(Duration.ofDays(3))));
        assertEquals("ObjectNotFound", code(() -> service.getObject(NS, "b", "logs/a.log")));
        assertEquals("b", new String(service.getObject(NS, "b", "data/b.bin").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void disabledRulesAndArchiveRulesChangeNothing() {
        bucket("b", null);
        put("b", "a.txt", "a");
        Map<String, Object> disabled = rule("DELETE", 0, null);
        disabled.put("isEnabled", false);
        service.putLifecyclePolicy(NS, "b", List.of(disabled, rule("ARCHIVE", 0, null),
                rule("INFREQUENT_ACCESS", 0, BucketLifecycle.OBJECTS)), null, null);

        assertEquals(0, service.applyLifecycle(Instant.now().plus(Duration.ofDays(30))));
        assertNotNull(service.getObject(NS, "b", "a.txt"));
    }

    @Test
    void onAVersionedBucketDeleteLeavesADeleteMarkerAndPreviousVersionsGoForGood() {
        bucket("v", "Enabled");
        StoredOsObject first = put("v", "k", "one");
        put("v", "k", "two");
        service.putLifecyclePolicy(NS, "v", List.of(rule("DELETE", 1, BucketLifecycle.OBJECTS)), null, null);

        assertEquals(1, service.applyLifecycle(Instant.now().plus(Duration.ofDays(3))));
        assertEquals("ObjectNotFound", code(() -> service.getObject(NS, "v", "k")));
        List<StoredOsObject> left = service.listObjectVersions(NS, "v", null, null, null, null, null, null, null)
                .items();
        assertEquals(3, left.size());
        assertTrue(left.get(0).isDeleteMarker());

        service.putLifecyclePolicy(NS, "v", List.of(rule("DELETE", 1, BucketLifecycle.PREVIOUS_VERSIONS)),
                null, null);
        assertEquals(2, service.applyLifecycle(Instant.now().plus(Duration.ofDays(3))));
        List<StoredOsObject> markerOnly = service.listObjectVersions(NS, "v", null, null, null, null, null, null,
                null).items();
        assertEquals(1, markerOnly.size());
        assertTrue(markerOnly.get(0).isDeleteMarker());
        assertEquals("ObjectNotFound", code(() -> service.getObject(NS, "v", "k", first.getVersionId())));
    }

    @Test
    void previousVersionsAgeFromWhenTheyWereReplaced() {
        bucket("v", "Enabled");
        put("v", "k", "one");
        service.putLifecyclePolicy(NS, "v", List.of(rule("DELETE", 0, BucketLifecycle.PREVIOUS_VERSIONS)),
                null, null);
        Instant tomorrow = Instant.now().plus(Duration.ofDays(2));
        assertEquals(0, service.applyLifecycle(tomorrow), "the only version is the latest, never previous");

        put("v", "k", "two");
        assertEquals(1, service.applyLifecycle(tomorrow));
        assertEquals("two", new String(service.getObject(NS, "v", "k").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void abortRulesDropOldUncommittedUploads() {
        bucket("b", null);
        StoredMultipartUpload upload = service.createMultipartUpload(NS, "b", "big.bin", null, null);
        service.putLifecyclePolicy(NS, "b", List.of(rule("ABORT", 5, BucketLifecycle.MULTIPART_UPLOADS)),
                null, null);

        assertEquals(0, service.applyLifecycle(Instant.now().plus(Duration.ofDays(2))));
        assertEquals(1, service.applyLifecycle(Instant.now().plus(Duration.ofDays(7))));
        assertEquals("NotAuthorizedOrNotFound",
                code(() -> service.getUpload("b", upload.getUploadId(), null)));
    }

    @Test
    void deletingTheBucketDeletesItsPolicy() {
        bucket("b", null);
        service.putLifecyclePolicy(NS, "b", List.of(), null, null);
        service.deleteBucket(NS, "b", null);
        bucket("b", null);
        assertEquals("LifecyclePolicyNotFound", code(() -> service.getLifecyclePolicy(NS, "b")));
    }

    @Test
    void aPreviousVersionAgesFromWhenItBecamePreviousNotFromTheNextSurvivingVersion() {
        bucket("v", "Enabled");
        StoredOsObject first = put("v", "k", "one");
        StoredOsObject second = put("v", "k", "two");
        StoredOsObject third = put("v", "k", "three");
        assertNotNull(first.getTimePrevious(), "replacing a version records when it became previous");

        Instant now = Instant.now();
        // The first version was written 50 days ago and replaced by the second 40 days ago; the second is gone,
        // and the third, written 5 days ago, is the next surviving version.
        first.setTimeModified(now.minus(Duration.ofDays(50)).toString());
        first.setTimePrevious(now.minus(Duration.ofDays(40)).toString());
        service.deleteObject(NS, "v", "k", null, second.getVersionId());
        third.setTimeModified(now.minus(Duration.ofDays(5)).toString());
        service.putLifecyclePolicy(NS, "v", List.of(rule("DELETE", 30, BucketLifecycle.PREVIOUS_VERSIONS)),
                null, null);

        assertEquals(1, service.applyLifecycle(now));
        assertEquals("ObjectNotFound", code(() -> service.getObject(NS, "v", "k", first.getVersionId())));
        assertEquals("three", new String(service.getObject(NS, "v", "k").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void aVersionPromotedToTheLatestAfterTheSweepListedItIsKept() {
        bucket("v", "Enabled");
        StoredOsObject first = put("v", "k", "one");
        StoredOsObject second = put("v", "k", "two");

        // The sweep listed the first version as previous; then the latest one is deleted and it is promoted.
        service.deleteObject(NS, "v", "k", null, second.getVersionId());

        assertFalse(service.deletePreviousVersion("v", first));
        assertEquals("one", new String(service.getObject(NS, "v", "k").getData(), StandardCharsets.UTF_8));
    }

    @Test
    void aPolicyPutRacingABucketDeleteNeverOutlivesTheBucket() throws InterruptedException {
        AtomicBoolean pauseNextRead = new AtomicBoolean();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        InMemoryStorage<String, StoredBucket> buckets = new InMemoryStorage<>() {
            @Override
            public Optional<StoredBucket> get(String key) {
                Optional<StoredBucket> read = super.get(key);
                // Paused after the read, so the PUT acts on a bucket that the delete may remove meanwhile.
                if (pauseNextRead.compareAndSet(true, false)) {
                    paused.countDown();
                    awaitQuietly(resume);
                }
                return read;
            }
        };
        ObjectStorageService raced = new ObjectStorageService(buckets, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), config,
                new WorkRequestService(new InMemoryStorage<>(), config));
        raced.createBucket(NS, "b", COMPARTMENT, null, null, null, null, null, null, null);

        pauseNextRead.set(true);
        Thread put = new Thread(() -> ignoringErrors(() -> raced.putLifecyclePolicy(NS, "b", List.of(), null, null)));
        put.start();
        assertTrue(paused.await(5, TimeUnit.SECONDS), "the policy PUT reads the bucket");
        Thread delete = new Thread(() -> ignoringErrors(() -> raced.deleteBucket(NS, "b", null)));
        delete.start();
        // Long enough for a delete that does not wait for the PUT to finish first.
        delete.join(300);
        resume.countDown();
        put.join(5000);
        delete.join(5000);

        assertEquals("BucketNotFound", code(() -> raced.getBucket(NS, "b")));
        assertTrue(raced.lifecyclePolicyStore().scan(k -> true).isEmpty(),
                "a policy stored for a deleted bucket would apply to the next bucket of that name");
    }

    @Test
    void aPartUploadedWhileTheSweepAbortsTheUploadDoesNotBringItBack() throws InterruptedException {
        AtomicBoolean pauseNextRead = new AtomicBoolean();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        InMemoryStorage<String, StoredMultipartUpload> uploads = new InMemoryStorage<>() {
            @Override
            public Optional<StoredMultipartUpload> get(String key) {
                Optional<StoredMultipartUpload> read = super.get(key);
                // Paused after the read, so the part is saved to an upload the sweep may abort meanwhile.
                if (pauseNextRead.compareAndSet(true, false)) {
                    paused.countDown();
                    awaitQuietly(resume);
                }
                return read;
            }
        };
        ObjectStorageService raced = new ObjectStorageService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                uploads, new InMemoryStorage<>(), config, new WorkRequestService(new InMemoryStorage<>(), config));
        raced.createBucket(NS, "b", COMPARTMENT, null, null, null, null, null, null, null);
        StoredMultipartUpload upload = raced.createMultipartUpload(NS, "b", "big.bin", null, null);
        raced.putLifecyclePolicy(NS, "b", List.of(rule("ABORT", 1, BucketLifecycle.MULTIPART_UPLOADS)), null, null);

        pauseNextRead.set(true);
        Thread part = new Thread(() -> ignoringErrors(() -> raced.uploadPart(NS, "b", "big.bin",
                upload.getUploadId(), 1, "data".getBytes(StandardCharsets.UTF_8))));
        part.start();
        assertTrue(paused.await(5, TimeUnit.SECONDS), "the part upload reads the upload");
        Thread sweep = new Thread(() -> raced.applyLifecycle(Instant.now().plus(Duration.ofDays(3))));
        sweep.start();
        // Long enough for a sweep that does not wait for the part to be saved first.
        sweep.join(300);
        resume.countDown();
        part.join(5000);
        sweep.join(5000);

        assertEquals("NotAuthorizedOrNotFound", code(() -> raced.getUpload("b", upload.getUploadId(), null)));
    }

    @Test
    void requestsForMissingOrFinishedUploadsLeaveNoLocksBehind() {
        bucket("b", null);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        assertEquals("NotAuthorizedOrNotFound", code(() -> service.uploadPart(NS, "b", "x", "missing", 1, data)));
        assertEquals("NotAuthorizedOrNotFound", code(() -> service.abortMultipartUpload(NS, "b", "x", "missing")));
        assertEquals("NotAuthorizedOrNotFound",
                code(() -> service.commitMultipartUpload(NS, "b", "x", "missing", List.of())));

        StoredMultipartUpload aborted = service.createMultipartUpload(NS, "b", "x", null, null);
        service.uploadPart(NS, "b", "x", aborted.getUploadId(), 1, data);
        assertEquals(1, service.uploadLockCount(), "a live upload keeps its lock");
        service.abortMultipartUpload(NS, "b", "x", aborted.getUploadId());

        StoredMultipartUpload committed = service.createMultipartUpload(NS, "b", "y", null, null);
        String etag = service.uploadPart(NS, "b", "y", committed.getUploadId(), 1, data).etag();
        service.commitMultipartUpload(NS, "b", "y", committed.getUploadId(),
                List.of(Map.of("partNum", 1, "etag", etag)));

        assertEquals(0, service.uploadLockCount());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void ignoringErrors(Runnable call) {
        try {
            call.run();
        } catch (OciException expected) {
            // Either side of the race may lose with an OCI error; the test checks what is stored afterwards.
        }
    }
}
