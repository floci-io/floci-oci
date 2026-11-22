package io.floci.oci.services.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.monitoring.model.StoredAlarm;
import io.floci.oci.services.monitoring.model.StoredAlarmSuppression;
import io.floci.oci.services.monitoring.model.StoredMetricStream;
import io.floci.oci.services.notifications.NotificationsService;
import io.floci.oci.services.streaming.StreamingService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates every ACTIVE alarm on a tick, once a minute by default ("Monitoring evaluates alarms
 * once per minute", Monitoring/Concepts/monitoringoverview.htm), and sends alarm messages to the
 * alarm's Notifications topic and stream.
 *
 * <p>An evaluation at time {@code now} looks at data up to {@code now - evaluationSlackDuration}.
 * A metric stream fires when every one-minute evaluation inside {@code pendingDuration} breaches
 * the condition, and is OK again as soon as the most recent minute does not. Overrides are
 * evaluated in order before the base rule; the first rule that fires for a stream sets its
 * severity. Messages follow Monitoring/alarm-message-format.htm with the types OK_TO_FIRING,
 * FIRING_TO_OK and REPEAT.
 */
@ApplicationScoped
public class AlarmEvaluator {

    private static final Logger LOG = Logger.getLogger(AlarmEvaluator.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    static final int MAX_HISTORY = 1000;

    private final MonitoringService service;
    private final EmulatorConfig config;
    private final NotificationsService notifications;
    private final StreamingService streaming;
    private final RequestContextPropagation contextPropagation;
    private final ObjectMapper mapper;
    private ScheduledExecutorService scheduler;

    @Inject
    public AlarmEvaluator(MonitoringService service, EmulatorConfig config, NotificationsService notifications,
                          StreamingService streaming, RequestContextPropagation contextPropagation,
                          ObjectMapper mapper) {
        this.service = service;
        this.config = config;
        this.notifications = notifications;
        this.streaming = streaming;
        this.contextPropagation = contextPropagation;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.services().monitoring().enabled()) {
            return;
        }
        long seconds = Math.max(1, config.services().monitoring().alarmEvaluationIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("monitoring-alarms").daemon(true).factory());
        scheduler.scheduleAtFixedRate(this::tick, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One pass over every tenancy and region: expire old metric points, then evaluate every alarm. */
    void tick() {
        Instant now = Instant.now();
        try {
            StorageBackend<String, StoredAlarm> alarms = service.alarmStore();
            StorageBackend<String, StoredMetricStream> metrics = service.metricStore();
            if (alarms instanceof TenancyAwareStorageBackend<StoredAlarm> alarmPartitions
                    && metrics instanceof TenancyAwareStorageBackend<StoredMetricStream> metricPartitions
                    && contextPropagation != null) {
                Set<String> partitions = new TreeSet<>(alarmPartitions.tenancies());
                partitions.addAll(metricPartitions.tenancies());
                for (String partition : partitions) {
                    String region = TenancyAwareStorageBackend.regionOf(partition).orElse(config.defaultRegion());
                    contextPropagation.runAs(TenancyAwareStorageBackend.tenancyOf(partition), region,
                            () -> expireThenEvaluate(now));
                }
            } else {
                expireThenEvaluate(now);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Alarm evaluation failed: {0}", e.getMessage());
        }
    }

    private void expireThenEvaluate(Instant now) {
        service.expireMetrics(now.toEpochMilli());
        evaluateAll(now);
    }

    void evaluateAll(Instant now) {
        for (StoredAlarm alarm : service.alarmStore().scan(k -> true)) {
            if (!"ACTIVE".equals(alarm.getLifecycleState())) {
                continue;
            }
            try {
                evaluate(alarm, now);
            } catch (RuntimeException e) {
                LOG.warnv("Alarm {0} could not be evaluated: {1}", alarm.getId(), e.getMessage());
            }
        }
    }

    /**
     * Evaluates one alarm on a copy of it. The copy is stored, and its messages sent, only if the
     * stored alarm did not change meanwhile (an update, a delete or a state reset).
     */
    void evaluate(StoredAlarm stored, Instant now) {
        StoredAlarm alarm = service.snapshot(stored, a -> mapper.convertValue(a, StoredAlarm.class));
        String etagAtStart = alarm.getEtag();
        List<Outgoing> outgoing = new ArrayList<>();
        if (evaluate(alarm, now, outgoing) && service.commitEvaluation(alarm, etagAtStart)) {
            for (Outgoing message : outgoing) {
                deliverAll(alarm, message);
            }
        }
    }

    /** Computes the new state into {@code alarm} and the messages into {@code outgoing}; false when unchanged. */
    private boolean evaluate(StoredAlarm alarm, Instant now, List<Outgoing> outgoing) {
        Map<String, Object> spec = alarm.getSpec();
        if (!Boolean.TRUE.equals(spec.get("isEnabled"))) {
            if ("FIRING".equals(alarm.getStatus())) {
                alarm.setStatus("OK");
                alarm.getStreams().clear();
                return true;
            }
            return false;
        }
        if (suppressed(alarm, now)) {
            return false;
        }
        Duration slack = spec.get("evaluationSlackDuration") instanceof String s
                ? MonitoringService.isoDuration(s, "evaluationSlackDuration") : Duration.ofMinutes(3);
        long evaluatedAt = Math.floorDiv(now.minus(slack).toEpochMilli(), MINUTE.toMillis()) * MINUTE.toMillis();

        List<StoredMetricStream> streams = service.streams((String) spec.get("metricCompartmentId"),
                Boolean.TRUE.equals(spec.get("metricCompartmentIdInSubtree")), (String) spec.get("namespace"),
                (String) spec.get("resourceGroup"));
        List<StoredAlarmSuppression> dimensionSuppressions = dimensionSuppressions(alarm, now);
        Map<String, Map<String, Object>> previous = alarm.getStreams();

        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        // Streams still breaching but silenced by a DIMENSION suppression: neither firing nor recovered.
        Set<String> suppressedKeys = new HashSet<>();
        for (Map<String, Object> rule : rules(spec)) {
            for (Map.Entry<String, Firing> firing : firingStreams(rule, streams, evaluatedAt).entrySet()) {
                if (current.containsKey(firing.getKey())) {
                    continue;
                }
                if (suppressedDimensions(dimensionSuppressions, firing.getValue().dimensions())) {
                    suppressedKeys.add(firing.getKey());
                    continue;
                }
                Map<String, Object> state = new LinkedHashMap<>();
                // The full key: equal dimensions in two compartments are two streams with two dedupe keys.
                state.put("streamKey", firing.getKey());
                state.put("dimensions", firing.getValue().dimensions());
                state.put("status", "FIRING");
                state.put("ruleName", rule.get("ruleName"));
                state.put("severity", rule.get("severity"));
                state.put("query", rule.get("query"));
                state.put("body", rule.get("body"));
                state.put("value", firing.getValue().value());
                state.put("timestamp", now.toString());
                Map<String, Object> before = previous.get(firing.getKey());
                if (before != null && "FIRING".equals(before.get("status")) && before.get("lastNotified") != null) {
                    state.put("lastNotified", before.get("lastNotified"));
                }
                current.put(firing.getKey(), state);
            }
        }

        List<Map<String, Object>> cleared = new ArrayList<>();
        // Streams that recovered earlier keep their last OK state, so dimension states still report them.
        Map<String, Map<String, Object>> next = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> old : previous.entrySet()) {
            if (current.containsKey(old.getKey())) {
                continue;
            }
            if ("FIRING".equals(old.getValue().get("status")) && !suppressedKeys.contains(old.getKey())) {
                Map<String, Object> ok = new LinkedHashMap<>(old.getValue());
                ok.put("status", "OK");
                ok.put("timestamp", now.toString());
                ok.remove("lastNotified");
                cleared.add(ok);
                next.put(old.getKey(), ok);
            } else {
                next.put(old.getKey(), old.getValue());
            }
        }
        List<Map<String, Object>> newlyFiring = current.entrySet().stream()
                .filter(e -> !previous.containsKey(e.getKey()) || !"FIRING".equals(previous.get(e.getKey()).get("status")))
                .map(Map.Entry::getValue).toList();
        next.putAll(current);
        alarm.setStreams(next);

        String before = alarm.getStatus();
        String after = current.isEmpty() ? "OK" : "FIRING";
        alarm.setStatus(after);
        Map<String, Object> top = mostSevere(new ArrayList<>(current.values()));
        alarm.setFiringSeverity(top == null ? null : (String) top.get("severity"));
        alarm.setFiringRuleName(top == null ? null : (String) top.get("ruleName"));

        boolean perDimension = Boolean.TRUE.equals(spec.get("isNotificationsPerMetricDimensionEnabled"));
        if (!before.equals(after)) {
            if ("FIRING".equals(after)) {
                alarm.setTimestampTriggered(now.toString());
            }
            history(alarm, now, "The alarm state changed from " + before + " to " + after + ".");
        }
        if (perDimension) {
            // Each stream keeps its own repeat deadline, so one stream changing never delays another's reminder.
            Duration repeat = repeatEvery(alarm);
            for (Map<String, Object> stream : current.values()) {
                if (newlyFiring.contains(stream)) {
                    stream.put("lastNotified", now.toEpochMilli());
                    outgoing.add(message(alarm, "OK_TO_FIRING", List.of(stream), now));
                } else if (repeat != null && now.toEpochMilli() - lastNotified(stream, alarm) >= repeat.toMillis()) {
                    stream.put("lastNotified", now.toEpochMilli());
                    outgoing.add(message(alarm, "REPEAT", List.of(stream), now));
                }
            }
            for (Map<String, Object> stream : cleared) {
                outgoing.add(message(alarm, "FIRING_TO_OK", List.of(stream), now));
            }
        } else if (!before.equals(after) && ("FIRING".equals(after) || !cleared.isEmpty())) {
            outgoing.add(message(alarm, "FIRING".equals(after) ? "OK_TO_FIRING" : "FIRING_TO_OK",
                    "FIRING".equals(after) ? new ArrayList<>(current.values()) : cleared, now));
        } else if ("FIRING".equals(after) && repeatDue(alarm, now)) {
            outgoing.add(message(alarm, "REPEAT", new ArrayList<>(current.values()), now));
        }
        if (!outgoing.isEmpty()) {
            alarm.setLastNotified(now.toEpochMilli());
        }
        return true;
    }

    private static long lastNotified(Map<String, Object> stream, StoredAlarm alarm) {
        return stream.get("lastNotified") instanceof Number n ? n.longValue() : alarm.getLastNotified();
    }

    private static Map<String, Object> mostSevere(List<Map<String, Object>> streams) {
        return streams.stream()
                .min((a, b) -> MonitoringService.severityRank((String) a.get("severity"))
                        - MonitoringService.severityRank((String) b.get("severity")))
                .orElse(null);
    }

    /** An alarm message ready to send: its title and rendered JSON. */
    private record Outgoing(String title, String json) {
    }

    private record Firing(Map<String, String> dimensions, double value) {
    }

    /** Overrides first, in order, then the base values ({@code ruleName} {@code BASE}). */
    private static List<Map<String, Object>> rules(Map<String, Object> spec) {
        List<Map<String, Object>> rules = new ArrayList<>();
        if (spec.get("overrides") instanceof List<?> overrides) {
            for (Object raw : overrides) {
                Map<String, Object> override = new LinkedHashMap<>(MonitoringService.asMap(raw));
                override.putIfAbsent("query", spec.get("query"));
                override.putIfAbsent("severity", spec.get("severity"));
                override.putIfAbsent("pendingDuration", spec.get("pendingDuration"));
                override.putIfAbsent("body", spec.get("body"));
                rules.add(override);
            }
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("ruleName", spec.getOrDefault("ruleName", "BASE"));
        base.put("query", spec.get("query"));
        base.put("severity", spec.get("severity"));
        base.put("pendingDuration", spec.get("pendingDuration"));
        base.put("body", spec.get("body"));
        rules.add(base);
        return rules;
    }

    /**
     * The streams for which the rule's whole query, its terms joined with {@code &&} and {@code ||}
     * minute by minute, breached in every evaluation inside {@code pendingDuration}.
     */
    private Map<String, Firing> firingStreams(Map<String, Object> rule, List<StoredMetricStream> streams,
                                              long evaluatedAt) {
        Mql.Query query = Mql.parse((String) rule.get("query"));
        Duration pending = rule.get("pendingDuration") instanceof String p
                ? MonitoringService.isoDuration(p, "pendingDuration") : MINUTE;
        long evaluations = Math.max(1, (pending.toMillis() + MINUTE.toMillis() - 1) / MINUTE.toMillis());
        long first = evaluatedAt - (evaluations - 1) * MINUTE.toMillis();

        Map<String, Map<String, String>> dimensions = new LinkedHashMap<>();
        Map<String, Double> lastValues = new HashMap<>();
        Map<String, Set<Long>> breached = null;
        for (int i = 0; i < query.terms().size(); i++) {
            Mql.Term term = query.terms().get(i);
            Map<String, Set<Long>> termBreached = new LinkedHashMap<>();
            for (MetricQueryEngine.Series series : MetricQueryEngine.evaluate(term, streams, first,
                    evaluatedAt + 1, MINUTE)) {
                Set<Long> minutes = new HashSet<>();
                for (long t = first; t <= evaluatedAt; t += MINUTE.toMillis()) {
                    Double value = series.points().get(t);
                    if (value != null && (term.predicate() != null ? term.predicate().test(value) : value == 1.0)) {
                        minutes.add(t);
                        if (t == evaluatedAt) {
                            lastValues.putIfAbsent(series.key(), value);
                        }
                    }
                }
                termBreached.put(series.key(), minutes);
                dimensions.putIfAbsent(series.key(), series.dimensions());
            }
            breached = breached == null ? termBreached : join(breached, termBreached, query.joins().get(i - 1));
        }
        Map<String, Firing> result = new LinkedHashMap<>();
        if (breached == null) {
            return result;
        }
        for (Map.Entry<String, Set<Long>> entry : breached.entrySet()) {
            if (entry.getValue().size() == evaluations) {
                result.put(entry.getKey(), new Firing(dimensions.get(entry.getKey()),
                        lastValues.getOrDefault(entry.getKey(), 0.0)));
            }
        }
        return result;
    }

    private static Map<String, Set<Long>> join(Map<String, Set<Long>> left, Map<String, Set<Long>> right,
                                               String operator) {
        Map<String, Set<Long>> joined = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        for (String key : keys) {
            Set<Long> minutes = new HashSet<>(left.getOrDefault(key, Set.of()));
            if ("&&".equals(operator)) {
                minutes.retainAll(right.getOrDefault(key, Set.of()));
            } else {
                minutes.addAll(right.getOrDefault(key, Set.of()));
            }
            joined.put(key, minutes);
        }
        return joined;
    }

    private boolean suppressed(StoredAlarm alarm, Instant now) {
        if (alarm.getSpec().get("suppression") instanceof Map<?, ?> window && within(window.get("timeSuppressFrom"),
                window.get("timeSuppressUntil"), now)) {
            return true;
        }
        return activeSuppressions(alarm, now).stream().anyMatch(s -> "ALARM".equals(s.getSpec().get("level")));
    }

    boolean suspended(StoredAlarm alarm, Instant now) {
        return suppressed(alarm, now);
    }

    private List<StoredAlarmSuppression> dimensionSuppressions(StoredAlarm alarm, Instant now) {
        return activeSuppressions(alarm, now).stream()
                .filter(s -> "DIMENSION".equals(s.getSpec().get("level"))).toList();
    }

    private List<StoredAlarmSuppression> activeSuppressions(StoredAlarm alarm, Instant now) {
        return service.suppressionStore().scan(k -> true).stream()
                .filter(s -> "ACTIVE".equals(s.getLifecycleState()))
                .filter(s -> within(s.getSpec().get("timeSuppressFrom"), s.getSpec().get("timeSuppressUntil"), now))
                .filter(s -> service.targets(s, alarm))
                .toList();
    }

    private static boolean suppressedDimensions(List<StoredAlarmSuppression> suppressions,
                                                Map<String, String> dimensions) {
        for (StoredAlarmSuppression suppression : suppressions) {
            Map<String, Object> wanted = MonitoringService.asMap(suppression.getSpec().get("dimensions"));
            if (!wanted.isEmpty() && wanted.entrySet().stream()
                    .allMatch(e -> Objects.equals(String.valueOf(e.getValue()), dimensions.get(e.getKey())))) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(Object from, Object until, Instant now) {
        if (from == null || until == null) {
            return false;
        }
        Instant start = MonitoringService.parseTime(String.valueOf(from), "timeSuppressFrom");
        Instant end = MonitoringService.parseTime(String.valueOf(until), "timeSuppressUntil");
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private static Duration repeatEvery(StoredAlarm alarm) {
        return alarm.getSpec().get("repeatNotificationDuration") instanceof String repeat
                ? MonitoringService.isoDuration(repeat, "repeatNotificationDuration") : null;
    }

    private static boolean repeatDue(StoredAlarm alarm, Instant now) {
        Duration every = repeatEvery(alarm);
        return every != null && now.toEpochMilli() - alarm.getLastNotified() >= every.toMillis();
    }

    private static void history(StoredAlarm alarm, Instant now, String summary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("alarmSummary", summary(alarm));
        entry.put("summary", summary);
        entry.put("timestamp", now.toString());
        entry.put("timestampTriggered", alarm.getTimestampTriggered());
        alarm.getHistory().add(entry);
        while (alarm.getHistory().size() > MAX_HISTORY) {
            alarm.getHistory().remove(0);
        }
    }

    /** The configured {@code alarmSummary}, else floci-oci's system-generated one. */
    static String summary(StoredAlarm alarm) {
        if (alarm.getSpec().get("alarmSummary") instanceof String configured) {
            return configured;
        }
        long firing = alarm.getStreams().values().stream().filter(s -> "FIRING".equals(s.get("status"))).count();
        return "Alarm \"" + alarm.getSpec().get("displayName") + "\" is in a \"" + alarm.getStatus() + "\" state"
                + ("FIRING".equals(alarm.getStatus()) ? "; because " + firing + " metric streams meet the trigger rule." : ".");
    }

    /** A stream's key, or its dimensions for a state recorded before keys were kept. */
    private static String streamIdentity(Map<String, Object> stream, Map<String, String> dimensions) {
        return stream.get("streamKey") instanceof String key ? key : MonitoringService.canonicalDimensions(dimensions);
    }

    /** Builds the alarm message (Monitoring/alarm-message-format.htm); null when it cannot be rendered. */
    private Outgoing message(StoredAlarm alarm, String type, List<Map<String, Object>> streamsInMessage, Instant now) {
        Map<String, Object> spec = alarm.getSpec();
        boolean perDimension = Boolean.TRUE.equals(spec.get("isNotificationsPerMetricDimensionEnabled"));
        String status = "FIRING_TO_OK".equals(type) ? "OK" : "FIRING";
        String severity = streamsInMessage.stream().map(s -> (String) s.get("severity")).filter(Objects::nonNull)
                .min((a, b) -> MonitoringService.severityRank(a) - MonitoringService.severityRank(b))
                .orElse((String) spec.get("severity"));
        List<Map<String, String>> dimensions = new ArrayList<>();
        List<Map<String, String>> values = new ArrayList<>();
        for (Map<String, Object> stream : streamsInMessage.subList(0, Math.min(100, streamsInMessage.size()))) {
            dimensions.add(castDimensions(stream.get("dimensions")));
            Map<String, String> value = new LinkedHashMap<>();
            value.put(String.valueOf(stream.get("query")), formatValue(stream.get("value")));
            values.add(value);
        }
        String dedupeSource = alarm.getId() + (perDimension && !dimensions.isEmpty()
                ? streamIdentity(streamsInMessage.get(0), dimensions.get(0)) : "");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", alarm.getId());
        metadata.put("status", status);
        metadata.put("severity", severity);
        metadata.put("namespace", spec.get("namespace"));
        metadata.put("query", spec.get("query"));
        metadata.put("totalMetricsFiring", "OK".equals(status) ? 0 : streamsInMessage.size());
        metadata.put("dimensions", dimensions);
        metadata.put("metricValues", values);
        metadata.put("alarmUrl", "https://cloud.oracle.com/monitoring/alarms/" + alarm.getId()
                + "?region=" + regionOf(alarm.getId()));
        metadata.put("alarmSummary", summary(alarm));
        metadata.put("notificationType", perDimension ? "Split messages per metric stream"
                : "Grouped messages across metric streams");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("dedupekey", UUID.nameUUIDFromBytes(dedupeSource.getBytes(StandardCharsets.UTF_8)).toString());
        String title = spec.get("notificationTitle") instanceof String t ? t : (String) spec.get("displayName");
        message.put("title", title);
        // The body of the rule that fired: an override's own body, else the alarm's.
        Map<String, Object> rule = mostSevere(streamsInMessage);
        message.put("body", rule != null && rule.containsKey("body") ? rule.get("body") : spec.get("body"));
        message.put("type", type);
        message.put("severity", severity);
        message.put("timestampEpochMillis", now.toEpochMilli());
        message.put("timestamp", now.toString());
        message.put("alarmMetaData", List.of(metadata));
        message.put("version", 1.5);

        String json;
        try {
            json = "PRETTY_JSON".equals(spec.get("messageFormat"))
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(message)
                    : mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            LOG.warnv("Alarm {0}: could not render the alarm message: {1}", alarm.getId(), e.getMessage());
            return null;
        }
        return new Outgoing(title, json);
    }

    private void deliverAll(StoredAlarm alarm, Outgoing message) {
        if (message != null && alarm.getSpec().get("destinations") instanceof List<?> destinations) {
            for (Object raw : destinations) {
                deliver(alarm, String.valueOf(raw), message.title(), message.json());
            }
        }
    }

    private void deliver(StoredAlarm alarm, String destination, String title, String json) {
        try {
            if (Ocids.isOfType(destination, "onstopic")) {
                if (notifications != null && config.services().notifications().enabled()) {
                    notifications.publishInternal(destination, title, json, "application/json");
                }
            } else if (Ocids.isOfType(destination, "stream")) {
                if (streaming != null && config.services().streaming().enabled()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
                    streaming.putMessages(destination, List.of(entry));
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Alarm {0}: delivery to {1} failed: {2}", alarm.getId(), destination, e.getMessage());
        }
    }

    /** The region name for an OCID's region code, e.g. {@code iad} to {@code us-ashburn-1}. */
    private String regionOf(String ocid) {
        String code = Ocids.parse(ocid).map(Ocids.Ocid::region).orElse(null);
        return Regions.all().stream().filter(r -> r.code().equals(code)).map(Regions.Region::name).findFirst()
                .orElse(config.defaultRegion());
    }

    private static String formatValue(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castDimensions(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
