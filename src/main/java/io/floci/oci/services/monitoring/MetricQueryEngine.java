package io.floci.oci.services.monitoring;

import io.floci.oci.services.monitoring.model.StoredDatapoint;
import io.floci.oci.services.monitoring.model.StoredMetricStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evaluates one MQL term over raw metric streams. An aggregated data point labelled {@code t}
 * covers the window {@code (t - interval, t]}, and labels step by the resolution, so a 5-minute
 * interval at a 1-minute resolution gives five-minute aggregations every minute (as
 * summarize_metrics_data_details.go describes {@code resolution}).
 */
final class MetricQueryEngine {

    private MetricQueryEngine() {
    }

    /**
     * One result series. {@code key} identifies it across terms: its dimensions, plus its
     * compartment when the query does not group, so equal dimensions in two compartments stay apart.
     */
    record Series(String key, StoredMetricStream sample, Map<String, String> dimensions, TreeMap<Long, Double> points) {
    }

    /**
     * @param streams the namespace's streams already narrowed to the compartments and resource group
     * @param start   first label, inclusive (epoch millis)
     * @param end     last label, exclusive (epoch millis)
     */
    static List<Series> evaluate(Mql.Term term, List<StoredMetricStream> streams, long start, long end,
                                 Duration resolution) {
        Mql.MetricSelector metric = term.metric();
        long interval = metric.interval().toMillis();
        long step = resolution.toMillis();
        boolean absent = "absent".equals(metric.statistic());
        // The absence detection period runs from when absence begins, the interval after the last point
        // (inferred from mon-mql.md: absent() "is ignored after the absence detection period"). Counting it
        // from the last point instead would make absent(1m) on a 1m interval unable to ever fire.
        long monitored = absent ? interval + metric.absencePeriod().toMillis() : interval;

        Map<String, List<StoredMetricStream>> groups = new LinkedHashMap<>();
        for (StoredMetricStream stream : streams) {
            if (!stream.getName().equals(metric.name())) {
                continue;
            }
            if (!metric.filters().stream().allMatch(f -> f.matches(stream.getDimensions()))) {
                continue;
            }
            String key = MonitoringService.canonicalDimensions(groupKey(stream.getDimensions(), metric.groupBy()))
                    + (metric.groupBy() == null ? "@" + stream.getCompartmentId() : "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(stream);
        }

        List<Series> result = new ArrayList<>();
        long first = Math.floorDiv(start + step - 1, step) * step;
        for (Map.Entry<String, List<StoredMetricStream>> group : groups.entrySet()) {
            // Only points that some window of this query can see; the rest of the retained history is skipped.
            long earliest = first - monitored;
            List<StoredDatapoint> pooled = new ArrayList<>();
            for (StoredMetricStream stream : group.getValue()) {
                for (StoredDatapoint point : stream.getDatapoints()) {
                    if (point.getTimestamp() > earliest && point.getTimestamp() < end) {
                        pooled.add(point);
                    }
                }
            }
            pooled.sort(Comparator.comparingLong(StoredDatapoint::getTimestamp));
            TreeMap<Long, Double> points = new TreeMap<>();
            for (long t = first; t < end; t += step) {
                List<StoredDatapoint> window = between(pooled, t - interval, t);
                Double value;
                if (absent) {
                    // A stream counts as monitored while it reported within the detection period.
                    if (between(pooled, t - monitored, t).isEmpty()) {
                        continue;
                    }
                    value = window.isEmpty() ? 1.0 : 0.0;
                } else {
                    if (window.isEmpty()) {
                        continue;
                    }
                    value = statistic(metric, window, interval);
                }
                if (term.arithmetic() != null) {
                    value = term.arithmetic().apply(value);
                }
                points.put(t, value);
            }
            StoredMetricStream sample = group.getValue().get(0);
            result.add(new Series(group.getKey(), sample, groupKey(sample.getDimensions(), metric.groupBy()), points));
        }
        return result;
    }

    private static Map<String, String> groupKey(Map<String, String> dimensions, List<String> groupBy) {
        if (groupBy == null) {
            return new TreeMap<>(dimensions);
        }
        Map<String, String> key = new TreeMap<>();
        for (String dimension : groupBy) {
            if (dimensions.containsKey(dimension)) {
                key.put(dimension, dimensions.get(dimension));
            }
        }
        return key;
    }

    /** A view of the points with {@code from < timestamp <= to}; {@code sorted} is ordered by timestamp. */
    static List<StoredDatapoint> between(List<StoredDatapoint> sorted, long from, long to) {
        return sorted.subList(firstAfter(sorted, from), firstAfter(sorted, to));
    }

    /** The index of the first point stamped after {@code time}, by binary search. */
    private static int firstAfter(List<StoredDatapoint> sorted, long time) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).getTimestamp() > time) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    static double statistic(Mql.MetricSelector metric, List<StoredDatapoint> window, long intervalMillis) {
        double sum = 0;
        long count = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (StoredDatapoint point : window) {
            int weight = Math.max(point.getCount(), 1);
            sum += point.getValue() * weight;
            count += weight;
            min = Math.min(min, point.getValue());
            max = Math.max(max, point.getValue());
        }
        double firstValue = window.get(0).getValue();
        double lastValue = window.get(window.size() - 1).getValue();
        return switch (metric.statistic()) {
            case "mean", "avg" -> sum / count;
            case "sum" -> sum;
            case "count" -> count;
            case "min" -> min;
            case "max" -> max;
            case "first" -> firstValue;
            case "last" -> lastValue;
            case "increment" -> lastValue - firstValue;
            case "rate" -> (lastValue - firstValue) / (intervalMillis / 1000.0);
            default -> percentile(window, metric.percentile());
        };
    }

    /** Nearest-rank percentile, with each value weighted by its count. */
    private static double percentile(List<StoredDatapoint> window, double p) {
        List<StoredDatapoint> sorted = new ArrayList<>(window);
        sorted.sort(Comparator.comparingDouble(StoredDatapoint::getValue));
        long total = 0;
        for (StoredDatapoint point : sorted) {
            total += Math.max(point.getCount(), 1);
        }
        long rank = Math.max(1, (long) Math.ceil(p * total));
        long seen = 0;
        for (StoredDatapoint point : sorted) {
            seen += Math.max(point.getCount(), 1);
            if (seen >= rank) {
                return point.getValue();
            }
        }
        return sorted.get(sorted.size() - 1).getValue();
    }
}
