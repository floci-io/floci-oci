package io.floci.oci.compat;

import com.oracle.bmc.streaming.StreamAdminClient;
import com.oracle.bmc.streaming.model.CreateCursorDetails;
import com.oracle.bmc.streaming.model.CreateStreamDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;
import com.oracle.bmc.streaming.model.ChangeStreamPoolCompartmentDetails;
import com.oracle.bmc.streaming.model.CreateStreamPoolDetails;
import com.oracle.bmc.streaming.model.KafkaSettings;
import com.oracle.bmc.streaming.model.OperationStatus;
import com.oracle.bmc.streaming.model.Stream;
import com.oracle.bmc.streaming.model.StreamPool;
import com.oracle.bmc.streaming.model.StreamPoolSummary;
import com.oracle.bmc.streaming.model.UpdateStreamPoolDetails;
import com.oracle.bmc.streaming.model.WorkRequest;
import com.oracle.bmc.streaming.requests.CreateCursorRequest;
import com.oracle.bmc.streaming.requests.ChangeStreamPoolCompartmentRequest;
import com.oracle.bmc.streaming.requests.CreateStreamPoolRequest;
import com.oracle.bmc.streaming.requests.CreateStreamRequest;
import com.oracle.bmc.streaming.requests.DeleteStreamPoolRequest;
import com.oracle.bmc.streaming.requests.DeleteStreamRequest;
import com.oracle.bmc.streaming.requests.GetMessagesRequest;
import com.oracle.bmc.streaming.requests.GetStreamPoolRequest;
import com.oracle.bmc.streaming.requests.GetStreamRequest;
import com.oracle.bmc.streaming.requests.GetWorkRequestRequest;
import com.oracle.bmc.streaming.requests.ListStreamPoolsRequest;
import com.oracle.bmc.streaming.requests.ListStreamsRequest;
import com.oracle.bmc.streaming.requests.UpdateStreamPoolRequest;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates Streaming's control plane and cursor-driven data plane via oci-java-sdk. */
class StreamingCompatibilityTest {

    /** With the compose emulator a pool starts a Redpanda broker before it is ACTIVE. */
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private static StreamAdminClient admin;

    @BeforeAll
    static void setUp() {
        admin = EmulatorFixture.streamAdmin();
    }

    @AfterAll
    static void tearDown() {
        admin.close();
    }

    private static Stream createStream(String name, int partitions) {
        var created = admin.createStream(CreateStreamRequest.builder()
                .createStreamDetails(CreateStreamDetails.builder()
                        .name(name)
                        .partitions(partitions)
                        .compartmentId(TENANCY)
                        .build())
                .build());
        // Dual-mode: full body AND a work-request id.
        assertThat(created.getOpcWorkRequestId()).isNotBlank();
        assertThat(created.getStream().getId()).isNotBlank();
        return created.getStream();
    }

    @Test
    void streamLifecycleAndSummaryShape() {
        Stream stream = createStream("sdk-stream-" + System.nanoTime(), 2);
        assertThat(stream.getLifecycleState().getValue()).isEqualTo("ACTIVE");
        assertThat(stream.getMessagesEndpoint()).isNotBlank();
        assertThat(stream.getRetentionInHours()).isEqualTo(24);

        var fetched = admin.getStream(GetStreamRequest.builder()
                .streamId(stream.getId()).build()).getStream();
        // retentionInHours exists on Stream but not on StreamSummary.
        assertThat(fetched.getRetentionInHours()).isNotNull();

        var listed = admin.listStreams(ListStreamsRequest.builder()
                .compartmentId(TENANCY).build()).getItems();
        assertThat(listed).extracting(s -> s.getId()).contains(stream.getId());

        admin.deleteStream(DeleteStreamRequest.builder().streamId(stream.getId()).build());
    }

    @Test
    void produceAndConsumeWithCursors() {
        Stream stream = createStream("sdk-consume-" + System.nanoTime(), 1);

        try (var data = EmulatorFixture.streamData(stream.getMessagesEndpoint())) {
            var put = data.putMessages(PutMessagesRequest.builder()
                    .streamId(stream.getId())
                    .putMessagesDetails(PutMessagesDetails.builder()
                            .messages(List.of(
                                    PutMessagesDetailsEntry.builder()
                                            .key("k1".getBytes(StandardCharsets.UTF_8))
                                            .value("first".getBytes(StandardCharsets.UTF_8))
                                            .build(),
                                    PutMessagesDetailsEntry.builder()
                                            .value("second".getBytes(StandardCharsets.UTF_8))
                                            .build()))
                            .build())
                    .build()).getPutMessagesResult();
            assertThat(put.getFailures()).isEqualTo(0);
            assertThat(put.getEntries()).hasSize(2);
            assertThat(put.getEntries().get(0).getOffset()).isEqualTo(0L);

            var cursor = data.createCursor(CreateCursorRequest.builder()
                    .streamId(stream.getId())
                    .createCursorDetails(CreateCursorDetails.builder()
                            .partition("0")
                            .type(CreateCursorDetails.Type.TrimHorizon)
                            .build())
                    .build()).getCursor();
            assertThat(cursor.getValue()).isNotBlank();

            var messages = data.getMessages(GetMessagesRequest.builder()
                    .streamId(stream.getId()).cursor(cursor.getValue()).limit(10).build());
            assertThat(messages.getItems()).hasSize(2);
            // Message.stream carries the stream NAME, not the OCID.
            assertThat(messages.getItems().get(0).getStream()).isEqualTo(stream.getName());
            assertThat(new String(messages.getItems().get(0).getValue(), StandardCharsets.UTF_8))
                    .isEqualTo("first");
            assertThat(messages.getOpcNextCursor()).isNotBlank();
        }

        admin.deleteStream(DeleteStreamRequest.builder().streamId(stream.getId()).build());
    }

    @Test
    void streamPoolCrudThroughWorkRequests() throws InterruptedException {
        String name = "sdk-pool-" + System.nanoTime();
        var created = admin.createStreamPool(CreateStreamPoolRequest.builder()
                .createStreamPoolDetails(CreateStreamPoolDetails.builder()
                        .name(name)
                        .compartmentId(TENANCY)
                        .kafkaSettings(KafkaSettings.builder().numPartitions(2).build())
                        .build())
                .build());
        assertThat(created.getEtag()).isNotBlank();
        String poolId = created.getStreamPool().getId();
        assertThat(created.getStreamPool().getKafkaSettings()).isNotNull();
        assertThat(created.getStreamPool().getCustomEncryptionKey()).isNotNull();

        WorkRequest createWork = awaitWorkRequest(created.getOpcWorkRequestId());
        assertThat(createWork.getStatus()).isEqualTo(OperationStatus.Succeeded);
        assertThat(createWork.getResources()).anySatisfy(resource -> {
            assertThat(resource.getEntityType()).isEqualTo("stream_pool");
            assertThat(resource.getIdentifier()).isEqualTo(poolId);
        });

        StreamPool pool = admin.getStreamPool(GetStreamPoolRequest.builder()
                .streamPoolId(poolId).build()).getStreamPool();
        assertThat(pool.getLifecycleState()).isEqualTo(StreamPool.LifecycleState.Active);
        assertThat(pool.getKafkaSettings().getNumPartitions()).isEqualTo(2);
        assertThat(pool.getCustomEncryptionKey().getKeyState().getValue()).isEqualTo("NONE");

        List<StreamPoolSummary> listed = admin.listStreamPools(ListStreamPoolsRequest.builder()
                .compartmentId(TENANCY).name(name).build()).getItems();
        assertThat(listed).extracting(StreamPoolSummary::getId).containsExactly(poolId);

        var updated = admin.updateStreamPool(UpdateStreamPoolRequest.builder()
                .streamPoolId(poolId)
                .updateStreamPoolDetails(UpdateStreamPoolDetails.builder()
                        .name(name + "-renamed")
                        .build())
                .build());
        assertThat(updated.getStreamPool().getName()).isEqualTo(name + "-renamed");
        awaitWorkRequest(updated.getOpcWorkRequestId());

        var moved = admin.changeStreamPoolCompartment(ChangeStreamPoolCompartmentRequest.builder()
                .streamPoolId(poolId)
                .changeStreamPoolCompartmentDetails(ChangeStreamPoolCompartmentDetails.builder()
                        .compartmentId(TENANCY).build())
                .build());
        assertThat(moved.getOpcWorkRequestId()).isNotBlank();

        var deleted = admin.deleteStreamPool(DeleteStreamPoolRequest.builder().streamPoolId(poolId).build());
        assertThat(awaitWorkRequest(deleted.getOpcWorkRequestId()).getStatus())
                .isEqualTo(OperationStatus.Succeeded);
    }

    private static WorkRequest awaitWorkRequest(String workRequestId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        WorkRequest workRequest = null;
        while (Instant.now().isBefore(deadline)) {
            workRequest = admin.getWorkRequest(
                    GetWorkRequestRequest.builder().workRequestId(workRequestId).build()).getWorkRequest();
            if (workRequest.getTimeFinished() != null) {
                return workRequest;
            }
            Thread.sleep(1000);
        }
        return workRequest;
    }
}
