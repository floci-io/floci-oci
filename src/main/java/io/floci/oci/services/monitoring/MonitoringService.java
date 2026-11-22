package io.floci.oci.services.monitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.common.Wildcards;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.monitoring.model.StoredAlarm;
import io.floci.oci.services.monitoring.model.StoredAlarmSuppression;
import io.floci.oci.services.monitoring.model.StoredDatapoint;
import io.floci.oci.services.monitoring.model.StoredMetricStream;
import io.floci.oci.services.notifications.NotificationsService;
import io.floci.oci.services.streaming.StreamingService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OCI Monitoring, API version {@code 20180401} (oci-go-sdk monitoring/monitoring_client.go): custom
 * metrics (PostMetricData, SummarizeMetricsData with MQL, ListMetrics), alarms and alarm
 * suppressions. Alarms are evaluated by {@link AlarmEvaluator}.
 */
@ApplicationScoped
public class MonitoringService implements CompartmentUsage, Resettable {

    private static final Logger LOG = Logger.getLogger(MonitoringService.class);

    static final Set<String> SEVERITIES = Set.of("CRITICAL", "ERROR", "WARNING", "INFO");
    static final Set<String> MESSAGE_FORMATS = Set.of("RAW", "PRETTY_JSON", "ONS_OPTIMIZED");
    /** "less than two hours in the past and less than 10 minutes in the future" (monitoring/datapoint.go). */
    static final Duration MAX_PAST = Duration.ofHours(2);
    static final Duration MAX_FUTURE = Duration.ofMinutes(10);
    /** How long raw data points are kept: floci-oci's choice, the API does not state one. */
    static final Duration RETENTION = Duration.ofDays(14);
    /** Default SummarizeMetricsData window: "the timestamp 3 hours before the call was sent". */
    static final Duration DEFAULT_QUERY_WINDOW = Duration.ofHours(3);
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-$]*");
    private static final Pattern RESOURCE_GROUP = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-$]*");
    private static final Pattern NOTIFICATION_VERSION = Pattern.compile("[0-9]{1,4}\\.X");
    private static final List<String> SPEC_FIELDS = List.of("displayName", "compartmentId", "metricCompartmentId",
            "metricCompartmentIdInSubtree", "namespace", "resourceGroup", "query", "resolution", "pendingDuration",
            "severity", "body", "isNotificationsPerMetricDimensionEnabled", "messageFormat", "destinations",
            "repeatNotificationDuration", "suppression", "isEnabled", "freeformTags", "definedTags", "overrides",
            "ruleName", "notificationVersion", "notificationTitle", "evaluationSlackDuration", "alarmSummary");

    private final StorageBackend<String, StoredMetricStream> metrics;
    private final StorageBackend<String, StoredAlarm> alarms;
    private final StorageBackend<String, StoredAlarmSuppression> suppressions;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final NotificationsService notifications;
    private final StreamingService streaming;

    @Inject
    public MonitoringService(StorageFactory storageFactory, EmulatorConfig config, OciContext ociContext,
                             ServiceRegistry serviceRegistry, NotificationsService notifications,
                             StreamingService streaming) {
        this(storageFactory.create("monitoring", "monitoring-metrics.json",
                        new TypeReference<Map<String, StoredMetricStream>>() {}),
                storageFactory.create("monitoring", "monitoring-alarms.json",
                        new TypeReference<Map<String, StoredAlarm>>() {}),
                storageFactory.create("monitoring", "monitoring-alarm-suppressions.json",
                        new TypeReference<Map<String, StoredAlarmSuppression>>() {}),
                config, ociContext, serviceRegistry, notifications, streaming);
    }

    MonitoringService(StorageBackend<String, StoredMetricStream> metrics, StorageBackend<String, StoredAlarm> alarms,
                      StorageBackend<String, StoredAlarmSuppression> suppressions, EmulatorConfig config,
                      OciContext ociContext, ServiceRegistry serviceRegistry, NotificationsService notifications,
                      StreamingService streaming) {
        this.metrics = metrics;
        this.alarms = alarms;
        this.suppressions = suppressions;
        this.config = config;
        this.ociContext = ociContext != null ? ociContext : OciContext.fromConfig(config);
        this.serviceRegistry = serviceRegistry;
        this.notifications = notifications;
        this.streaming = streaming;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("monitoring")
                .enabled(config.services().monitoring().enabled())
                .storageKey("monitoring")
                .resourceClasses(MonitoringController.class)
                .build());
    }

    StorageBackend<String, StoredMetricStream> metricStore() {
        return metrics;
    }

    StorageBackend<String, StoredAlarm> alarmStore() {
        return alarms;
    }

    StorageBackend<String, StoredAlarmSuppression> suppressionStore() {
        return suppressions;
    }

    // ── Metrics ────────────────────────────────────────────────────────────────

    public record FailedMetric(String message, Map<String, Object> metricData) {
    }

    /**
     * PostMetricData. Each metric object is validated on its own; a rejected one is reported in
     * {@code failedMetrics}. With {@code batchAtomicity} {@code ATOMIC}, one rejection rejects them all.
     */
    public List<FailedMetric> postMetricData(Map<String, Object> body) {
        return postMetricData(body, compartmentId -> true);
    }

    /** As {@link #postMetricData(Map)}; a metric whose compartment {@code knownCompartment} rejects fails. */
    public synchronized List<FailedMetric> postMetricData(Map<String, Object> body, Predicate<String> knownCompartment) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (!(b.get("metricData") instanceof List<?> items) || items.isEmpty()) {
            throw OciException.missingParameter("metricData is required");
        }
        String atomicity = b.get("batchAtomicity") instanceof String a ? a : "NON_ATOMIC";
        if (!Set.of("ATOMIC", "NON_ATOMIC").contains(atomicity)) {
            throw OciException.invalidParameter("batchAtomicity must be ATOMIC or NON_ATOMIC, got " + atomicity);
        }
        long now = System.currentTimeMillis();
        List<FailedMetric> failed = new ArrayList<>();
        List<StoredMetricStream> accepted = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> metric = asMap(item);
            try {
                StoredMetricStream stream = validMetric(metric, now);
                if (!knownCompartment.test(stream.getCompartmentId())) {
                    throw OciException.relatedResourceNotAuthorizedOrNotFound(
                            "Authorization failed or requested resource not found: compartment " + stream.getCompartmentId());
                }
                accepted.add(stream);
            } catch (OciException e) {
                failed.add(new FailedMetric(e.getMessage(), metric));
            }
        }
        if ("ATOMIC".equals(atomicity) && !failed.isEmpty()) {
            List<FailedMetric> all = new ArrayList<>();
            for (Object item : items) {
                Map<String, Object> metric = asMap(item);
                all.add(failed.stream().filter(f -> f.metricData() == metric).findFirst()
                        .orElse(new FailedMetric("Rejected with the batch: another metric object is invalid",
                                metric)));
            }
            return all;
        }
        // Copy on write: queries and the alarm evaluator read stored streams without this lock,
        // so a stored stream's lists are never changed in place.
        for (StoredMetricStream incoming : accepted) {
            String key = streamKey(incoming);
            StoredMetricStream stream = metrics.get(key).map(existing -> merged(existing, incoming)).orElse(incoming);
            long cutoff = now - RETENTION.toMillis();
            stream.getDatapoints().removeIf(p -> p.getTimestamp() < cutoff);
            stream.getDatapoints().sort(Comparator.comparingLong(StoredDatapoint::getTimestamp));
            metrics.put(key, stream);
        }
        return failed;
    }

    private StoredMetricStream validMetric(Map<String, Object> metric, long now) {
        String namespace = requireString(metric, "namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw OciException.invalidParameter("Invalid namespace " + namespace
                    + ": it must start with a letter and contain only letters, digits and underscores");
        }
        if (namespace.startsWith("oci_") || namespace.startsWith("oracle_")) {
            throw OciException.invalidParameter("Custom metrics can't use the reserved namespace prefixes oci_ and oracle_");
        }
        String name = requireString(metric, "name");
        if (!METRIC_NAME.matcher(name).matches()) {
            throw OciException.invalidParameter("Invalid metric name " + name);
        }
        String compartmentId = requireString(metric, "compartmentId");
        String resourceGroup = str(metric, "resourceGroup");
        if (resourceGroup != null && !RESOURCE_GROUP.matcher(resourceGroup).matches()) {
            throw OciException.invalidParameter("Invalid resourceGroup " + resourceGroup);
        }
        Map<String, String> dimensions = stringMap(metric.get("dimensions"));
        if (dimensions.isEmpty() || dimensions.size() > 20) {
            throw OciException.invalidParameter("A metric needs between 1 and 20 dimensions");
        }
        for (Map.Entry<String, String> d : dimensions.entrySet()) {
            if (d.getKey().isEmpty() || d.getValue() == null || d.getValue().isEmpty()
                    || d.getKey().length() > 256 || d.getValue().length() > 512 || d.getKey().contains(" ")) {
                throw OciException.invalidParameter("Invalid dimension " + d.getKey());
            }
        }
        if (!(metric.get("datapoints") instanceof List<?> points) || points.isEmpty()) {
            throw OciException.missingParameter("datapoints is required");
        }
        StoredMetricStream stream = new StoredMetricStream();
        stream.setNamespace(namespace);
        stream.setName(name);
        stream.setCompartmentId(compartmentId);
        stream.setResourceGroup(resourceGroup);
        stream.setDimensions(new TreeMap<>(dimensions));
        stream.setMetadata(stringMap(metric.get("metadata")));
        for (Object raw : points) {
            Map<String, Object> point = asMap(raw);
            long timestamp = parseTime(requireString(point, "timestamp"), "timestamp").toEpochMilli();
            if (timestamp < now - MAX_PAST.toMillis() || timestamp > now + MAX_FUTURE.toMillis()) {
                throw OciException.invalidParameter("Data point timestamp " + point.get("timestamp")
                        + " must be less than two hours in the past and less than 10 minutes in the future");
            }
            if (!(point.get("value") instanceof Number value)) {
                throw OciException.missingParameter("datapoints[].value is required");
            }
            StoredDatapoint datapoint = new StoredDatapoint();
            datapoint.setTimestamp(timestamp);
            datapoint.setValue(value.doubleValue());
            datapoint.setCount(point.get("count") instanceof Number count ? Math.max(1, count.intValue()) : 1);
            stream.getDatapoints().add(datapoint);
        }
        return stream;
    }

    private static StoredMetricStream merged(StoredMetricStream existing, StoredMetricStream incoming) {
        List<StoredDatapoint> datapoints = new ArrayList<>(existing.getDatapoints());
        datapoints.addAll(incoming.getDatapoints());
        StoredMetricStream stream = copyOf(existing, datapoints);
        stream.getMetadata().putAll(incoming.getMetadata());
        return stream;
    }

    private static StoredMetricStream copyOf(StoredMetricStream existing, List<StoredDatapoint> datapoints) {
        StoredMetricStream stream = new StoredMetricStream();
        stream.setNamespace(existing.getNamespace());
        stream.setName(existing.getName());
        stream.setCompartmentId(existing.getCompartmentId());
        stream.setResourceGroup(existing.getResourceGroup());
        stream.setDimensions(new TreeMap<>(existing.getDimensions()));
        stream.setMetadata(new LinkedHashMap<>(existing.getMetadata()));
        stream.setDatapoints(datapoints);
        return stream;
    }

    /**
     * Drops the current partition's points older than the retention period, and streams left with none.
     * A write trims only its own stream, so a stream that stopped reporting is expired here.
     */
    synchronized void expireMetrics(long now) {
        long cutoff = now - RETENTION.toMillis();
        for (StoredMetricStream stream : metrics.scan(k -> true)) {
            if (stream.getDatapoints().stream().noneMatch(p -> p.getTimestamp() < cutoff)) {
                continue;
            }
            List<StoredDatapoint> kept = new ArrayList<>(stream.getDatapoints());
            kept.removeIf(p -> p.getTimestamp() < cutoff);
            if (kept.isEmpty()) {
                metrics.delete(streamKey(stream));
            } else {
                metrics.put(streamKey(stream), copyOf(stream, kept));
            }
        }
    }

    /**
     * Clears Monitoring's stores under the same lock as expiry and alarm evaluation, so a cleanup that
     * read a stream before {@code POST /_floci-oci/state/reset} cannot write it back afterwards.
     */
    @Override
    public synchronized void clear() {
        metrics.clear();
        alarms.clear();
        suppressions.clear();
    }

    private static String streamKey(StoredMetricStream stream) {
        String canonical = canonical(stream.getNamespace()) + canonical(stream.getCompartmentId())
                + canonical(stream.getName()) + canonical(Objects.toString(stream.getResourceGroup(), ""))
                + canonicalDimensions(stream.getDimensions());
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * An unambiguous encoding of a dimension set: each key and value is length-prefixed, so
     * {@code {"host":"a, zone=b"}} and {@code {"host":"a","zone":"b"}} never collide.
     */
    static String canonicalDimensions(Map<String, String> dimensions) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> d : new TreeMap<>(dimensions).entrySet()) {
            canonical.append(canonical(d.getKey())).append(canonical(d.getValue()));
        }
        return canonical.toString();
    }

    private static String canonical(String value) {
        String text = Objects.toString(value, "");
        return text.length() + ":" + text;
    }

    /** The streams a query in {@code compartmentId} may read, by namespace and resource group. */
    List<StoredMetricStream> streams(String compartmentId, boolean inSubtree, String namespace, String resourceGroup) {
        return metrics.scan(k -> true).stream()
                .filter(s -> inSubtree || compartmentId.equals(s.getCompartmentId()))
                .filter(s -> namespace == null || namespace.equals(s.getNamespace()))
                .filter(s -> Objects.equals(resourceGroup, s.getResourceGroup()))
                .sorted(Comparator.comparing((StoredMetricStream s) -> s.getName())
                        .thenComparing(s -> canonicalDimensions(s.getDimensions())))
                .toList();
    }

    /** "The parameter can only be set to true when compartmentId is the tenancy OCID" (list_metrics_request_response.go). */
    boolean subtree(String compartmentId, Boolean inSubtree) {
        if (!Boolean.TRUE.equals(inSubtree)) {
            return false;
        }
        if (!compartmentId.equals(ociContext.tenancyId())) {
            throw OciException.invalidParameter("compartmentIdInSubtree can only be true when compartmentId is the tenancy");
        }
        return true;
    }

    public record SeriesResult(StoredMetricStream sample, Map<String, String> dimensions, String resolution,
                               TreeMap<Long, Double> points) {
    }

    public List<SeriesResult> summarizeMetricsData(String compartmentId, Boolean inSubtree, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String namespace = requireString(b, "namespace");
        Mql.Query query = Mql.parse(requireString(b, "query"));
        boolean subtree = subtree(compartmentId, inSubtree);
        Instant end = b.get("endTime") instanceof String e ? parseTime(e, "endTime") : Instant.now();
        Instant start = b.get("startTime") instanceof String s ? parseTime(s, "startTime")
                : end.minus(DEFAULT_QUERY_WINDOW);
        if (!start.isBefore(end)) {
            throw OciException.invalidParameter("startTime must be before endTime");
        }
        String resolutionText = b.get("resolution") instanceof String r ? r : "1m";
        Duration resolution = Mql.interval(resolutionText);
        for (Mql.Term term : query.terms()) {
            if (resolution.compareTo(term.metric().interval()) > 0) {
                throw OciException.invalidParameter("The resolution must be equal or less than the interval in the query");
            }
        }
        // "The maximum time range is calculated using the current time, regardless of any specified end
        // time" (mql.htm): 7 days below a 5 minute resolution, 30 days below an hour, else 90 days.
        // Data is never stamped more than 10 minutes ahead, and an aggregate stamped t covers
        // (t - interval, t], so no label later than that plus the longest interval can hold data.
        Duration longestInterval = query.terms().stream().map(t -> t.metric().interval())
                .max(Comparator.naturalOrder()).orElse(resolution);
        Instant now = Instant.now();
        Duration maxRange = resolution.compareTo(Duration.ofMinutes(5)) < 0 ? Duration.ofDays(7)
                : resolution.compareTo(Duration.ofHours(1)) < 0 ? Duration.ofDays(30) : Duration.ofDays(90);
        if (start.isBefore(now.minus(maxRange))) {
            start = now.minus(maxRange);
        }
        Instant latest = now.plus(MAX_FUTURE).plus(longestInterval).plus(resolution);
        if (end.isAfter(latest)) {
            end = latest;
        }
        if (!start.isBefore(end)) {
            return List.of();
        }
        List<StoredMetricStream> candidates = streams(compartmentId, subtree, namespace, str(b, "resourceGroup"));
        // Joined terms "act as a single query" (mql.htm, Join Queries), applied left to right: a point
        // survives && only where the other term also has one for the same dimensions and time, and
        // || keeps the points of either term.
        LinkedHashMap<String, SeriesResult> results = null;
        for (int i = 0; i < query.terms().size(); i++) {
            Mql.Term term = query.terms().get(i);
            LinkedHashMap<String, SeriesResult> termResults = new LinkedHashMap<>();
            for (MetricQueryEngine.Series series : MetricQueryEngine.evaluate(term, candidates,
                    start.toEpochMilli(), end.toEpochMilli(), resolution)) {
                TreeMap<Long, Double> points = series.points();
                if (term.predicate() != null) {
                    points.values().removeIf(v -> !term.predicate().test(v));
                }
                if (!points.isEmpty()) {
                    termResults.put(series.key(),
                            new SeriesResult(series.sample(), series.dimensions(), resolutionText, points));
                }
            }
            results = results == null ? termResults : join(results, termResults, query.joins().get(i - 1));
        }
        return results == null ? List.of() : new ArrayList<>(results.values());
    }

    private static LinkedHashMap<String, SeriesResult> join(LinkedHashMap<String, SeriesResult> left,
                                                            LinkedHashMap<String, SeriesResult> right, String operator) {
        LinkedHashMap<String, SeriesResult> joined = new LinkedHashMap<>();
        if ("&&".equals(operator)) {
            for (Map.Entry<String, SeriesResult> entry : left.entrySet()) {
                SeriesResult other = right.get(entry.getKey());
                if (other == null) {
                    continue;
                }
                TreeMap<Long, Double> points = new TreeMap<>(entry.getValue().points());
                points.keySet().retainAll(other.points().keySet());
                if (!points.isEmpty()) {
                    SeriesResult result = entry.getValue();
                    joined.put(entry.getKey(), new SeriesResult(result.sample(), result.dimensions(),
                            result.resolution(), points));
                }
            }
            return joined;
        }
        joined.putAll(left);
        for (Map.Entry<String, SeriesResult> entry : right.entrySet()) {
            SeriesResult existing = joined.get(entry.getKey());
            if (existing == null) {
                joined.put(entry.getKey(), entry.getValue());
            } else {
                TreeMap<Long, Double> points = new TreeMap<>(existing.points());
                entry.getValue().points().forEach(points::putIfAbsent);
                joined.put(entry.getKey(), new SeriesResult(existing.sample(), existing.dimensions(),
                        existing.resolution(), points));
            }
        }
        return joined;
    }

    public List<Map<String, Object>> listMetrics(String compartmentId, Boolean inSubtree, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean subtree = subtree(compartmentId, inSubtree);
        String name = str(b, "name");

        String namespace = str(b, "namespace");
        String resourceGroup = str(b, "resourceGroup");
        Map<String, String> dimensionFilters = stringMap(b.get("dimensionFilters"));
        List<String> groupBy = b.get("groupBy") instanceof List<?> g
                ? g.stream().map(String::valueOf).toList() : List.of();
        for (String field : groupBy) {
            if (!Set.of("namespace", "name", "resourceGroup").contains(field)) {
                throw OciException.invalidParameter("groupBy supports namespace, name and resourceGroup, got " + field);
            }
        }
        Set<Map<String, Object>> seen = new LinkedHashSet<>();
        for (StoredMetricStream s : metrics.scan(k -> true)) {
            if (!subtree && !compartmentId.equals(s.getCompartmentId())
                    || name != null && !Wildcards.matches(s.getName(), name)
                    || namespace != null && !namespace.equals(s.getNamespace())
                    || resourceGroup != null && !resourceGroup.equals(s.getResourceGroup())) {
                continue;
            }
            // "If groupBy is used, then dimensionFilters is ignored."
            if (groupBy.isEmpty() && !dimensionFilters.entrySet().stream()
                    .allMatch(f -> f.getValue().equals(s.getDimensions().get(f.getKey())))) {
                continue;
            }
            Map<String, Object> metric = new LinkedHashMap<>();
            if (groupBy.isEmpty() || groupBy.contains("name")) {
                metric.put("name", s.getName());
            }
            if (groupBy.isEmpty() || groupBy.contains("namespace")) {
                metric.put("namespace", s.getNamespace());
            }
            if ((groupBy.isEmpty() || groupBy.contains("resourceGroup")) && s.getResourceGroup() != null) {
                metric.put("resourceGroup", s.getResourceGroup());
            }
            if (groupBy.isEmpty()) {
                metric.put("compartmentId", s.getCompartmentId());
                metric.put("dimensions", s.getDimensions());
            }
            seen.add(metric);
        }
        String sortBy = str(b, "sortBy");
        String sortField = "NAMESPACE".equals(sortBy) ? "namespace" : "RESOURCEGROUP".equals(sortBy) ? "resourceGroup" : "name";
        Comparator<Map<String, Object>> order = Comparator.comparing(m -> Objects.toString(m.get(sortField), ""));
        if ("DESC".equals(str(b, "sortOrder"))) {
            order = order.reversed();
        }
        return seen.stream().sorted(order.thenComparing(m -> String.valueOf(m.get("dimensions")))).toList();
    }

    // ── Alarms ─────────────────────────────────────────────────────────────────

    public synchronized StoredAlarm createAlarm(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        for (String field : List.of("displayName", "compartmentId", "metricCompartmentId", "namespace", "query",
                "severity")) {
            requireString(b, field);
        }
        if (!(b.get("destinations") instanceof List<?>)) {
            throw OciException.missingParameter("destinations is required");
        }
        if (!(b.get("isEnabled") instanceof Boolean)) {
            throw OciException.missingParameter("isEnabled is required");
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("resolution", "1m");
        spec.put("pendingDuration", "PT1M");
        spec.put("metricCompartmentIdInSubtree", false);
        spec.put("isNotificationsPerMetricDimensionEnabled", false);
        spec.put("messageFormat", "RAW");
        spec.put("ruleName", "BASE");
        spec.put("notificationVersion", "1.X");
        spec.put("evaluationSlackDuration", "PT3M");
        spec.put("freeformTags", new LinkedHashMap<>());
        spec.put("definedTags", new LinkedHashMap<>());
        applySpec(spec, b);
        validateSpec(spec);

        StoredAlarm alarm = new StoredAlarm();
        alarm.setId(Ocids.generate("alarm", ociContext.realm(), ociContext.regionCode()));
        alarm.setCompartmentId((String) spec.get("compartmentId"));
        alarm.setSpec(spec);
        alarm.setLifecycleState("ACTIVE");
        String now = Instant.now().toString();
        alarm.setTimeCreated(now);
        alarm.setTimeUpdated(now);
        alarm.setTimestampTriggered(now);
        alarm.setEtag(Etags.newEtag());
        alarms.put(alarm.getId(), alarm);
        LOG.infov("createAlarm {0} ({1})", spec.get("displayName"), alarm.getId());
        return alarm;
    }

    /**
     * Stores the alarm evaluator's result, unless the alarm changed while it was being evaluated:
     * updated (a new etag), deleted, or gone because state was reset. Returns whether it was stored.
     */
    /** A copy of the alarm taken under the write lock, so its settings and etag belong together. */
    synchronized StoredAlarm snapshot(StoredAlarm alarm, UnaryOperator<StoredAlarm> copier) {
        return copier.apply(alarm);
    }

    synchronized boolean commitEvaluation(StoredAlarm evaluated, String etagAtStart) {
        Optional<StoredAlarm> stored = alarms.get(evaluated.getId());
        if (stored.isEmpty() || !"ACTIVE".equals(stored.get().getLifecycleState())
                || !Objects.equals(etagAtStart, stored.get().getEtag())) {
            return false;
        }
        alarms.put(evaluated.getId(), evaluated);
        return true;
    }

    public StoredAlarm getAlarm(String alarmId) {
        return alarms.get(alarmId).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: alarm " + alarmId));
    }

    private StoredAlarm liveAlarm(String alarmId) {
        StoredAlarm alarm = getAlarm(alarmId);
        if ("DELETED".equals(alarm.getLifecycleState())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: alarm " + alarmId);
        }
        return alarm;
    }

    public List<StoredAlarm> listAlarms(String compartmentId, Boolean inSubtree, String displayName,
                                        String lifecycleState, String sortBy, String sortOrder) {
        boolean subtree = subtree(compartmentId, inSubtree);
        Comparator<StoredAlarm> order = "severity".equals(sortBy)
                ? Comparator.comparingInt(a -> severityRank((String) a.getSpec().get("severity")))
                : Comparator.comparing(a -> String.valueOf(a.getSpec().get("displayName")));
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return alarms.scan(k -> true).stream()
                .filter(a -> subtree || compartmentId.equals(a.getCompartmentId()))
                .filter(a -> lifecycleState != null ? lifecycleState.equals(a.getLifecycleState())
                        : !"DELETED".equals(a.getLifecycleState()))
                .filter(a -> displayName == null || displayName.equals(a.getSpec().get("displayName")))
                .sorted(order)
                .toList();
    }

    public synchronized StoredAlarm updateAlarm(String alarmId, Map<String, Object> body, String ifMatch) {
        StoredAlarm alarm = liveAlarm(alarmId);
        Etags.checkIfMatch(ifMatch, alarm.getEtag());
        Map<String, Object> spec = new LinkedHashMap<>(alarm.getSpec());
        applySpec(spec, body == null ? Map.of() : body);
        validateSpec(spec);
        alarm.setSpec(spec);
        alarm.setCompartmentId((String) spec.get("compartmentId"));
        alarm.setTimeUpdated(Instant.now().toString());
        alarm.setEtag(Etags.newEtag());
        alarms.put(alarmId, alarm);
        return alarm;
    }

    public synchronized void deleteAlarm(String alarmId, String ifMatch) {
        StoredAlarm alarm = liveAlarm(alarmId);
        Etags.checkIfMatch(ifMatch, alarm.getEtag());
        alarm.setLifecycleState("DELETED");
        alarm.setTimeUpdated(Instant.now().toString());
        alarm.setEtag(Etags.newEtag());
        alarms.put(alarmId, alarm);
    }

    public synchronized StoredAlarm changeAlarmCompartment(String alarmId, String compartmentId, String ifMatch) {
        StoredAlarm alarm = liveAlarm(alarmId);
        Etags.checkIfMatch(ifMatch, alarm.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        alarm.getSpec().put("compartmentId", compartmentId);
        alarm.setCompartmentId(compartmentId);
        alarm.setTimeUpdated(Instant.now().toString());
        alarm.setEtag(Etags.newEtag());
        alarms.put(alarmId, alarm);
        return alarm;
    }

    public synchronized void removeAlarmSuppression(String alarmId, String ifMatch) {
        StoredAlarm alarm = liveAlarm(alarmId);
        Etags.checkIfMatch(ifMatch, alarm.getEtag());
        alarm.getSpec().remove("suppression");
        alarm.setTimeUpdated(Instant.now().toString());
        alarm.setEtag(Etags.newEtag());
        alarms.put(alarmId, alarm);
    }

    private void applySpec(Map<String, Object> spec, Map<String, Object> body) {
        for (String field : SPEC_FIELDS) {
            if (body.containsKey(field)) {
                Object value = body.get(field);
                if (value == null) {
                    spec.remove(field);
                } else {
                    spec.put(field, value);
                }
            }
        }
    }

    /**
     * "The query must specify a metric, statistic, interval, and trigger rule (threshold or absence)", for the
     * alarm and for each override (alarm_override.go, create_alarm_details.go).
     */
    private static void requireTriggerRule(Mql.Query query) {
        if (!query.hasPredicate() && query.terms().stream().noneMatch(t -> "absent".equals(t.metric().statistic()))) {
            throw OciException.invalidParameter("An alarm query needs a condition, such as > 80, or absent()");
        }
    }

    private void validateSpec(Map<String, Object> spec) {
        // An update can set a field to null; the required ones must survive it.
        for (String field : List.of("displayName", "compartmentId", "metricCompartmentId", "namespace", "query",
                "severity", "destinations", "isEnabled")) {
            if (spec.get(field) == null) {
                throw OciException.missingParameter(field + " is required");
            }
        }
        // "The parameter can only be set to true when metricCompartmentId is the tenancy OCID"
        // (create_alarm_details.go).
        if (Boolean.TRUE.equals(spec.get("metricCompartmentIdInSubtree"))
                && !ociContext.tenancyId().equals(spec.get("metricCompartmentId"))) {
            throw OciException.invalidParameter(
                    "metricCompartmentIdInSubtree can only be true when metricCompartmentId is the tenancy");
        }
        if (!SEVERITIES.contains(spec.get("severity"))) {
            throw OciException.invalidParameter("severity must be one of " + SEVERITIES);
        }
        requireTriggerRule(Mql.parse((String) spec.get("query")));
        if (!"1m".equals(spec.get("resolution"))) {
            throw OciException.invalidParameter("The only supported alarm resolution is 1m");
        }
        if (spec.get("messageFormat") != null && !MESSAGE_FORMATS.contains(spec.get("messageFormat"))) {
            throw OciException.invalidParameter("messageFormat must be one of " + MESSAGE_FORMATS);
        }
        if (spec.get("notificationVersion") instanceof String version
                && !NOTIFICATION_VERSION.matcher(version).matches()) {
            throw OciException.invalidParameter("notificationVersion must look like 1.X");
        }
        checkDuration(spec, "pendingDuration", Duration.ofMinutes(1), Duration.ofHours(1));
        checkDuration(spec, "repeatNotificationDuration", Duration.ofMinutes(1), Duration.ofDays(30));
        checkDuration(spec, "evaluationSlackDuration", Duration.ofMinutes(3), Duration.ofHours(2));
        if (spec.get("suppression") instanceof Map<?, ?> suppression) {
            Instant from = parseTime(String.valueOf(suppression.get("timeSuppressFrom")), "suppression.timeSuppressFrom");
            Instant until = parseTime(String.valueOf(suppression.get("timeSuppressUntil")), "suppression.timeSuppressUntil");
            if (!from.isBefore(until)) {
                throw OciException.invalidParameter("suppression.timeSuppressFrom must be before timeSuppressUntil");
            }
        }
        if (spec.get("overrides") instanceof List<?> overrides) {
            for (Object raw : overrides) {
                Map<String, Object> override = asMap(raw);
                if (override.get("query") instanceof String q) {
                    requireTriggerRule(Mql.parse(q));
                }
                if (override.get("severity") != null && !SEVERITIES.contains(override.get("severity"))) {
                    throw OciException.invalidParameter("overrides[].severity must be one of " + SEVERITIES);
                }
                checkDuration(override, "pendingDuration", Duration.ofMinutes(1), Duration.ofHours(1));
            }
        }
        validateDestinations(spec.get("destinations"));
    }

    /** "Supported destination services: Notifications, Streaming. Limit: One destination per supported destination service." */
    private void validateDestinations(Object value) {
        if (!(value instanceof List<?> destinations) || destinations.isEmpty()) {
            throw OciException.missingParameter("destinations is required");
        }
        Set<String> services = new LinkedHashSet<>();
        for (Object raw : destinations) {
            String id = String.valueOf(raw);
            String service = Ocids.isOfType(id, "onstopic") ? "notifications"
                    : Ocids.isOfType(id, "stream") ? "streaming" : null;
            if (service == null) {
                throw OciException.invalidParameter("A destination must be a Notifications topic or a stream, got " + id);
            }
            if (!services.add(service)) {
                throw OciException.invalidParameter("Only one destination per destination service is allowed");
            }
            try {
                if ("notifications".equals(service) && notifications != null
                        && config.services().notifications().enabled()) {
                    notifications.getTopic(id);
                } else if ("streaming".equals(service) && streaming != null
                        && config.services().streaming().enabled()) {
                    streaming.getStream(id);
                }
            } catch (OciException e) {
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
                throw OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: destination " + id);
            }
        }
    }

    // ── Alarm suppressions ─────────────────────────────────────────────────────

    public synchronized StoredAlarmSuppression createAlarmSuppression(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, Object> target = asMap(b.get("alarmSuppressionTarget"));
        String targetType = str(target, "targetType");
        String displayName = requireString(b, "displayName");
        Instant from = parseTime(requireString(b, "timeSuppressFrom"), "timeSuppressFrom");
        Instant until = parseTime(requireString(b, "timeSuppressUntil"), "timeSuppressUntil");
        if (!from.isBefore(until)) {
            throw OciException.invalidParameter("timeSuppressFrom must be before timeSuppressUntil");
        }
        String level = b.get("level") instanceof String l ? l : "DIMENSION";
        if (!Set.of("ALARM", "DIMENSION").contains(level)) {
            throw OciException.invalidParameter("level must be ALARM or DIMENSION, got " + level);
        }
        Map<String, String> dimensions = stringMap(b.get("dimensions"));
        if ("DIMENSION".equals(level) && dimensions.isEmpty()) {
            throw OciException.invalidParameter("dimensions is required when level is DIMENSION");
        }
        String compartmentId;
        Map<String, Object> storedTarget = new LinkedHashMap<>();
        if ("ALARM".equals(targetType)) {
            String alarmId = requireString(target, "alarmId");
            StoredAlarm alarm = alarms.get(alarmId).filter(a -> !"DELETED".equals(a.getLifecycleState()))
                    .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                            "Authorization failed or requested resource not found: alarm " + alarmId));
            compartmentId = alarm.getCompartmentId();
            storedTarget.put("targetType", "ALARM");
            storedTarget.put("alarmId", alarmId);
        } else if ("COMPARTMENT".equals(targetType)) {
            compartmentId = requireString(target, "compartmentId");
            storedTarget.put("targetType", "COMPARTMENT");
            storedTarget.put("compartmentId", compartmentId);
            storedTarget.put("compartmentIdInSubtree", Boolean.TRUE.equals(target.get("compartmentIdInSubtree")));
        } else {
            throw OciException.invalidParameter("alarmSuppressionTarget.targetType must be ALARM or COMPARTMENT");
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("alarmSuppressionTarget", storedTarget);
        spec.put("level", level);
        spec.put("displayName", displayName);
        spec.put("timeSuppressFrom", from.toString());
        spec.put("timeSuppressUntil", until.toString());
        if (b.get("suppressionConditions") instanceof List<?> conditions && !conditions.isEmpty()) {
            for (Object raw : conditions) {
                Map<String, Object> condition = asMap(raw);
                if (!"RECURRENCE".equals(condition.get("conditionType"))) {
                    throw OciException.invalidParameter("suppressionConditions[].conditionType must be RECURRENCE");
                }
                requireString(condition, "suppressionRecurrence");
                requireString(condition, "suppressionDuration");
            }
            spec.put("suppressionConditions", conditions);
        }
        if (str(b, "description") != null) {
            spec.put("description", str(b, "description"));
        }
        if (!dimensions.isEmpty()) {
            spec.put("dimensions", dimensions);
        }
        spec.put("freeformTags", stringMap(b.get("freeformTags")));
        spec.put("definedTags", b.get("definedTags") instanceof Map<?, ?> tags ? tags : new LinkedHashMap<>());

        StoredAlarmSuppression suppression = new StoredAlarmSuppression();
        suppression.setId(Ocids.generate("alarmsuppression", ociContext.realm(), ociContext.regionCode()));
        suppression.setCompartmentId(compartmentId);
        suppression.setSpec(spec);
        suppression.setLifecycleState("ACTIVE");
        String now = Instant.now().toString();
        suppression.setTimeCreated(now);
        suppression.setTimeUpdated(now);
        suppression.setEtag(Etags.newEtag());
        suppressions.put(suppression.getId(), suppression);
        return suppression;
    }

    public StoredAlarmSuppression getAlarmSuppression(String id) {
        return suppressions.get(id).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: alarm suppression " + id));
    }

    public synchronized void deleteAlarmSuppression(String id, String ifMatch) {
        StoredAlarmSuppression suppression = getAlarmSuppression(id);
        if ("DELETED".equals(suppression.getLifecycleState())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: alarm suppression " + id);
        }
        Etags.checkIfMatch(ifMatch, suppression.getEtag());
        suppression.setLifecycleState("DELETED");
        suppression.setTimeUpdated(Instant.now().toString());
        suppression.setEtag(Etags.newEtag());
        suppressions.put(id, suppression);
    }

    public List<StoredAlarmSuppression> listAlarmSuppressions(String alarmId, String compartmentId,
                                                              Boolean inSubtree, String displayName,
                                                              String lifecycleState, String level, String targetType,
                                                              Boolean isAllSuppressions, String sortBy,
                                                              String sortOrder) {
        if (alarmId == null && compartmentId == null) {
            throw OciException.missingParameter("alarmId or compartmentId is required");
        }
        if (Boolean.TRUE.equals(isAllSuppressions) && alarmId == null) {
            throw OciException.invalidParameter("isAllSuppressions requires alarmId");
        }
        boolean subtree = compartmentId != null && subtree(compartmentId, inSubtree);
        StoredAlarm alarm = alarmId == null ? null : getAlarm(alarmId);
        Comparator<StoredAlarmSuppression> order = switch (sortBy == null ? "timeCreated" : sortBy) {
            case "displayName" -> Comparator.comparing(s -> String.valueOf(s.getSpec().get("displayName")));
            case "timeSuppressFrom" -> Comparator.comparing(s -> String.valueOf(s.getSpec().get("timeSuppressFrom")));
            default -> Comparator.comparing(StoredAlarmSuppression::getTimeCreated);
        };
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return suppressions.scan(k -> true).stream()
                .filter(s -> lifecycleState != null ? lifecycleState.equals(s.getLifecycleState())
                        : !"DELETED".equals(s.getLifecycleState()))
                .filter(s -> displayName == null || displayName.equals(s.getSpec().get("displayName")))
                .filter(s -> level == null || level.equals(s.getSpec().get("level")))
                .filter(s -> targetType == null || targetType.equals(target(s).get("targetType")))
                .filter(s -> alarm == null || (Boolean.TRUE.equals(isAllSuppressions)
                        ? targets(s, alarm) : alarm.getId().equals(target(s).get("alarmId"))))
                .filter(s -> compartmentId == null || subtree || compartmentId.equals(s.getCompartmentId()))
                .sorted(order)
                .toList();
    }

    static Map<String, Object> target(StoredAlarmSuppression suppression) {
        return asMap(suppression.getSpec().get("alarmSuppressionTarget"));
    }

    /** Whether the suppression targets the alarm, directly or through the alarm's compartment. */
    boolean targets(StoredAlarmSuppression suppression, StoredAlarm alarm) {
        Map<String, Object> target = target(suppression);
        if ("ALARM".equals(target.get("targetType"))) {
            return alarm.getId().equals(target.get("alarmId"));
        }
        String compartment = (String) target.get("compartmentId");
        return compartment != null && (compartment.equals(alarm.getCompartmentId())
                || Boolean.TRUE.equals(target.get("compartmentIdInSubtree"))
                && compartment.equals(ociContext.tenancyId()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 0;
            case "ERROR" -> 1;
            case "WARNING" -> 2;
            default -> 3;
        };
    }

    static Instant parseTime(String value, String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw OciException.invalidParameter(field + " must be an RFC 3339 timestamp, got " + value);
        }
    }

    private static void checkDuration(Map<String, Object> spec, String field, Duration min, Duration max) {
        Object value = spec.get(field);
        if (value == null) {
            return;
        }
        Duration duration = isoDuration(String.valueOf(value), field);
        if (duration.compareTo(min) < 0 || duration.compareTo(max) > 0) {
            throw OciException.invalidParameter(field + " must be between " + min + " and " + max + ", got " + value);
        }
    }

    static Duration isoDuration(String value, String field) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw OciException.invalidParameter(field + " must be an ISO 8601 duration such as PT5M, got " + value);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String requireString(Map<String, Object> b, String key) {
        String value = str(b, key);
        if (value == null) {
            throw OciException.missingParameter(key + " is required");
        }
        return value;
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return result;
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return Stream.concat(
                CompartmentUsage.live(alarms, tenancyId, compartmentId, StoredAlarm::getCompartmentId,
                        StoredAlarm::getLifecycleState, a -> "alarm " + a.getId()),
                CompartmentUsage.live(suppressions, tenancyId, compartmentId, StoredAlarmSuppression::getCompartmentId,
                        StoredAlarmSuppression::getLifecycleState, s -> "alarm suppression " + s.getId())).toList();
    }
}
