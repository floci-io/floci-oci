package io.floci.oci.services.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.logging.model.StoredLoggingResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class LoggingServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..logtest";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..logtest";

    private LoggingService service;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new LoggingService(new InMemoryStorage<>(), new InMemoryStorage<>(), workRequests, config, null,
                null, new ObjectMapper());
    }

    private String created(String workRequestId) {
        StoredWorkRequest wr = workRequests.get(workRequestId);
        assertEquals("SUCCEEDED", wr.getStatus());
        return wr.getResources().get(0).getIdentifier();
    }

    private String group(String name) {
        return created(service.createLogGroup(Map.of("compartmentId", COMPARTMENT, "displayName", name)));
    }

    private String log(String groupId, String name) {
        return created(service.createLog(groupId, Map.of("displayName", name, "logType", "CUSTOM")));
    }

    private void put(String logId, Instant at, String... data) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            entries.add(Map.of("data", data[i], "id", "e" + i + "-" + at.toEpochMilli(), "time", at.plusMillis(i).toString()));
        }
        service.putLogs(logId, Map.of("specversion", "1.0", "logEntryBatches", List.of(Map.of(
                "source", "web-1", "type", "app.access", "subject", "/var/log/app.log",
                "defaultlogentrytime", at.toString(), "entries", entries))));
    }

    private List<Map<String, Object>> search(String query) {
        return service.search(Map.of("timeStart", Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                "timeEnd", Instant.now().plus(1, ChronoUnit.MINUTES).toString(), "searchQuery", query));
    }

    @Test
    void logGroupAndLogLifecycleThroughWorkRequests() {
        String groupId = group("app");
        assertTrue(groupId.startsWith("ocid1.loggroup.oc1.iad."));
        assertEquals("loggroup", workRequests.list("logging", COMPARTMENT).get(0).getResources().get(0).getEntityType());

        String logId = log(groupId, "access");
        assertTrue(logId.startsWith("ocid1.log.oc1.iad."));
        StoredLoggingResource log = service.getLog(groupId, logId);
        assertEquals(30, log.getSpec().get("retentionDuration"));
        assertEquals(true, log.getSpec().get("isEnabled"));
        assertEquals(COMPARTMENT, log.getCompartmentId());

        assertEquals("Conflict", assertThrows(OciException.class, () -> log(groupId, "access")).getCode());
        assertEquals("Conflict", assertThrows(OciException.class, () -> service.deleteLogGroup(groupId, null)).getCode());
        assertThrows(OciException.class, () -> service.getLog("ocid1.loggroup.oc1.iad.other", logId));

        service.updateLog(groupId, logId, Map.of("retentionDuration", 90, "isEnabled", false), null);
        assertEquals(90, service.getLog(groupId, logId).getSpec().get("retentionDuration"));
        assertThrows(OciException.class, () -> service.updateLog(groupId, logId, Map.of("retentionDuration", 45), null));

        service.deleteLog(groupId, logId, null);
        service.deleteLogGroup(groupId, null);
        assertEquals(404, assertThrows(OciException.class, () -> service.getLogGroup(groupId)).getHttpStatus());
    }

    @Test
    void serviceLogsNeedAnOciServiceSource() {
        String groupId = group("svc");
        assertThrows(OciException.class, () -> service.createLog(groupId, Map.of("displayName", "x", "logType", "SERVICE")));
        String logId = created(service.createLog(groupId, Map.of("displayName", "bucket-writes", "logType", "SERVICE",
                "configuration", Map.of("source", Map.of("sourceType", "OCISERVICE", "service", "objectstorage",
                        "resource", "my-bucket", "category", "write")))));

        Map<?, ?> configuration = (Map<?, ?>) service.getLog(groupId, logId).getSpec().get("configuration");
        assertEquals(COMPARTMENT, configuration.get("compartmentId"));
        assertEquals(1, service.listLogs(groupId, null, "objectstorage", "my-bucket", null, null, null, null).size());
        assertEquals(0, service.listLogs(groupId, "CUSTOM", null, null, null, null, null, null).size());
    }

    @Test
    void changeLogGroupMovesTheLog() {
        String a = group("a");
        String b = group("b");
        String logId = log(a, "events");
        service.changeLogLogGroup(a, logId, b, null);
        assertEquals(b, service.getLog(b, logId).getLogGroupId());
        assertTrue(service.listLogs(a, null, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void putLogsStoresTheEnvelopeAndSearchFindsIt() {
        String groupId = group("app");
        String logId = log(groupId, "access");
        Instant now = Instant.now().minusSeconds(5);
        put(logId, now, "{\"level\":\"ERROR\",\"status\":500,\"msg\":\"boom\"}",
                "{\"level\":\"INFO\",\"status\":200,\"msg\":\"ok\"}", "plain text line");

        List<Map<String, Object>> all = search("search \"" + COMPARTMENT + "/app/access\"");
        assertEquals(3, all.size());
        Map<?, ?> content = (Map<?, ?>) all.get(0).get("logContent");
        assertEquals("web-1", content.get("source"));
        assertEquals("app.access", content.get("type"));
        assertEquals(Map.of("level", "ERROR", "status", 500, "msg", "boom"), content.get("data"));
        Map<?, ?> oracle = (Map<?, ?>) content.get("oracle");
        assertEquals(logId, oracle.get("logid"));
        assertEquals(groupId, oracle.get("loggroupid"));
        assertEquals(TENANCY, oracle.get("tenantid"));
        assertEquals(now.toEpochMilli(), all.get(0).get("datetime"));

        assertEquals(1, search("search \"" + COMPARTMENT + "\" | where data.level = 'ERROR'").size());
        assertEquals(2, search("search \"" + COMPARTMENT + "\" | data.status >= 200 and data.status < 600").size());
        assertEquals(1, search("search \"" + COMPARTMENT + "\" | where logContent = '*plain*'").size());
        assertEquals(1, search("search \"" + COMPARTMENT + "\" | where contains_ci(data.msg, 'BOO')").size());
        assertEquals(List.of(Map.of("count", 3)), search("search \"" + COMPARTMENT + "\" | count"));
        assertEquals(3, search("search \"" + TENANCY + "\"").size());
        assertEquals(0, search("search \"ocid1.compartment.oc1..elsewhere\"").size());
    }

    @Test
    void queryPipesSortProjectAndSummarize() {
        String logId = log(group("app"), "access");
        Instant now = Instant.now().minusSeconds(5);
        put(logId, now, "{\"host\":\"a\",\"ms\":10}", "{\"host\":\"b\",\"ms\":30}", "{\"host\":\"a\",\"ms\":20}");
        String scope = "search \"" + COMPARTMENT + "/app\"";

        List<Map<String, Object>> sorted = search(scope + " | sort by data.ms desc | select data.host as host, data.ms");
        assertEquals(List.of(Map.of("host", "b", "ms", 30), Map.of("host", "a", "ms", 20), Map.of("host", "a", "ms", 10)),
                sorted);
        assertEquals(2, search(scope + " | dedup data.host").size());
        assertEquals(1, search(scope + " | top 1 by data.ms").size());

        Map<String, Object> a = new HashMap<>();
        a.put("host", "a");
        a.put("calls", 2);
        a.put("slowest", 20.0);
        Map<String, Object> b = new HashMap<>();
        b.put("host", "b");
        b.put("calls", 1);
        b.put("slowest", 30.0);
        assertEquals(List.of(a, b), search(scope + " | summarize count() as calls, max(data.ms) as slowest by data.host as host"));
    }

    @Test
    void invalidRequestsAreRejected() {
        String logId = log(group("app"), "access");
        assertThrows(OciException.class, () -> service.putLogs(logId, Map.of("specversion", "2.0",
                "logEntryBatches", List.of())));
        assertThrows(OciException.class, () -> search("where level = 'x'"));
        assertThrows(OciException.class, () -> search("search \"" + COMPARTMENT + "\" | sort data.ms"));
        assertEquals(404, assertThrows(OciException.class, () -> service.putLogs("ocid1.log.oc1.iad.missing",
                Map.of("specversion", "1.0"))).getHttpStatus());
    }

    @Test
    void retentionAppliesWhenReadingToo() {
        String groupId = group("app");
        String logId = created(service.createLog(groupId, Map.of("displayName", "old", "logType", "CUSTOM",
                "retentionDuration", 90)));
        put(logId, Instant.now().minus(40, ChronoUnit.DAYS), "{\"n\":1}");
        Map<String, Object> window = Map.of("timeStart", Instant.now().minus(60, ChronoUnit.DAYS).toString(),
                "timeEnd", Instant.now().toString(), "searchQuery", "search \"" + COMPARTMENT + "\"");
        assertEquals(1, service.search(window).size());

        service.updateLog(groupId, logId, Map.of("retentionDuration", 30), null);
        assertTrue(service.search(window).isEmpty());
    }

    @Test
    void movingALogGroupKeepsNamesUniqueInTheDestination() {
        String here = group("app");
        String other = "ocid1.compartment.oc1..destination";
        created(service.createLogGroup(Map.of("compartmentId", other, "displayName", "app")));
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.changeLogGroupCompartment(here, other, null)).getCode());
        assertEquals(COMPARTMENT, service.getLogGroup(here).getCompartmentId());
    }

    @Test
    void disabledLogsDropWhatTheyReceive() {
        String groupId = group("app");
        String logId = log(groupId, "access");
        service.updateLog(groupId, logId, Map.of("isEnabled", false), null);
        put(logId, Instant.now().minusSeconds(5), "dropped");
        assertTrue(search("search \"" + COMPARTMENT + "\"").isEmpty());
    }

    @Test
    void savedSearchesValidateTheQuery() {
        assertThrows(OciException.class, () -> service.createSavedSearch(Map.of("compartmentId", COMPARTMENT,
                "name", "bad", "query", "nonsense")));
        StoredLoggingResource saved = service.createSavedSearch(Map.of("compartmentId", COMPARTMENT, "name", "errors",
                "query", "search \"" + COMPARTMENT + "\" | where data.level = 'ERROR'"));
        assertTrue(saved.getId().startsWith("ocid1.logsavedsearch."));
        assertEquals(1, service.listSavedSearches(COMPARTMENT, null, "errors", null, null).size());
        service.deleteSavedSearch(saved.getId(), null);
        assertThrows(OciException.class, () -> service.getSavedSearch(saved.getId()));
    }
}
