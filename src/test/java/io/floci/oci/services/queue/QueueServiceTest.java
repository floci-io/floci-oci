package io.floci.oci.services.queue;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.queue.model.StoredQueue.StoredMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class QueueServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..queuetestcompartment";

    private QueueService service;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new QueueService(new InMemoryStorage<>(), config, workRequests);
    }

    private String createQueue(Integer visibility, Integer dlqCount) {
        return service.createQueue("q", COMPARTMENT, null, visibility, null,
                dlqCount, null, null, null).queueId();
    }

    @Test
    void createQueueRecordsWorkRequestWithResourceAndTimeFinished() {
        QueueService.WorkRequestOutcome outcome =
                service.createQueue("orders", COMPARTMENT, null, null, null, null, null, null, null);
        assertTrue(outcome.queueId().startsWith("ocid1.queue.oc1.iad."));

        StoredWorkRequest wr = workRequests.get("queue", outcome.workRequestId());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals("CREATE_QUEUE", wr.getOperationType());
        // Terraform reads the queue OCID off resources[] on the FIRST poll and
        // spins until timeFinished is set — both must be present immediately.
        assertEquals("QUEUE", wr.getResources().get(0).getEntityType());
        assertEquals("CREATED", wr.getResources().get(0).getActionType());
        assertEquals(outcome.queueId(), wr.getResources().get(0).getIdentifier());
        assertNotNull(wr.getTimeFinished());

        var q = service.getQueue(outcome.queueId());
        assertEquals("ACTIVE", q.getLifecycleState());
        assertEquals(30, q.getVisibilityInSeconds());
        assertEquals(86400, q.getRetentionInSeconds());
        assertEquals(0, q.getDeadLetterQueueDeliveryCount());
        assertNotNull(q.getMessagesEndpoint());
    }

    @Test
    void queueWorkRequestsAreInvisibleToOtherServices() {
        QueueService.WorkRequestOutcome outcome = service.createQueue("wr-scope", COMPARTMENT,
                null, null, null, null, null, null, null);
        assertThrows(OciException.class,
                () -> workRequests.get("identity", outcome.workRequestId()));
        assertTrue(workRequests.list("identity", null).stream()
                .noneMatch(wr -> wr.getId().equals(outcome.workRequestId())));
        assertEquals(1, workRequests.list("queue", COMPARTMENT).size());
    }

    @Test
    void putAndConsumeRoundtrip() {
        String queueId = createQueue(30, null);
        List<QueueService.PutResult> put = service.putMessages(queueId, List.of(
                Map.of("content", "first"), Map.of("content", "second")));
        assertEquals(2, put.size());
        assertEquals(1L, put.get(0).id());
        assertEquals(2L, put.get(1).id());

        List<StoredMessage> got = service.getMessages(queueId, null, 0, 10, null);
        assertEquals(2, got.size());
        assertEquals("first", got.get(0).getContent());
        assertEquals(1, got.get(0).getDeliveryCount());
        assertNotNull(got.get(0).getReceipt());

        // In-flight: a second consumer sees nothing while visibility holds.
        assertTrue(service.getMessages(queueId, null, 0, 10, null).isEmpty());

        service.deleteMessage(queueId, got.get(0).getReceipt());
        service.deleteMessage(queueId, got.get(1).getReceipt());
        assertEquals(0, service.getStats(queueId).visibleMessages());
        assertEquals(0, service.getStats(queueId).inFlightMessages());
    }

    @Test
    void visibilityZeroPeeksButCountsDelivery() {
        String queueId = createQueue(30, null);
        service.putMessages(queueId, List.of(Map.of("content", "peek-me")));

        List<StoredMessage> peeked = service.getMessages(queueId, 0, 0, 10, null);
        assertEquals(1, peeked.size());
        assertEquals(1, peeked.get(0).getDeliveryCount());

        // Still visible to the next consumer, delivery count keeps growing.
        List<StoredMessage> again = service.getMessages(queueId, 0, 0, 10, null);
        assertEquals(1, again.size());
        assertEquals(2, again.get(0).getDeliveryCount());
    }

    @Test
    void updateMessageExtendsVisibility() {
        String queueId = createQueue(1, null);
        service.putMessages(queueId, List.of(Map.of("content", "extend")));
        StoredMessage m = service.getMessages(queueId, 1, 0, 1, null).get(0);
        StoredMessage updated = service.updateMessage(queueId, m.getReceipt(), 120);
        assertTrue(updated.getVisibleAfter().compareTo(m.getCreatedAt()) > 0);
    }

    @Test
    void overDeliveredMessagesMoveToDlq() {
        String queueId = createQueue(0, 2);
        service.putMessages(queueId, List.of(Map.of("content", "poison")));
        // Two peek deliveries reach the DLQ threshold.
        service.getMessages(queueId, 0, 0, 1, null);
        service.getMessages(queueId, 0, 0, 1, null);

        QueueService.QueueStats stats = service.getStats(queueId);
        assertEquals(0, stats.visibleMessages());
        assertEquals(1, stats.dlqVisible());
    }

    @Test
    void purgeClearsSelectedLanes() {
        String queueId = createQueue(0, 1);
        service.putMessages(queueId, List.of(Map.of("content", "a"), Map.of("content", "b")));
        service.getMessages(queueId, 0, 0, 2, null); // both to DLQ threshold=1
        service.getStats(queueId);                    // triggers dead-lettering
        service.putMessages(queueId, List.of(Map.of("content", "fresh")));

        service.purgeQueue(queueId, "DLQ", null);
        assertEquals(0, service.getStats(queueId).dlqVisible());
        assertEquals(1, service.getStats(queueId).visibleMessages());

        service.purgeQueue(queueId, "NORMAL", null);
        assertEquals(0, service.getStats(queueId).visibleMessages());
    }

    @Test
    void channelsAreDiscoverableAndFilterable() {
        String queueId = createQueue(30, null);
        service.putMessages(queueId, List.of(
                Map.of("content", "to-a", "metadata", Map.of("channelId", "channel-a")),
                Map.of("content", "to-b", "metadata", Map.of("channelId", "channel-b"))));

        assertEquals(List.of("channel-a", "channel-b"), service.listChannels(queueId));

        List<StoredMessage> onlyB = service.getMessages(queueId, 0, 0, 10, "channel-b");
        assertEquals(1, onlyB.size());
        assertEquals("to-b", onlyB.get(0).getContent());
    }

    @Test
    void batchDeleteReportsPerEntryFailures() {
        String queueId = createQueue(30, null);
        service.putMessages(queueId, List.of(Map.of("content", "x")));
        StoredMessage m = service.getMessages(queueId, 30, 0, 1, null).get(0);

        List<QueueService.BatchEntryError> results =
                service.deleteMessages(queueId, List.of(m.getReceipt(), "bogus-receipt"));
        assertNull(results.get(0).errorCode());
        assertEquals(404, results.get(1).errorCode());
    }

    @Test
    void retentionIsNotUpdatable() {
        String queueId = createQueue(30, null);
        int retention = service.getQueue(queueId).getRetentionInSeconds();
        service.updateQueue(queueId, "renamed", 60, null, null, null, null, null, null);
        assertEquals(retention, service.getQueue(queueId).getRetentionInSeconds());
        assertEquals("renamed", service.getQueue(queueId).getDisplayName());
        assertEquals(60, service.getQueue(queueId).getVisibilityInSeconds());
    }

    @Test
    void deleteQueueRemovesItAndRecordsWorkRequest() {
        String queueId = createQueue(30, null);
        String etagBefore = service.getQueue(queueId).getEtag();
        QueueService.WorkRequestOutcome outcome = service.deleteQueue(queueId, etagBefore);
        assertEquals("DELETE_QUEUE", workRequests.get(outcome.workRequestId()).getOperationType());
        assertThrows(OciException.class, () -> service.getQueue(queueId));
    }

    @Test
    void staleIfMatchIs412() {
        String queueId = createQueue(30, null);
        OciException e = assertThrows(OciException.class,
                () -> service.deleteQueue(queueId, "stale"));
        assertEquals(412, e.getHttpStatus());
    }

    @Test
    void changeCompartmentMoves() {
        String queueId = createQueue(30, null);
        String other = "ocid1.compartment.oc1..otherqueuecompartment";
        service.changeCompartment(queueId, other, null);
        assertEquals(other, service.getQueue(queueId).getCompartmentId());
        assertNotEquals(COMPARTMENT, service.getQueue(queueId).getCompartmentId());
    }
}
