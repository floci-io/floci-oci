package io.floci.oci.services.monitoring;

import io.floci.oci.services.monitoring.model.StoredDatapoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricQueryEngineTest {

    private static List<StoredDatapoint> points(long... timestamps) {
        return Arrays.stream(timestamps).mapToObj(t -> {
            StoredDatapoint point = new StoredDatapoint();
            point.setTimestamp(t);
            return point;
        }).toList();
    }

    private static List<Long> stamps(List<StoredDatapoint> window) {
        return window.stream().map(StoredDatapoint::getTimestamp).toList();
    }

    @Test
    void aWindowExcludesItsStartAndIncludesItsEnd() {
        List<StoredDatapoint> sorted = points(10, 20, 20, 30, 40);

        assertEquals(List.of(20L, 20L, 30L), stamps(MetricQueryEngine.between(sorted, 10, 30)));
        assertEquals(List.of(10L), stamps(MetricQueryEngine.between(sorted, 0, 10)));
        assertEquals(List.of(), stamps(MetricQueryEngine.between(sorted, 40, 100)));
        assertEquals(List.of(), stamps(MetricQueryEngine.between(sorted, 21, 29)));
        assertEquals(List.of(), stamps(MetricQueryEngine.between(points(), 0, 100)));
    }
}
