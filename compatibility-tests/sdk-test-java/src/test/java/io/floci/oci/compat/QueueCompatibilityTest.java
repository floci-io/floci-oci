package io.floci.oci.compat;

import com.oracle.bmc.queue.QueueAdminClient;
import com.oracle.bmc.queue.QueueClient;
import com.oracle.bmc.queue.model.CreateQueueDetails;
import com.oracle.bmc.queue.model.PutMessagesDetails;
import com.oracle.bmc.queue.model.PutMessagesDetailsEntry;
import com.oracle.bmc.queue.requests.CreateQueueRequest;
import com.oracle.bmc.queue.requests.DeleteMessageRequest;
import com.oracle.bmc.queue.requests.DeleteQueueRequest;
import com.oracle.bmc.queue.requests.GetMessagesRequest;
import com.oracle.bmc.queue.requests.GetQueueRequest;
import com.oracle.bmc.queue.requests.GetStatsRequest;
import com.oracle.bmc.queue.requests.GetWorkRequestRequest;
import com.oracle.bmc.queue.requests.ListQueuesRequest;
import com.oracle.bmc.queue.requests.PutMessagesRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates the Queue control + data planes against the real oci-java-sdk. */
class QueueCompatibilityTest {

    private static QueueAdminClient admin;
    private static QueueClient data;

    @BeforeAll
    static void setUp() {
        admin = EmulatorFixture.queueAdmin();
        data = EmulatorFixture.queueData();
    }

    @AfterAll
    static void tearDown() {
        admin.close();
        data.close();
    }

    /** Create is work-request driven: no body, opc-work-request-id, then poll. */
    private static String createQueue(String name) {
        var created = admin.createQueue(CreateQueueRequest.builder()
                .createQueueDetails(CreateQueueDetails.builder()
                        .displayName(name)
                        .compartmentId(TENANCY)
                        .build())
                .build());
        assertThat(created.getOpcWorkRequestId()).isNotBlank();

        var workRequest = admin.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(created.getOpcWorkRequestId()).build()).getWorkRequest();
        assertThat(workRequest.getStatus().getValue()).isEqualTo("SUCCEEDED");
        assertThat(workRequest.getTimeFinished()).isNotNull();
        assertThat(workRequest.getResources()).isNotEmpty();
        return workRequest.getResources().get(0).getIdentifier();
    }

    @Test
    void queueLifecycleThroughWorkRequests() {
        String queueId = createQueue("sdk-queue-" + System.nanoTime());

        var queue = admin.getQueue(GetQueueRequest.builder().queueId(queueId).build()).getQueue();
        assertThat(queue.getLifecycleState().getValue()).isEqualTo("ACTIVE");
        assertThat(queue.getMessagesEndpoint()).isNotBlank();
        assertThat(queue.getRetentionInSeconds()).isEqualTo(86400);
        assertThat(queue.getVisibilityInSeconds()).isEqualTo(30);

        // Wrapped collection, unlike Identity's bare arrays.
        var listed = admin.listQueues(ListQueuesRequest.builder()
                .compartmentId(TENANCY).build()).getQueueCollection();
        assertThat(listed.getItems()).extracting(s -> s.getId()).contains(queueId);

        var deleted = admin.deleteQueue(DeleteQueueRequest.builder().queueId(queueId).build());
        assertThat(deleted.getOpcWorkRequestId()).isNotBlank();
    }

    @Test
    void messageRoundtripThroughTheDataPlane() {
        String queueId = createQueue("sdk-messages-" + System.nanoTime());

        var put = data.putMessages(PutMessagesRequest.builder()
                .queueId(queueId)
                .putMessagesDetails(PutMessagesDetails.builder()
                        .messages(List.of(
                                PutMessagesDetailsEntry.builder().content("sdk message one").build(),
                                PutMessagesDetailsEntry.builder().content("sdk message two").build()))
                        .build())
                .build()).getPutMessages();
        assertThat(put.getMessages()).hasSize(2);
        assertThat(put.getMessages().get(0).getId()).isEqualTo(1L);

        var got = data.getMessages(GetMessagesRequest.builder()
                .queueId(queueId).limit(10).timeoutInSeconds(0).build()).getGetMessages();
        assertThat(got.getMessages()).hasSize(2);
        var first = got.getMessages().get(0);
        assertThat(first.getContent()).isEqualTo("sdk message one");
        assertThat(first.getReceipt()).isNotBlank();
        assertThat(first.getDeliveryCount()).isEqualTo(1);
        assertThat(first.getVisibleAfter()).isNotNull();

        var stats = data.getStats(GetStatsRequest.builder().queueId(queueId).build()).getQueueStats();
        assertThat(stats.getQueue().getInFlightMessages()).isEqualTo(2L);
        assertThat(stats.getQueue().getVisibleMessages()).isEqualTo(0L);

        data.deleteMessage(DeleteMessageRequest.builder()
                .queueId(queueId).messageReceipt(first.getReceipt()).build());

        admin.deleteQueue(DeleteQueueRequest.builder().queueId(queueId).build());
    }
}
