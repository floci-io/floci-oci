package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamPoolKafkaManagerTest {

    private static final String DESCRIBE = """
            PARTITION  LEADER  EPOCH  REPLICAS  LOG-START-OFFSET  HIGH-WATERMARK
            0          0       1      [0]       0                 3
            1          0       1      [0]       0                 0
            """;

    @Test
    void highWatermarkReadsThePartitionsRow() {
        assertEquals(3, StreamPoolKafkaManager.highWatermark(DESCRIBE, 0));
        assertEquals(0, StreamPoolKafkaManager.highWatermark(DESCRIBE, 1));
    }

    @Test
    void highWatermarkRejectsAMissingPartition() {
        assertThrows(IllegalStateException.class, () -> StreamPoolKafkaManager.highWatermark(DESCRIBE, 2));
    }

    @Test
    void topicGoneReadsTheStatusOfRpkTopicDelete() {
        assertTrue(StreamPoolKafkaManager.topicGone(new ExecResult(0, "TOPIC  STATUS\nt1     OK\n", "")));
        assertTrue(StreamPoolKafkaManager.topicGone(new ExecResult(0, "TOPIC  STATUS\n"
                + "t1     UNKNOWN_TOPIC_OR_PARTITION: This server does not host this topic-partition.\n", "")));
        assertFalse(StreamPoolKafkaManager.topicGone(new ExecResult(0, "TOPIC  STATUS\n"
                + "t1     REQUEST_TIMED_OUT: The request timed out.\n", "")));
        assertFalse(StreamPoolKafkaManager.topicGone(new ExecResult(1, "", "unable to dial")));
    }

    @Test
    void claimPortKeepsThePreviousPortWhenItCanBeReclaimed() {
        PortAllocator ports = mock(PortAllocator.class);
        when(ports.reclaim(19095)).thenReturn(true);

        assertEquals(19095, manager(ports).claimPort(19095));
        verify(ports, never()).allocate(anyInt(), anyInt());
    }

    @Test
    void claimPortAllocatesAFreshPortWhenThePreviousOneIsTaken() {
        PortAllocator ports = mock(PortAllocator.class);
        when(ports.reclaim(19095)).thenReturn(false);
        when(ports.allocate(19092, 19191)).thenReturn(19100);

        assertEquals(19100, manager(ports).claimPort(19095));
    }

    private static StreamPoolKafkaManager manager(PortAllocator ports) {
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().streaming().kafkaBasePort()).thenReturn(19092);
        when(config.services().streaming().kafkaMaxPort()).thenReturn(19191);
        return new StreamPoolKafkaManager(mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerDetector.class), mock(CurrentContainerNetworkResolver.class), ports,
                mock(KafkaRestClient.class), config);
    }
}
