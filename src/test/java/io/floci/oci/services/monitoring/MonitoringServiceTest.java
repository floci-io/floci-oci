package io.floci.oci.services.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.monitoring.model.StoredAlarm;
import io.floci.oci.services.monitoring.model.StoredAlarmSuppression;
import io.floci.oci.services.monitoring.model.StoredMetricStream;
import io.floci.oci.services.notifications.NotificationsService;
import io.floci.oci.services.streaming.StreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitoringServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..montest";
    private static final String TOPIC = "ocid1.onstopic.oc1.iad.alarms";

    private MonitoringService service;
    private AlarmEvaluator evaluator;
    private NotificationsService notifications;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn("ocid1.tenancy.oc1..t");
        lenient().when(config.services().notifications().enabled()).thenReturn(true);
        lenient().when(config.services().streaming().enabled()).thenReturn(true);
        notifications = mock(NotificationsService.class);
        service = new MonitoringService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                config, null, null, notifications, mock(StreamingService.class));
        evaluator = new AlarmEvaluator(service, config, notifications, mock(StreamingService.class), null,
                new ObjectMapper());
    }

    private static Map<String, Object> metric(String host, Instant at, double value) {
        Map<String, Object> metric = new HashMap<>();
        metric.put("namespace", "custom_app");
        metric.put("compartmentId", COMPARTMENT);
        metric.put("name", "Cpu");
        metric.put("dimensions", Map.of("host", host));
        metric.put("datapoints", List.of(Map.of("timestamp", at.toString(), "value", value)));
        return metric;
    }

    @SafeVarargs
    private List<MonitoringService.FailedMetric> post(Map<String, Object>... metrics) {
        return service.postMetricData(Map.of("metricData", List.of(metrics)));
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(minutes, ChronoUnit.MINUTES).plusSeconds(10);
    }

    private static Map<String, Object> streamState(StoredAlarm alarm, Map<String, String> dimensions) {
        return alarm.getStreams().values().stream().filter(state -> dimensions.equals(state.get("dimensions")))
                .findFirst().orElseThrow(() -> new AssertionError("no stream " + dimensions + " in " + alarm.getStreams()));
    }

    private Map<String, Object> alarmBody(String query) {
        Map<String, Object> body = new HashMap<>();
        body.put("displayName", "high cpu");
        body.put("compartmentId", COMPARTMENT);
        body.put("metricCompartmentId", COMPARTMENT);
        body.put("namespace", "custom_app");
        body.put("query", query);
        body.put("severity", "CRITICAL");
        body.put("destinations", List.of(TOPIC));
        body.put("isEnabled", true);
        return body;
    }

    @Test
    void postMetricDataValidatesEachMetricOnItsOwn() {
        Map<String, Object> reserved = metric("a", minutesAgo(1), 1);
        reserved.put("namespace", "oci_compute");
        Map<String, Object> stale = metric("a", Instant.now().minus(3, ChronoUnit.HOURS), 1);
        Map<String, Object> noDimensions = metric("a", minutesAgo(1), 1);
        noDimensions.put("dimensions", Map.of());

        List<MonitoringService.FailedMetric> failed = post(metric("a", minutesAgo(1), 1), reserved, stale, noDimensions);

        assertEquals(3, failed.size());
        assertEquals(1, service.listMetrics(COMPARTMENT, null, Map.of()).size());
    }

    @Test
    void atomicBatchesRejectEverything() {
        Map<String, Object> bad = metric("a", minutesAgo(1), 1);
        bad.put("name", "1bad");
        List<MonitoringService.FailedMetric> failed = service.postMetricData(Map.of("batchAtomicity", "ATOMIC",
                "metricData", List.of(metric("a", minutesAgo(1), 1), bad)));

        assertEquals(2, failed.size());
        assertTrue(service.listMetrics(COMPARTMENT, null, Map.of()).isEmpty());
    }

    @Test
    void summarizeMetricsDataAppliesTheQuery() {
        post(metric("a", minutesAgo(5), 10), metric("a", minutesAgo(4), 90), metric("b", minutesAgo(4), 50));

        List<MonitoringService.SeriesResult> all = service.summarizeMetricsData(COMPARTMENT, null,
                Map.of("namespace", "custom_app", "query", "Cpu[1m].max()"));
        assertEquals(2, all.size());
        assertEquals(List.of(10.0, 90.0), new ArrayList<>(all.get(0).points().values()));
        assertEquals("1m", all.get(0).resolution());

        List<MonitoringService.SeriesResult> above = service.summarizeMetricsData(COMPARTMENT, null,
                Map.of("namespace", "custom_app", "query", "Cpu[1m]{host = \"a\"}.max() > 50"));
        assertEquals(1, above.size());
        assertEquals(List.of(90.0), new ArrayList<>(above.get(0).points().values()));

        List<MonitoringService.SeriesResult> grouped = service.summarizeMetricsData(COMPARTMENT, null,
                Map.of("namespace", "custom_app", "query", "Cpu[1h].grouping().sum()", "resolution", "1m"));
        assertEquals(1, grouped.size());
        assertEquals(150.0, grouped.get(0).points().lastEntry().getValue());

        assertThrows(OciException.class, () -> service.summarizeMetricsData(COMPARTMENT, null,
                Map.of("namespace", "custom_app", "query", "Cpu[1m].max()", "resolution", "5m")));
        assertThrows(OciException.class, () -> service.summarizeMetricsData(COMPARTMENT, true,
                Map.of("namespace", "custom_app", "query", "Cpu[1m].max()")));
    }

    @Test
    void listMetricsFiltersAndGroups() {
        post(metric("a", minutesAgo(1), 1), metric("b", minutesAgo(1), 1));

        assertEquals(2, service.listMetrics(COMPARTMENT, null, Map.of("name", "C*")).size());
        assertEquals(1, service.listMetrics(COMPARTMENT, null, Map.of("dimensionFilters", Map.of("host", "b"))).size());
        assertEquals(List.of(Map.of("namespace", "custom_app")),
                service.listMetrics(COMPARTMENT, null, Map.of("groupBy", List.of("namespace"))));
        assertTrue(service.listMetrics(COMPARTMENT, null, Map.of("name", "Mem")).isEmpty());
    }

    @Test
    void createAlarmFillsDefaultsAndValidates() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));

        assertTrue(alarm.getId().startsWith("ocid1.alarm.oc1.iad."));
        assertEquals("ACTIVE", alarm.getLifecycleState());
        assertEquals("1m", alarm.getSpec().get("resolution"));
        assertEquals("PT1M", alarm.getSpec().get("pendingDuration"));
        assertEquals("RAW", alarm.getSpec().get("messageFormat"));
        assertEquals("BASE", alarm.getSpec().get("ruleName"));

        for (Map.Entry<String, Object> bad : Map.<String, Object>of("severity", "LOUD", "query", "Cpu[1m].max()",
                "pendingDuration", "PT2H", "resolution", "5m",
                "destinations", List.of("ocid1.bucket.oc1.iad.x")).entrySet()) {
            Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
            body.put(bad.getKey(), bad.getValue());
            assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createAlarm(body),
                    bad.getKey()).getCode(), bad.getKey());
        }

        Map<String, Object> subtreeOfACompartment = alarmBody("Cpu[1m].max() > 80");
        subtreeOfACompartment.put("metricCompartmentIdInSubtree", true);
        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> service.createAlarm(subtreeOfACompartment)).getCode());
        Map<String, Object> subtreeOfTheTenancy = alarmBody("Cpu[1m].max() > 80");
        subtreeOfTheTenancy.put("metricCompartmentId", "ocid1.tenancy.oc1..t");
        subtreeOfTheTenancy.put("metricCompartmentIdInSubtree", true);
        assertEquals(true, service.createAlarm(subtreeOfTheTenancy).getSpec().get("metricCompartmentIdInSubtree"));
        StoredAlarm existing = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.updateAlarm(existing.getId(), Map.of("metricCompartmentIdInSubtree", true), null))
                .getCode());

        when(notifications.getTopic("ocid1.onstopic.oc1.iad.missing"))
                .thenThrow(OciException.notAuthorizedOrNotFound("missing"));
        Map<String, Object> unknownTopic = alarmBody("Cpu[1m].max() > 80");
        unknownTopic.put("destinations", List.of("ocid1.onstopic.oc1.iad.missing"));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound",
                assertThrows(OciException.class, () -> service.createAlarm(unknownTopic)).getCode());
    }

    @Test
    void deletedAlarmsAreTombstones() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        service.deleteAlarm(alarm.getId(), null);

        assertEquals("DELETED", service.getAlarm(alarm.getId()).getLifecycleState());
        assertTrue(service.listAlarms(COMPARTMENT, null, null, null, null, null).isEmpty());
        assertEquals(1, service.listAlarms(COMPARTMENT, null, null, "DELETED", null, null).size());
        assertThrows(OciException.class, () -> service.updateAlarm(alarm.getId(), Map.of(), null));
    }

    @Test
    void alarmFiresNotifiesAndClears() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        // Evaluations look at data up to now minus the 3 minute slack period.
        post(metric("a", minutesAgo(4), 95));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());

        StoredAlarm firing = service.getAlarm(alarm.getId());
        assertEquals("FIRING", firing.getStatus());
        assertEquals("CRITICAL", firing.getFiringSeverity());
        assertEquals(1, firing.getHistory().size());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).publishInternal(eq(TOPIC), eq("high cpu"), message.capture(), eq("application/json"));
        assertTrue(message.getValue().contains("\"type\":\"OK_TO_FIRING\""), message.getValue());
        assertTrue(message.getValue().contains("\"status\":\"FIRING\""), message.getValue());
        assertTrue(message.getValue().contains("\"Cpu[1m].max() > 80\":\"95\""), message.getValue());

        clearInvocations(notifications);
        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        verify(notifications, never()).publishInternal(anyString(), anyString(), anyString(), anyString());

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now().plus(2, ChronoUnit.MINUTES));
        assertEquals("OK", service.getAlarm(alarm.getId()).getStatus());
        verify(notifications).publishInternal(eq(TOPIC), eq("high cpu"), message.capture(), eq("application/json"));
        assertTrue(message.getValue().contains("\"type\":\"FIRING_TO_OK\""), message.getValue());
        // A recovered stream keeps reporting its OK state on later evaluations.
        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now().plus(3, ChronoUnit.MINUTES));
        Map<String, Object> recovered = streamState(service.getAlarm(alarm.getId()), Map.of("host", "a"));
        assertEquals("OK", recovered.get("status"));
    }

    @Test
    void pendingDurationNeedsEveryMinuteToBreach() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("pendingDuration", "PT2M");
        StoredAlarm alarm = service.createAlarm(body);
        post(metric("a", minutesAgo(4), 95));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("OK", service.getAlarm(alarm.getId()).getStatus());

        post(metric("a", minutesAgo(5), 99));
        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("FIRING", service.getAlarm(alarm.getId()).getStatus());
    }

    @Test
    void perDimensionAlarmsNotifyPerStreamAndRepeat() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("isNotificationsPerMetricDimensionEnabled", true);
        body.put("repeatNotificationDuration", "PT1M");
        StoredAlarm alarm = service.createAlarm(body);
        // Data for the next evaluation minute too, so the second tick still fires.
        post(metric("a", minutesAgo(4), 95), metric("b", minutesAgo(4), 99),
                metric("a", minutesAgo(3), 95), metric("b", minutesAgo(3), 99),
                metric("a", minutesAgo(2), 95), metric("b", minutesAgo(2), 99));

        Instant now = Instant.now();
        evaluator.evaluate(service.getAlarm(alarm.getId()), now);
        verify(notifications, times(2)).publishInternal(eq(TOPIC), anyString(), anyString(), anyString());
        assertEquals(2, service.getAlarm(alarm.getId()).getStreams().size());

        clearInvocations(notifications);
        evaluator.evaluate(service.getAlarm(alarm.getId()), now.plusSeconds(61));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).publishInternal(eq(TOPIC), anyString(), message.capture(), anyString());
        assertTrue(message.getValue().contains("\"type\":\"REPEAT\""), message.getValue());
    }

    @Test
    void equalDimensionsInTwoCompartmentsGetTwoDedupeKeys() throws Exception {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("metricCompartmentId", "ocid1.tenancy.oc1..t");
        body.put("metricCompartmentIdInSubtree", true);
        body.put("isNotificationsPerMetricDimensionEnabled", true);
        StoredAlarm alarm = service.createAlarm(body);
        Map<String, Object> elsewhere = metric("a", minutesAgo(4), 97);
        elsewhere.put("compartmentId", "ocid1.compartment.oc1..other");
        post(metric("a", minutesAgo(4), 95), elsewhere);

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).publishInternal(eq(TOPIC), anyString(), messages.capture(), anyString());
        ObjectMapper mapper = new ObjectMapper();
        Set<String> keys = new HashSet<>();
        for (String message : messages.getAllValues()) {
            keys.add(mapper.readTree(message).get("dedupekey").asText());
        }
        assertEquals(2, keys.size(), messages.getAllValues().toString());
    }

    @Test
    void overridesAreEvaluatedFirst() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("severity", "WARNING");
        body.put("overrides", List.of(Map.of("ruleName", "critical", "query", "Cpu[1m].max() > 90",
                "severity", "CRITICAL")));
        StoredAlarm alarm = service.createAlarm(body);
        post(metric("a", minutesAgo(4), 95), metric("b", minutesAgo(4), 85));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());

        StoredAlarm firing = service.getAlarm(alarm.getId());
        assertEquals("CRITICAL", firing.getFiringSeverity());
        assertEquals("critical", streamState(firing, Map.of("host", "a")).get("ruleName"));
        assertEquals("BASE", streamState(firing, Map.of("host", "b")).get("ruleName"));
    }

    @Test
    void dimensionSetsThatPrintAlikeStayDifferentStreams() {
        Map<String, Object> joined = metric("x", minutesAgo(2), 1);
        joined.put("dimensions", Map.of("host", "a, zone=b"));
        Map<String, Object> split = metric("x", minutesAgo(2), 2);
        split.put("dimensions", Map.of("host", "a", "zone", "b"));
        post(joined, split);
        assertEquals(2, service.streams(COMPARTMENT, false, "custom_app", null).size());
    }

    @Test
    void ingestionNeverChangesAStreamAQueryAlreadyHolds() {
        post(metric("a", minutesAgo(3), 1));
        StoredMetricStream held = service.streams(COMPARTMENT, false, "custom_app", null).get(0);
        post(metric("a", minutesAgo(2), 2));
        assertEquals(1, held.getDatapoints().size());
        assertEquals(2, service.streams(COMPARTMENT, false, "custom_app", null).get(0).getDatapoints().size());
    }

    @Test
    void anOmittedResourceGroupMatchesOnlyMetricsWithoutOne() {
        // "A null value returns only metric data that has no resource groups" (summarize_metrics_data_details.go).
        Map<String, Object> grouped = metric("a", minutesAgo(2), 1);
        grouped.put("resourceGroup", "fleet");
        post(grouped, metric("b", minutesAgo(2), 2));
        assertEquals(1, service.streams(COMPARTMENT, false, "custom_app", null).size());
        assertEquals(1, service.streams(COMPARTMENT, false, "custom_app", "fleet").size());
    }

    @Test
    void joinedQueriesCombineTheirTerms() {
        post(metric("a", minutesAgo(2), 90), metric("b", minutesAgo(2), 10));
        Map<String, Object> body = new HashMap<>();
        body.put("namespace", "custom_app");
        body.put("query", "Cpu[1m].max() > 80 && Cpu[1m].max() < 20");
        assertTrue(service.summarizeMetricsData(COMPARTMENT, null, body).isEmpty());
        body.put("query", "Cpu[1m].max() > 80 && Cpu[1m].max() < 100");
        assertEquals(List.of(Map.of("host", "a")), service.summarizeMetricsData(COMPARTMENT, null, body).stream()
                .map(MonitoringService.SeriesResult::dimensions).toList());
        body.put("query", "Cpu[1m].max() > 80 || Cpu[1m].max() < 20");
        assertEquals(2, service.summarizeMetricsData(COMPARTMENT, null, body).size());
    }

    @Test
    void anEvaluationOfAnAlarmChangedMeanwhileIsDropped() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        post(metric("a", minutesAgo(4), 95));
        StoredAlarm before = new ObjectMapper().convertValue(service.getAlarm(alarm.getId()), StoredAlarm.class);
        service.updateAlarm(alarm.getId(), Map.of("displayName", "renamed"), null);

        evaluator.evaluate(before, Instant.now());

        assertEquals("OK", service.getAlarm(alarm.getId()).getStatus());
        assertEquals("renamed", service.getAlarm(alarm.getId()).getSpec().get("displayName"));
        verify(notifications, never()).publishInternal(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anEvaluationOfAnAlarmDeletedMeanwhileIsDropped() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        post(metric("a", minutesAgo(4), 95));
        StoredAlarm before = new ObjectMapper().convertValue(service.getAlarm(alarm.getId()), StoredAlarm.class);
        service.deleteAlarm(alarm.getId(), null);

        evaluator.evaluate(before, Instant.now());

        assertEquals("DELETED", service.getAlarm(alarm.getId()).getLifecycleState());
        verify(notifications, never()).publishInternal(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void perDimensionRepeatsAreDuePerStream() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("isNotificationsPerMetricDimensionEnabled", true);
        body.put("repeatNotificationDuration", "PT5M");
        StoredAlarm alarm = service.createAlarm(body);
        Instant now = Instant.now();
        post(metric("a", minutesAgo(4), 95), metric("a", minutesAgo(0), 95));
        evaluator.evaluate(service.getAlarm(alarm.getId()), now);

        // Four minutes on, b starts firing; a's reminder is not due yet.
        post(metric("b", minutesAgo(0), 95));
        evaluator.evaluate(service.getAlarm(alarm.getId()), now.plus(4, ChronoUnit.MINUTES));

        // Six minutes on, a's reminder is due although b changed two minutes ago.
        post(metric("a", minutesAgo(-2), 95), metric("b", minutesAgo(-2), 95));
        clearInvocations(notifications);
        evaluator.evaluate(service.getAlarm(alarm.getId()), now.plus(6, ChronoUnit.MINUTES));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(1)).publishInternal(eq(TOPIC), anyString(), message.capture(), anyString());
        assertTrue(message.getValue().contains("\"type\":\"REPEAT\""), message.getValue());
        assertTrue(message.getValue().contains("\"host\":\"a\""), message.getValue());
    }

    @Test
    void anOverrideQueryNeedsATriggerRuleToo() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("overrides", List.of(Map.of("ruleName", "critical", "query", "Cpu[1m].max()",
                "severity", "CRITICAL")));

        OciException error = assertThrows(OciException.class, () -> service.createAlarm(body));

        assertEquals("InvalidParameter", error.getCode());
        assertEquals("An alarm query needs a condition, such as > 80, or absent()", error.getMessage());
        body.put("overrides", List.of(Map.of("ruleName", "critical", "query", "Cpu[1m].absent()",
                "severity", "CRITICAL")));
        assertEquals(1, ((List<?>) service.createAlarm(body).getSpec().get("overrides")).size());
    }

    @Test
    void aStreamThatStopsReportingIsExpiredAfterTheRetentionPeriod() {
        post(metric("a", minutesAgo(1), 1), metric("b", minutesAgo(1), 1));
        long later = System.currentTimeMillis() + MonitoringService.RETENTION.toMillis() + 60_000;

        service.expireMetrics(System.currentTimeMillis());
        assertEquals(2, service.listMetrics(COMPARTMENT, null, Map.of()).size());

        service.expireMetrics(later);
        assertEquals(List.of(), service.listMetrics(COMPARTMENT, null, Map.of()));
    }

    @Test
    void aResetDuringExpiryLeavesNoMetricsBehind() throws Exception {
        AtomicBoolean expiring = new AtomicBoolean();
        AtomicReference<Thread> reset = new AtomicReference<>();
        InMemoryStorage<String, StoredMetricStream> metrics = new InMemoryStorage<>() {
            @Override
            public void put(String key, StoredMetricStream value) {
                if (expiring.get() && reset.get() == null) {
                    Thread thread = new Thread(service::clear);
                    reset.set(thread);
                    thread.start();
                    try {
                        // Long enough for a reset that does not wait for expiry to finish first.
                        thread.join(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.put(key, value);
            }
        };
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        service = new MonitoringService(metrics, new InMemoryStorage<>(), new InMemoryStorage<>(), config, null, null,
                notifications, mock(StreamingService.class));
        post(metric("a", minutesAgo(5), 1));
        post(metric("a", minutesAgo(1), 2));

        expiring.set(true);
        service.expireMetrics(minutesAgo(3).plus(MonitoringService.RETENTION).toEpochMilli());
        reset.get().join();

        assertEquals(List.of(), service.listMetrics(COMPARTMENT, null, Map.of()));
    }

    @Test
    void anOverrideSendsItsOwnBody() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("body", "base instructions");
        body.put("overrides", List.of(Map.of("ruleName", "critical", "query", "Cpu[1m].max() > 90",
                "severity", "CRITICAL", "body", "page the on-call")));
        StoredAlarm alarm = service.createAlarm(body);
        post(metric("a", minutesAgo(4), 95));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).publishInternal(eq(TOPIC), anyString(), message.capture(), anyString());
        assertTrue(message.getValue().contains("\"body\":\"page the on-call\""), message.getValue());
    }

    @Test
    void joinedAlarmTermsAreCombinedEachMinuteBeforeThePendingCheck() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80 || Mem[1m].max() > 80");
        body.put("pendingDuration", "PT2M");
        StoredAlarm alarm = service.createAlarm(body);
        Map<String, Object> memLow = metric("a", minutesAgo(5), 10);
        memLow.put("name", "Mem");
        Map<String, Object> memHigh = metric("a", minutesAgo(4), 95);
        memHigh.put("name", "Mem");
        // CPU breaches in the first minute, memory in the second: the joined query holds in both.
        post(metric("a", minutesAgo(5), 95), metric("a", minutesAgo(4), 10), memLow, memHigh);

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("FIRING", service.getAlarm(alarm.getId()).getStatus());
    }

    @Test
    void aSuppressedStreamIsNotReportedAsRecovered() {
        Map<String, Object> body = alarmBody("Cpu[1m].max() > 80");
        body.put("isNotificationsPerMetricDimensionEnabled", true);
        StoredAlarm alarm = service.createAlarm(body);
        post(metric("a", minutesAgo(4), 95), metric("a", minutesAgo(3), 95));
        Instant now = Instant.now();
        evaluator.evaluate(service.getAlarm(alarm.getId()), now);

        service.createAlarmSuppression(Map.of(
                "alarmSuppressionTarget", Map.of("targetType", "ALARM", "alarmId", alarm.getId()),
                "displayName", "silence a", "dimensions", Map.of("host", "a"),
                "timeSuppressFrom", now.minusSeconds(60).toString(),
                "timeSuppressUntil", now.plusSeconds(3600).toString()));
        clearInvocations(notifications);
        evaluator.evaluate(service.getAlarm(alarm.getId()), now.plusSeconds(60));
        verify(notifications, never()).publishInternal(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void equalDimensionsInTwoCompartmentsAreNotPooled() {
        Map<String, Object> other = metric("a", minutesAgo(2), 90);
        other.put("compartmentId", "ocid1.compartment.oc1..other");
        post(metric("a", minutesAgo(2), 10), other);
        Map<String, Object> body = new HashMap<>();
        body.put("namespace", "custom_app");
        body.put("query", "Cpu[1m].mean()");
        List<MonitoringService.SeriesResult> series = service.summarizeMetricsData("ocid1.tenancy.oc1..t", true, body);
        assertEquals(2, series.size());
        assertEquals(Set.of(10.0, 90.0), series.stream().map(r -> r.points().firstEntry().getValue())
                .collect(Collectors.toSet()));
    }

    @Test
    void aHugeQueryRangeIsCappedByTheResolution() {
        post(metric("a", minutesAgo(2), 10));
        Map<String, Object> body = new HashMap<>();
        body.put("namespace", "custom_app");
        body.put("query", "Cpu[1m].mean()");
        body.put("startTime", "0001-01-01T00:00:00Z");
        body.put("endTime", "9999-01-01T00:00:00Z");
        long started = System.nanoTime();
        List<MonitoringService.SeriesResult> series = service.summarizeMetricsData(COMPARTMENT, null, body);
        assertTrue(System.nanoTime() - started < 5_000_000_000L);
        assertEquals(1, series.size());
        assertThrows(OciException.class, () -> Mql.interval("2147483648m"));
    }

    @Test
    void theCurrentHourIsReturnedForAnHourlyQueryEndingLater() {
        post(metric("a", minutesAgo(1), 10));
        Map<String, Object> body = new HashMap<>();
        body.put("namespace", "custom_app");
        body.put("query", "Cpu[1h].mean()");
        body.put("resolution", "1h");
        body.put("startTime", Instant.now().minus(2, ChronoUnit.HOURS).toString());
        body.put("endTime", Instant.now().plus(3, ChronoUnit.HOURS).toString());
        assertEquals(1, service.summarizeMetricsData(COMPARTMENT, null, body).size());
    }

    @Test
    void aResolutionAboveTheIntervalIsRejectedWhateverTheDates() {
        Map<String, Object> body = new HashMap<>();
        body.put("namespace", "custom_app");
        body.put("query", "Cpu[1m].max()");
        body.put("resolution", "5m");
        body.put("startTime", "2000-01-01T00:00:00Z");
        body.put("endTime", "2000-01-02T00:00:00Z");
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.summarizeMetricsData(COMPARTMENT, null, body)).getCode());
    }

    @Test
    void anUpdateCannotClearARequiredField() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        for (String field : List.of("namespace", "metricCompartmentId", "query")) {
            Map<String, Object> update = new HashMap<>();
            update.put(field, null);
            assertEquals("MissingParameter", assertThrows(OciException.class,
                    () -> service.updateAlarm(alarm.getId(), update, null)).getCode(), field);
        }
        assertEquals("custom_app", service.getAlarm(alarm.getId()).getSpec().get("namespace"));
    }

    @Test
    void suppressionsSilenceTheAlarm() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        StoredAlarmSuppression suppression = service.createAlarmSuppression(Map.of(
                "alarmSuppressionTarget", Map.of("targetType", "ALARM", "alarmId", alarm.getId()),
                "level", "ALARM", "displayName", "maintenance",
                "timeSuppressFrom", Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                "timeSuppressUntil", Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        post(metric("a", minutesAgo(4), 95));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("OK", service.getAlarm(alarm.getId()).getStatus());
        assertTrue(evaluator.suspended(service.getAlarm(alarm.getId()), Instant.now()));
        verify(notifications, never()).publishInternal(anyString(), anyString(), anyString(), anyString());

        assertEquals(List.of(suppression.getId()), service.listAlarmSuppressions(alarm.getId(), null, null, null,
                null, null, null, null, null, null).stream().map(StoredAlarmSuppression::getId).toList());
        service.deleteAlarmSuppression(suppression.getId(), null);
        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("FIRING", service.getAlarm(alarm.getId()).getStatus());
    }

    @Test
    void dimensionSuppressionsNeedDimensions() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].max() > 80"));
        assertThrows(OciException.class, () -> service.createAlarmSuppression(Map.of(
                "alarmSuppressionTarget", Map.of("targetType", "ALARM", "alarmId", alarm.getId()),
                "displayName", "x", "timeSuppressFrom", Instant.now().toString(),
                "timeSuppressUntil", Instant.now().plusSeconds(60).toString())));

        service.createAlarmSuppression(Map.of(
                "alarmSuppressionTarget", Map.of("targetType", "ALARM", "alarmId", alarm.getId()),
                "displayName", "only a", "dimensions", Map.of("host", "a"),
                "timeSuppressFrom", Instant.now().minusSeconds(60).toString(),
                "timeSuppressUntil", Instant.now().plusSeconds(3600).toString()));
        post(metric("a", minutesAgo(4), 95), metric("b", minutesAgo(4), 95));
        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());

        StoredAlarm firing = service.getAlarm(alarm.getId());
        assertEquals("FIRING", firing.getStatus());
        assertEquals(List.of(Map.of("host", "b")), firing.getStreams().values().stream()
                .map(state -> state.get("dimensions")).toList());
    }

    @Test
    void absenceAlarmsFireWhenAStreamStopsReporting() {
        StoredAlarm alarm = service.createAlarm(alarmBody("Cpu[1m].absent()"));
        post(metric("a", minutesAgo(30), 1));

        evaluator.evaluate(service.getAlarm(alarm.getId()), Instant.now());
        assertEquals("FIRING", service.getAlarm(alarm.getId()).getStatus());
    }
}
