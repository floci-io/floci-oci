package io.floci.oci.services.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciEvents;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.events.model.StoredRule;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.notifications.NotificationsService;
import io.floci.oci.services.streaming.StreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventsServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..eventstest";

    private EventsService service;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.services().events().enabled()).thenReturn(true);
        service = new EventsService(new InMemoryStorage<>(), config, new ObjectMapper());
    }

    private Map<String, Object> ruleBody(String condition, boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("compartmentId", COMPARTMENT);
        body.put("displayName", "rule");
        body.put("condition", condition);
        body.put("isEnabled", enabled);
        body.put("actions", Map.of("actions", List.of(Map.of(
                "actionType", "ONS", "isEnabled", true, "topicId", "ocid1.onstopic.oc1.iad.x"))));
        return body;
    }

    private static OciEvents.Event createObject(String bucket) {
        return new OciEvents.Event("com.oraclecloud.objectstorage.createobject", "objectstorage", COMPARTMENT,
                "a.txt", "", null, null, Map.of("bucketName", bucket, "namespace", "ns"));
    }

    @Test
    void createKeepsTheConditionVerbatimAndFillsActionState() {
        String condition = "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\"}";
        StoredRule rule = service.createRule(ruleBody(condition, true));

        assertTrue(rule.getId().startsWith("ocid1.eventrule.oc1.iad."));
        assertEquals(condition, rule.getCondition());
        assertEquals("ACTIVE", rule.getLifecycleState());
        Map<String, Object> action = rule.getActions().get(0);
        assertEquals("ONS", action.get("actionType"));
        assertEquals("ACTIVE", action.get("lifecycleState"));
        assertEquals("", action.get("lifecycleMessage"));
        assertFalse(action.containsKey("description"));
    }

    @Test
    void disabledRuleIsInactive() {
        assertEquals("INACTIVE", service.createRule(ruleBody("{}", false)).getLifecycleState());
    }

    @Test
    void conditionThatIsNotJsonIsInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> service.createRule(ruleBody("not json", true)));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void streamingActionNeedsAStreamId() {
        Map<String, Object> body = ruleBody("{}", true);
        body.put("actions", Map.of("actions", List.of(Map.of("actionType", "OSS", "isEnabled", true))));
        OciException e = assertThrows(OciException.class, () -> service.createRule(body));
        assertEquals("MissingParameter", e.getCode());
    }

    @Test
    void unknownActionTypeIsRejected() {
        Map<String, Object> body = ruleBody("{}", true);
        body.put("actions", Map.of("actions", List.of(Map.of("actionType", "SQS", "isEnabled", true))));
        assertThrows(OciException.class, () -> service.createRule(body));
    }

    @Test
    void updateReplacesTheActionList() {
        StoredRule rule = service.createRule(ruleBody("{}", true));
        String firstActionId = (String) rule.getActions().get(0).get("id");
        StoredRule updated = service.updateRule(rule.getId(), Map.of("actions", Map.of("actions", List.of(
                Map.of("actionType", "ONS", "isEnabled", false, "topicId", "ocid1.onstopic.oc1.iad.y")))), null);
        assertEquals(1, updated.getActions().size());
        assertNotEquals(firstActionId, updated.getActions().get(0).get("id"));
    }

    @Test
    void matchingFiltersTheEnvelopeThatIsDelivered() {
        StoredRule rule = service.createRule(ruleBody("{\"eventID\":\"fixed-*\"}", true));
        Map<String, Object> envelope = new HashMap<>(service.envelope(createObject("b")));
        envelope.put("eventID", "fixed-1");

        assertEquals(List.of(rule.getId()),
                service.matchingRules(createObject("b"), envelope).stream().map(StoredRule::getId).toList());
        assertTrue(service.matchingRules(createObject("b")).isEmpty());
    }

    @Test
    void matchingUsesConditionAndSkipsDisabledRules() {
        StoredRule matching = service.createRule(ruleBody(
                "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\","
                        + "\"data\":{\"additionalDetails\":{\"bucketName\":\"uploads*\"}}}", true));
        service.createRule(ruleBody("{}", false));
        service.createRule(ruleBody("{\"eventType\":\"com.oraclecloud.objectstorage.deleteobject\"}", true));

        List<StoredRule> matched = service.matchingRules(createObject("uploads-2026"));
        assertEquals(List.of(matching.getId()), matched.stream().map(StoredRule::getId).toList());
        assertTrue(service.matchingRules(createObject("other")).isEmpty());
    }

    @Test
    void ruleInAnotherCompartmentDoesNotMatch() {
        Map<String, Object> body = ruleBody("{}", true);
        body.put("compartmentId", "ocid1.compartment.oc1..elsewhere");
        service.createRule(body);
        assertTrue(service.matchingRules(createObject("b")).isEmpty());
    }

    @Test
    void envelopeFollowsTheCloudEventsShape() {
        Map<String, Object> envelope = service.envelope(createObject("b"));
        assertEquals("0.1", envelope.get("cloudEventsVersion"));
        assertEquals("2.0", envelope.get("eventTypeVersion"));
        assertEquals("application/json", envelope.get("contentType"));
        assertEquals("objectstorage", envelope.get("source"));
        assertNotNull(envelope.get("eventID"));
        assertEquals(Map.of("compartmentId", COMPARTMENT), envelope.get("extensions"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertEquals("all", data.get("availabilityDomain"));
        assertEquals("a.txt", data.get("resourceName"));
        assertFalse(data.containsKey("freeFormTags"));
    }

    @Test
    void listSortsAndFilters() throws InterruptedException {
        StoredRule first = service.createRule(ruleBody("{}", true));
        Thread.sleep(5);
        StoredRule second = service.createRule(ruleBody("{}", false));
        assertEquals(List.of(first.getId(), second.getId()),
                service.listRules(COMPARTMENT, null, null, null, null).stream().map(StoredRule::getId).toList());
        assertEquals(List.of(second.getId()),
                service.listRules(COMPARTMENT, null, "INACTIVE", null, null).stream().map(StoredRule::getId).toList());
    }

    @Test
    void conditionThatIsJsonNullIsInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> service.createRule(ruleBody("null", true)));
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameter", e.getCode());
        assertThrows(OciException.class, () -> service.createRule(ruleBody("[]", true)));
    }

    @Test
    void oneUnreadableConditionDoesNotStopTheOtherRules() {
        StoredRule good = service.createRule(ruleBody("{}", true));
        StoredRule broken = service.createRule(ruleBody("{}", true));
        // A condition stored before validation rejected JSON null, for example by an older version.
        broken.setCondition("null");

        assertEquals(List.of(good.getId()),
                service.matchingRules(createObject("b")).stream().map(StoredRule::getId).toList());
    }

    @Test
    void rejectedUpdateLeavesTheRuleUnchanged() {
        StoredRule rule = service.createRule(ruleBody("{}", true));
        String etag = rule.getEtag();
        Map<String, Object> update = Map.of("isEnabled", false, "displayName", "renamed",
                "actions", Map.of("actions", List.of(Map.of("actionType", "invalid", "isEnabled", true))));

        assertThrows(OciException.class, () -> service.updateRule(rule.getId(), update, null));

        StoredRule stored = service.getRule(rule.getId());
        assertTrue(stored.isEnabled());
        assertEquals("ACTIVE", stored.getLifecycleState());
        assertEquals("rule", stored.getDisplayName());
        assertEquals(etag, stored.getEtag());
    }

    @Test
    void actionsToADisabledServiceAreNotDelivered() {
        FunctionsService functions = mock(FunctionsService.class);
        StreamingService streaming = mock(StreamingService.class);
        NotificationsService notifications = mock(NotificationsService.class);
        EventsService delivering = new EventsService(new InMemoryStorage<>(), config, new ObjectMapper(),
                functions, streaming, notifications);
        when(config.services().functions().enabled()).thenReturn(false);
        when(config.services().streaming().enabled()).thenReturn(false);
        when(config.services().notifications().enabled()).thenReturn(false);
        StoredRule rule = new StoredRule();
        rule.setId("ocid1.eventrule.oc1.iad.x");
        byte[] payload = "{}".getBytes();

        delivering.deliver(rule, Map.of("actionType", "FAAS", "functionId", "ocid1.fnfunc.oc1.iad.f"), payload);
        delivering.deliver(rule, Map.of("actionType", "OSS", "streamId", "ocid1.stream.oc1.iad.s"), payload);
        verify(functions, never()).invoke(anyString(), any(), anyString());
        delivering.deliver(rule, Map.of("actionType", "ONS", "topicId", "ocid1.onstopic.oc1.iad.t"), payload);
        verify(streaming, never()).putMessages(anyString(), anyList());
        verify(notifications, never()).publishEvent(anyString(), any());

        when(config.services().functions().enabled()).thenReturn(true);
        when(config.services().streaming().enabled()).thenReturn(true);
        when(config.services().notifications().enabled()).thenReturn(true);
        delivering.deliver(rule, Map.of("actionType", "FAAS", "functionId", "ocid1.fnfunc.oc1.iad.f"), payload);
        delivering.deliver(rule, Map.of("actionType", "OSS", "streamId", "ocid1.stream.oc1.iad.s"), payload);
        delivering.deliver(rule, Map.of("actionType", "ONS", "topicId", "ocid1.onstopic.oc1.iad.t"), payload);
        verify(functions).invoke("ocid1.fnfunc.oc1.iad.f", payload, "application/json");
        verify(streaming).putMessages(anyString(), anyList());
        verify(notifications).publishEvent("ocid1.onstopic.oc1.iad.t", payload);
    }
}
