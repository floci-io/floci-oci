package io.floci.oci.compat;

import com.oracle.bmc.streaming.StreamAdminClient;
import com.oracle.bmc.streaming.model.CreateCursorDetails;
import com.oracle.bmc.streaming.model.CreateStreamDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetails;
import com.oracle.bmc.streaming.model.PutMessagesDetailsEntry;
import com.oracle.bmc.streaming.model.Stream;
import com.oracle.bmc.streaming.requests.CreateCursorRequest;
import com.oracle.bmc.streaming.requests.CreateStreamRequest;
import com.oracle.bmc.streaming.requests.DeleteStreamRequest;
import com.oracle.bmc.streaming.requests.GetMessagesRequest;
import com.oracle.bmc.streaming.requests.GetStreamRequest;
import com.oracle.bmc.streaming.requests.ListStreamsRequest;
import com.oracle.bmc.streaming.requests.PutMessagesRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates Streaming's control plane and cursor-driven data plane via oci-java-sdk. */
class StreamingCompatibilityTest {

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
}
