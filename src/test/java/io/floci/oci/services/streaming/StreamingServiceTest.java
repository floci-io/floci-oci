package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStreamPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class StreamingServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..streamtestcompartment";

    private static final String TENANCY = "ocid1.tenancy.oc1..streamtesttenancy";
    private static final String OTHER_COMPARTMENT = "ocid1.compartment.oc1..streamothercompartment";

    private StreamingService service;
    private WorkRequestService workRequests;
    private InMemoryStorage<String, StoredStream> streams;
    private InMemoryStorage<String, StoredStreamPool> pools;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        streams = new InMemoryStorage<>();
        pools = new InMemoryStorage<>();
        service = new StreamingService(streams, pools, config, workRequests);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    private StoredStream stream(int partitions) {
        return service.createStream("s-" + System.nanoTime(), partitions, null,
                COMPARTMENT, null, null, null).stream();
    }

    @Test
    void createStreamRecordsWorkRequestWithStreamEntityType() {
        StreamingService.CreatedStream created =
                service.createStream("orders", 2, 48, COMPARTMENT, null, null, null);
        assertTrue(created.stream().getId().startsWith("ocid1.stream.oc1.iad."));
        assertEquals("ACTIVE", created.stream().getLifecycleState());
        assertEquals(48, created.stream().getRetentionInHours());
        assertNotNull(created.stream().getStreamPoolId());

        // Terraform matches entityType containing "stream" (lowercased) with actionType CREATED.
        StoredWorkRequest wr = workRequests.get("streaming", created.workRequestId());
        assertEquals("CREATE_STREAM", wr.getOperationType());
        assertTrue(wr.getResources().get(0).getEntityType().toLowerCase().contains("stream"));
        assertEquals("CREATED", wr.getResources().get(0).getActionType());
        assertEquals(created.stream().getId(), wr.getResources().get(0).getIdentifier());
        assertNotNull(wr.getTimeFinished());
    }

    @Test
    void partitionsRequiredAndPositive() {
        assertThrows(OciException.class,
                () -> service.createStream("bad", null, null, COMPARTMENT, null, null, null));
        assertThrows(OciException.class,
                () -> service.createStream("bad", 0, null, COMPARTMENT, null, null, null));
    }

    @Test
    void keylessMessagesRoundRobinAcrossPartitions() {
        StoredStream s = stream(2);
        List<StreamingService.PutEntryResult> results = service.putMessages(s.getId(), List.of(
                Map.of("value", b64("a")), Map.of("value", b64("b")),
                Map.of("value", b64("c")), Map.of("value", b64("d"))));
        Set<Integer> partitions = results.stream()
                .map(StreamingService.PutEntryResult::partition)
                .collect(Collectors.toSet());
        assertEquals(Set.of(0, 1), partitions);
        assertEquals(0, results.get(0).offset());
    }

    @Test
    void sameKeyAlwaysLandsOnSamePartition() {
        StoredStream s = stream(4);
        List<StreamingService.PutEntryResult> results = service.putMessages(s.getId(), List.of(
                Map.of("key", b64("customer-1"), "value", b64("a")),
                Map.of("key", b64("customer-1"), "value", b64("b"))));
        assertEquals(results.get(0).partition(), results.get(1).partition());
        assertEquals(0, results.get(0).offset());
        assertEquals(1, results.get(1).offset());
    }

    @Test
    void trimHorizonCursorReadsFromStartAndAdvances() {
        StoredStream s = stream(1);
        service.putMessages(s.getId(), List.of(
                Map.of("value", b64("m1")), Map.of("value", b64("m2"))));

        String cursor = service.createCursor(s.getId(), "0", "TRIM_HORIZON", null, null);
        StreamingService.ConsumeResult first = service.getMessages(s.getId(), cursor, 1);
        assertEquals(1, first.messages().size());
        assertEquals(b64("m1"), first.messages().get(0).get("value"));
        assertEquals(s.getName(), first.messages().get(0).get("stream"));
        assertEquals("0", first.messages().get(0).get("partition"));

        StreamingService.ConsumeResult second = service.getMessages(s.getId(), first.nextCursor(), 10);
        assertEquals(1, second.messages().size());
        assertEquals(b64("m2"), second.messages().get(0).get("value"));

        // Fully drained: next cursor keeps returning empty.
        assertTrue(service.getMessages(s.getId(), second.nextCursor(), 10).messages().isEmpty());
    }

    @Test
    void latestCursorSkipsExistingMessages() {
        StoredStream s = stream(1);
        service.putMessages(s.getId(), List.of(Map.of("value", b64("old"))));
        String cursor = service.createCursor(s.getId(), "0", "LATEST", null, null);
        assertTrue(service.getMessages(s.getId(), cursor, 10).messages().isEmpty());
        service.putMessages(s.getId(), List.of(Map.of("value", b64("new"))));
        assertEquals(1, service.getMessages(s.getId(), cursor, 10).messages().size());
    }

    @Test
    void atOffsetAndAfterOffsetCursors() {
        StoredStream s = stream(1);
        service.putMessages(s.getId(), List.of(
                Map.of("value", b64("m0")), Map.of("value", b64("m1")), Map.of("value", b64("m2"))));
        String at = service.createCursor(s.getId(), "0", "AT_OFFSET", 1L, null);
        assertEquals(b64("m1"), service.getMessages(s.getId(), at, 1).messages().get(0).get("value"));
        String after = service.createCursor(s.getId(), "0", "AFTER_OFFSET", 1L, null);
        assertEquals(b64("m2"), service.getMessages(s.getId(), after, 1).messages().get(0).get("value"));
    }

    @Test
    void groupCursorCommitsAndResumes() {
        StoredStream s = stream(2);
        service.putMessages(s.getId(), List.of(
                Map.of("key", b64("k0"), "value", b64("a")),
                Map.of("key", b64("k1"), "value", b64("b")),
                Map.of("key", b64("k2"), "value", b64("c"))));

        String cursor = service.createGroupCursor(s.getId(), "workers", "TRIM_HORIZON", null, false);
        StreamingService.ConsumeResult consumed = service.getMessages(s.getId(), cursor, 10);
        assertEquals(3, consumed.messages().size());

        service.consumerCommit(s.getId(), consumed.nextCursor());

        // A fresh group cursor resumes from the committed offsets: nothing left.
        String resumed = service.createGroupCursor(s.getId(), "workers", "TRIM_HORIZON", null, false);
        assertTrue(service.getMessages(s.getId(), resumed, 10).messages().isEmpty());

        // A different group still reads from the horizon.
        String other = service.createGroupCursor(s.getId(), "audit", "TRIM_HORIZON", null, false);
        assertEquals(3, service.getMessages(s.getId(), other, 10).messages().size());

        Map<String, Object> group = service.getGroup(s.getId(), "workers");
        assertEquals("workers", group.get("groupName"));
        assertEquals(2, ((List<?>) group.get("reservations")).size());
    }

    @Test
    void updateGroupResetsCommittedOffsets() {
        StoredStream s = stream(1);
        service.putMessages(s.getId(), List.of(Map.of("value", b64("m"))));
        String cursor = service.createGroupCursor(s.getId(), "g", "TRIM_HORIZON", null, false);
        service.consumerCommit(s.getId(), service.getMessages(s.getId(), cursor, 10).nextCursor());

        service.updateGroup(s.getId(), "g", "TRIM_HORIZON", null);
        String reset = service.createGroupCursor(s.getId(), "g", "LATEST", null, false);
        assertEquals(1, service.getMessages(s.getId(), reset, 10).messages().size());
    }

    @Test
    void oversizedAndInvalidEntriesFailPerEntry() {
        StoredStream s = stream(1);
        String big = Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]);
        List<StreamingService.PutEntryResult> results = service.putMessages(s.getId(), List.of(
                Map.of("value", big), Map.of("value", b64("fine"))));
        assertEquals("InvalidParameter", results.get(0).error());
        assertEquals(0, results.get(1).offset());
    }

    @Test
    void foreignAndMalformedCursorsAreRejected() {
        StoredStream a = stream(1);
        StoredStream b = stream(1);
        String cursorForA = service.createCursor(a.getId(), "0", "TRIM_HORIZON", null, null);
        assertThrows(OciException.class, () -> service.getMessages(b.getId(), cursorForA, 10));
        assertThrows(OciException.class, () -> service.getMessages(a.getId(), "!!bogus!!", 10));
    }

    @Test
    void deleteStreamRecordsWorkRequest() {
        StoredStream s = stream(1);
        String wr = service.deleteStream(s.getId(), null);
        assertEquals("DELETE_STREAM", workRequests.get(wr).getOperationType());
        assertThrows(OciException.class, () -> service.getStream(s.getId()));
    }

    // ── Stream pools ───────────────────────────────────────────────────────────

    @Test
    void createStreamPoolAppliesDefaultsAndRecordsAStreamPoolWorkRequest() {
        StreamingService.PoolChange created = service.createStreamPool("orders-pool", COMPARTMENT,
                null, null, Map.of("team", "a"), null);
        StoredStreamPool pool = created.pool();
        assertTrue(pool.getId().startsWith("ocid1.streampool.oc1.iad."));
        assertEquals("ACTIVE", pool.getLifecycleState());
        assertEquals(false, pool.getAutoCreateTopicsEnable());
        assertEquals(24, pool.getLogRetentionHours());
        assertEquals(1, pool.getNumPartitions());
        assertNotNull(pool.getEtag());

        StoredWorkRequest wr = workRequests.get("streaming", created.workRequestId());
        assertEquals("CREATE_STREAM_POOL", wr.getOperationType());
        assertEquals("stream_pool", wr.getResources().get(0).getEntityType());
        assertEquals("CREATED", wr.getResources().get(0).getActionType());
        assertEquals(pool.getId(), wr.getResources().get(0).getIdentifier());
    }

    @Test
    void createStreamPoolTakesKafkaSettingsAndRequiresAName() {
        StoredStreamPool pool = service.createStreamPool("kafka-pool", COMPARTMENT,
                Map.of("autoCreateTopicsEnable", true, "logRetentionHours", 48, "numPartitions", 3),
                Map.of("kmsKeyId", "ocid1.key.oc1.iad.k"), null, null).pool();
        assertEquals(true, pool.getAutoCreateTopicsEnable());
        assertEquals(48, pool.getLogRetentionHours());
        assertEquals(3, pool.getNumPartitions());
        assertEquals("ocid1.key.oc1.iad.k", pool.getKmsKeyId());

        OciException missing = assertThrows(OciException.class,
                () -> service.createStreamPool(null, COMPARTMENT, null, null, null, null));
        assertEquals("MissingParameter", missing.getCode());
    }

    @Test
    void updateStreamPoolChangesNameSettingsAndEtag() {
        StoredStreamPool pool = service.createStreamPool("before", COMPARTMENT, null, null, null, null).pool();
        String etag = pool.getEtag();
        StreamingService.PoolChange updated = service.updateStreamPool(pool.getId(), "after",
                Map.of("logRetentionHours", 72), null, Map.of("k", "v"), null, etag);
        assertEquals("after", updated.pool().getName());
        assertEquals(72, updated.pool().getLogRetentionHours());
        assertNotEquals(etag, updated.pool().getEtag());
        assertEquals("UPDATED", workRequests.get(updated.workRequestId()).getResources().get(0).getActionType());

        OciException stale = assertThrows(OciException.class,
                () -> service.updateStreamPool(pool.getId(), "x", null, null, null, null, etag));
        assertEquals(412, stale.getHttpStatus());
    }

    @Test
    void listStreamPoolsFiltersSortsAndRequiresACompartment() throws InterruptedException {
        StoredStreamPool b = service.createStreamPool("b-pool", COMPARTMENT, null, null, null, null).pool();
        Thread.sleep(2);
        StoredStreamPool a = service.createStreamPool("a-pool", COMPARTMENT, null, null, null, null).pool();
        service.createStreamPool("elsewhere", OTHER_COMPARTMENT, null, null, null, null);

        assertEquals(List.of(a.getId(), b.getId()), ids(service.listStreamPools(COMPARTMENT, null, null, null,
                null, null)));
        assertEquals(List.of(a.getId(), b.getId()), ids(service.listStreamPools(COMPARTMENT, null, null, null,
                "NAME", null)));
        assertEquals(List.of(b.getId(), a.getId()), ids(service.listStreamPools(COMPARTMENT, null, null, null,
                "TIMECREATED", "ASC")));
        assertEquals(List.of(b.getId()), ids(service.listStreamPools(COMPARTMENT, null, "b-pool", null,
                null, null)));
        assertEquals(List.of(a.getId()), ids(service.listStreamPools(COMPARTMENT, a.getId(), null, "active",
                null, null)));

        OciException missing = assertThrows(OciException.class,
                () -> service.listStreamPools(null, null, null, null, null, null));
        assertEquals("MissingParameter", missing.getCode());
        assertEquals(400, missing.getHttpStatus());
    }

    @Test
    void streamCreatedWithOnlyAPoolInheritsItsCompartment() {
        StoredStreamPool pool = service.createStreamPool("p", OTHER_COMPARTMENT, null, null, null, null).pool();
        StoredStream stream = service.createStream("in-pool", 1, null, null, pool.getId(), null, null).stream();
        assertEquals(OTHER_COMPARTMENT, stream.getCompartmentId());
        assertEquals(pool.getId(), stream.getStreamPoolId());
    }

    @Test
    void streamInAnUnknownPoolIsRejected() {
        OciException e = assertThrows(OciException.class, () -> service.createStream("s", 1, null, null,
                "ocid1.streampool.oc1.iad.missing", null, null));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void streamsWithOnlyACompartmentShareItsDefaultPool() {
        StoredStream first = service.createStream("one", 1, null, COMPARTMENT, null, null, null).stream();
        StoredStream second = service.createStream("two", 1, null, COMPARTMENT, null, null, null).stream();
        StoredStream other = service.createStream("three", 1, null, OTHER_COMPARTMENT, null, null, null).stream();

        assertEquals(first.getStreamPoolId(), second.getStreamPoolId());
        assertNotEquals(first.getStreamPoolId(), other.getStreamPoolId());
        StoredStreamPool pool = service.getStreamPool(first.getStreamPoolId());
        assertEquals("DefaultPool", pool.getName());
        assertEquals(COMPARTMENT, pool.getCompartmentId());
        assertTrue(pool.isDefaultPool());
        assertEquals(List.of(pool.getId()), ids(service.listStreamPools(COMPARTMENT, null, null, null, null, null)));
    }

    @Test
    void deletingAPoolThatHoldsStreamsIsAConflict() {
        StoredStreamPool pool = service.createStreamPool("busy", COMPARTMENT, null, null, null, null).pool();
        StoredStream stream = service.createStream("s", 1, null, null, pool.getId(), null, null).stream();

        OciException e = assertThrows(OciException.class, () -> service.deleteStreamPool(pool.getId(), null));
        assertEquals("IncorrectState", e.getCode());
        assertEquals(409, e.getHttpStatus());

        service.deleteStream(stream.getId(), null);
        String wr = service.deleteStreamPool(pool.getId(), null);
        assertEquals("DELETED", workRequests.get(wr).getResources().get(0).getActionType());
        assertThrows(OciException.class, () -> service.getStreamPool(pool.getId()));
    }

    @Test
    void changeStreamPoolCompartmentMovesThePool() {
        StoredStreamPool pool = service.createStreamPool("moving", COMPARTMENT, null, null, null, null).pool();
        String wr = service.changeStreamPoolCompartment(pool.getId(), OTHER_COMPARTMENT, null);
        assertEquals(OTHER_COMPARTMENT, service.getStreamPool(pool.getId()).getCompartmentId());
        assertEquals("MOVED", workRequests.get(wr).getResources().get(0).getActionType());
    }

    @Test
    void createdPoolsHoldTheirCompartmentButDefaultPoolsDoNot() {
        service.createStream("s", 1, null, OTHER_COMPARTMENT, null, null, null);
        StoredStreamPool pool = service.createStreamPool("held", COMPARTMENT, null, null, null, null).pool();
        assertEquals(List.of("stream pool " + pool.getId()), service.resourcesIn(TENANCY, COMPARTMENT));
        assertEquals(1, service.resourcesIn(TENANCY, OTHER_COMPARTMENT).size());
    }

    @Test
    void startupMigrationMaterializesThePoolsOfOlderStreams() {
        StoredStream synthesized = legacyStream("legacy-a", COMPARTMENT, "ocid1.streampool.oc1.iad.synthesized");
        StoredStream sibling = legacyStream("legacy-b", COMPARTMENT, "ocid1.streampool.oc1.iad.synthesized");
        StoredStream poolOnly = legacyStream("legacy-c", null, "ocid1.streampool.oc1.iad.poolonly");
        streams.put(synthesized.getId(), synthesized);
        streams.put(sibling.getId(), sibling);
        streams.put(poolOnly.getId(), poolOnly);

        service.materializeMissingPools();
        service.materializeMissingPools();

        assertEquals(2, pools.scan(k -> true).size());
        StoredStreamPool pool = service.getStreamPool("ocid1.streampool.oc1.iad.synthesized");
        assertEquals("DefaultPool", pool.getName());
        assertEquals(COMPARTMENT, pool.getCompartmentId());
        assertEquals("ACTIVE", pool.getLifecycleState());
        assertEquals(TENANCY, service.getStreamPool("ocid1.streampool.oc1.iad.poolonly").getCompartmentId());
        assertEquals(TENANCY, service.getStream(poolOnly.getId()).getCompartmentId());

        // The materialized pool is the compartment's DefaultPool from now on.
        StoredStream fresh = service.createStream("fresh", 1, null, COMPARTMENT, null, null, null).stream();
        assertEquals("ocid1.streampool.oc1.iad.synthesized", fresh.getStreamPoolId());
    }

    private static StoredStream legacyStream(String name, String compartmentId, String streamPoolId) {
        StoredStream stream = new StoredStream();
        stream.setId("ocid1.stream.oc1.iad." + name);
        stream.setName(name);
        stream.setCompartmentId(compartmentId);
        stream.setStreamPoolId(streamPoolId);
        stream.setPartitions(1);
        stream.setLifecycleState("ACTIVE");
        stream.setTimeCreated("2026-01-01T00:00:00Z");
        return stream;
    }

    private static List<String> ids(List<StoredStreamPool> found) {
        return found.stream().map(StoredStreamPool::getId).toList();
    }
}
