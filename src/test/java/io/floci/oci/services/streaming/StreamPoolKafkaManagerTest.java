package io.floci.oci.services.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
