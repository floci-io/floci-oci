package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.streaming.model.StoredStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class StreamingServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..streamtestcompartment";

    private StreamingService service;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new StreamingService(new InMemoryStorage<>(), config, workRequests);
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
}
