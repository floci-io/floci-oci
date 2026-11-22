package io.floci.oci.services.monitoring;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.monitoring.model.StoredDatapoint;
import io.floci.oci.services.monitoring.model.StoredMetricStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MqlTest {

    @Test
    void parsesTheDocumentedExamples() {
        Mql.Query query = Mql.parse(
                "CpuUtilization[1m]{availabilityDomain = \"VeBZ:PHX-AD-1\"}.groupBy(poolId).percentile(0.9) > 85");
        Mql.Term term = query.terms().get(0);
        assertEquals("CpuUtilization", term.metric().name());
        assertEquals(Duration.ofMinutes(1), term.metric().interval());
        assertEquals(List.of("poolId"), term.metric().groupBy());
        assertEquals("percentile", term.metric().statistic());
        assertEquals(0.9, term.metric().percentile());
        assertTrue(term.predicate().test(86));
        assertFalse(term.predicate().test(85));

        assertTrue(Mql.parse("CpuUtilization[1m].mean() in (60, 80)").terms().get(0).predicate().test(80));
        assertTrue(Mql.parse("CpuUtilization[1m].mean() not in (60, 80)").terms().get(0).predicate().test(59));
        assertEquals(Duration.ofHours(20), Mql.parse("CpuUtilization[1m]{resourceId = \"x\"}.groupBy(resourceId).absent(20)")
                .terms().get(0).metric().absencePeriod());
        assertEquals(Duration.ofHours(2), Mql.parse("CpuUtilization[1m].absent()").terms().get(0).metric().absencePeriod());
        assertEquals(Duration.ofMinutes(1), Mql.parse("Cpu[1m].absent(1m)").terms().get(0).metric().absencePeriod());
        assertEquals(Duration.ofDays(3), Mql.parse("Cpu[1m].absent(3d)").terms().get(0).metric().absencePeriod());
        assertEquals(List.of("&&"), Mql.parse("Errors[1m].count() > 1 && Errors[1m].mean() > 0.5").joins());
        assertNull(Mql.parse("my_app.success_rate[5m].max()").terms().get(0).predicate());
        assertEquals("my_app.success_rate", Mql.parse("my_app.success_rate[5m].max()").terms().get(0).metric().name());
    }

    @Test
    void arithmeticAppliesOnEitherSide() {
        Mql.Term left = Mql.parse("100 - CpuUtilization[1m].mean()").terms().get(0);
        assertEquals(70, left.arithmetic().apply(30));
        Mql.Term right = Mql.parse("TotalRequestLatency[1m].mean() / 1000").terms().get(0);
        assertEquals(2, right.arithmetic().apply(2000));
    }

    @Test
    void fuzzyDimensionMatching() {
        Mql.Term term = Mql.parse("Cpu[1m]{name =~ \"ol8|ol7\", host =~ \"instance-2023-*\", env != \"prod\"}.min()")
                .terms().get(0);
        Map<String, String> match = Map.of("name", "ol7", "host", "instance-2023-abc", "env", "dev");
        assertTrue(term.metric().filters().stream().allMatch(f -> f.matches(match)));
        Map<String, String> miss = Map.of("name", "ol6", "host", "instance-2023-abc", "env", "dev");
        assertFalse(term.metric().filters().stream().allMatch(f -> f.matches(miss)));
    }

    @Test
    void anyDimensionNameIngestionAcceptsCanBeFiltered() {
        Mql.Term term = Mql.parse("Cpu[1m]{_host = \"web-1\", k8s.io/zone!=\"b\", $x=~\"a*\"}.max()").terms().get(0);
        assertEquals(List.of("_host", "k8s.io/zone", "$x"),
                term.metric().filters().stream().map(Mql.DimensionFilter::key).toList());
        assertTrue(term.metric().filters().stream()
                .allMatch(f -> f.matches(Map.of("_host", "web-1", "k8s.io/zone", "a", "$x", "abc"))));
    }

    @Test
    void invalidQueriesAreInvalidParameter() {
        for (String bad : List.of("Cpu.mean()", "Cpu[7m].unknown()", "Cpu[61m].mean()", "Cpu[1m]", "Cpu[1m].mean() >",
                "Cpu[1m].mean() + Mem[1m].mean()", "Cpu[1m].absent() > 1", "Cpu[1m].percentile(2)",
                "Cpu[1m].mean(123)", "Cpu[2147483648m].mean()", "Cpu[99999999999999999999m].mean()", "Cpu[1m].grouping(host).max()", "Cpu[1m].absent(1, 2)", "Cpu[1m].absent(0)", "Cpu[1m].absent(0m)", "Cpu[1m].absent(73)",
                "Cpu[1m].absent(4d)", "Cpu[1m].absent(3000000000000)", "Cpu[1m].absent(99999999999999999999m)")) {
            OciException e = assertThrows(OciException.class, () -> Mql.parse(bad), bad);
            assertEquals("InvalidParameter", e.getCode(), bad);
        }
    }

    private static StoredMetricStream stream(Map<String, String> dimensions, long... timesAndValues) {
        StoredMetricStream stream = new StoredMetricStream();
        stream.setNamespace("app");
        stream.setName("Cpu");
        stream.setCompartmentId("c");
        stream.setDimensions(dimensions);
        for (int i = 0; i < timesAndValues.length; i += 2) {
            StoredDatapoint point = new StoredDatapoint();
            point.setTimestamp(timesAndValues[i]);
            point.setValue(timesAndValues[i + 1]);
            stream.getDatapoints().add(point);
        }
        return stream;
    }

    @Test
    void engineAggregatesWindowsEndingAtEachLabel() {
        long m = 60_000;
        StoredMetricStream a = stream(Map.of("host", "a"), 10 * m + 1, 10, 10 * m + 2, 30, 11 * m + 1, 50);
        StoredMetricStream b = stream(Map.of("host", "b"), 10 * m + 5, 100);

        List<MetricQueryEngine.Series> mean = MetricQueryEngine.evaluate(Mql.parse("Cpu[1m].mean()").terms().get(0),
                List.of(a, b), 11 * m, 13 * m, Duration.ofMinutes(1));
        assertEquals(2, mean.size());
        assertEquals(Map.of(11 * m, 20.0, 12 * m, 50.0), mean.get(0).points());

        List<MetricQueryEngine.Series> grouped = MetricQueryEngine.evaluate(
                Mql.parse("Cpu[1m].grouping().max()").terms().get(0), List.of(a, b), 11 * m, 12 * m,
                Duration.ofMinutes(1));
        assertEquals(1, grouped.size());
        assertEquals(Map.of(), grouped.get(0).dimensions());
        assertEquals(100.0, grouped.get(0).points().get(11 * m));

        List<MetricQueryEngine.Series> count = MetricQueryEngine.evaluate(
                Mql.parse("Cpu[5m]{host = \"a\"}.count()").terms().get(0), List.of(a, b), 12 * m, 13 * m,
                Duration.ofMinutes(1));
        assertEquals(3.0, count.get(0).points().get(12 * m));
    }

    @Test
    void absentIsOneForAMonitoredStreamWithNoData() {
        long m = 60_000;
        StoredMetricStream a = stream(Map.of("host", "a"), 10 * m, 1);
        List<MetricQueryEngine.Series> absent = MetricQueryEngine.evaluate(Mql.parse("Cpu[1m].absent()").terms().get(0),
                List.of(a), 10 * m, 13 * m, Duration.ofMinutes(1));
        assertEquals(Map.of(10 * m, 0.0, 11 * m, 1.0, 12 * m, 1.0), absent.get(0).points());
    }

    @Test
    void aOneMinuteAbsenceOnAOneMinuteIntervalFiresThenStops() {
        long m = 60_000;
        StoredMetricStream a = stream(Map.of("host", "a"), 10 * m, 1);
        List<MetricQueryEngine.Series> absent = MetricQueryEngine.evaluate(
                Mql.parse("Cpu[1m].absent(1m)").terms().get(0), List.of(a), 10 * m, 14 * m, Duration.ofMinutes(1));
        assertEquals(Map.of(10 * m, 0.0, 11 * m, 1.0), absent.get(0).points());
    }
}
