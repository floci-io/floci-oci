package io.floci.oci.services.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.notifications.model.StoredSubscription;
import io.floci.oci.services.notifications.model.StoredTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationsServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..onstest";

    private NotificationsService service;
    private NotificationDispatcher dispatcher;
    private FunctionsService functions;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        lenient().when(config.services().functions().enabled()).thenReturn(true);
        dispatcher = mock(NotificationDispatcher.class);
        functions = mock(FunctionsService.class);
        service = new NotificationsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, functions,
                dispatcher, new ObjectMapper());
    }

    private StoredTopic topic(String name) {
        return service.createTopic(Map.of("name", name, "compartmentId", COMPARTMENT));
    }

    private StoredSubscription subscribe(StoredTopic topic, String protocol, String endpoint) {
        return service.createSubscription(Map.of("topicId", topic.getTopicId(), "compartmentId", COMPARTMENT,
                "protocol", protocol, "endpoint", endpoint));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private NotificationDispatcher.Target lastTarget() {
        ArgumentCaptor<NotificationDispatcher.Target> target = ArgumentCaptor.forClass(NotificationDispatcher.Target.class);
        verify(dispatcher, atLeastOnce()).deliver(any(), target.capture());
        return target.getValue();
    }

    @Test
    void createTopicIsActiveWithAShortIdAndEndpoint() {
        StoredTopic topic = topic("alerts");

        assertTrue(topic.getTopicId().startsWith("ocid1.onstopic.oc1.iad."));
        assertEquals("ACTIVE", topic.getLifecycleState());
        assertEquals(6, topic.getShortTopicId().length());
        assertEquals("http://localhost:4599", topic.getApiEndpoint());
        assertNotNull(topic.getEtag());
    }

    @Test
    void topicNamesAreUnique() {
        topic("alerts");
        OciException e = assertThrows(OciException.class, () -> topic("alerts"));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void createTopicRequiresNameAndCompartment() {
        OciException e = assertThrows(OciException.class,
                () -> service.createTopic(Map.of("compartmentId", COMPARTMENT)));
        assertEquals("MissingParameter", e.getCode());
    }

    @Test
    void listTopicsFiltersAndSorts() {
        StoredTopic a = topic("a");
        StoredTopic b = topic("b");
        service.createTopic(Map.of("name", "other", "compartmentId", "ocid1.compartment.oc1..other"));

        assertEquals(List.of(b.getTopicId(), a.getTopicId()),
                service.listTopics(COMPARTMENT, null, null, null, null, null).stream()
                        .map(StoredTopic::getTopicId).toList());
        assertEquals(List.of(a.getTopicId(), b.getTopicId()),
                service.listTopics(COMPARTMENT, null, null, null, "TIMECREATED", "ASC").stream()
                        .map(StoredTopic::getTopicId).toList());
        assertEquals(List.of(a.getTopicId()),
                service.listTopics(COMPARTMENT, null, "a", null, null, null).stream()
                        .map(StoredTopic::getTopicId).toList());
    }

    @Test
    void updateTopicHonoursIfMatchAndRotatesTheEtag() {
        StoredTopic topic = topic("alerts");
        String etag = topic.getEtag();

        assertThrows(OciException.class, () -> service.updateTopic(topic.getTopicId(),
                Map.of("description", "x"), "stale"));
        StoredTopic updated = service.updateTopic(topic.getTopicId(),
                Map.of("description", "on call", "freeformTags", Map.of("team", "sre")), etag);

        assertEquals("on call", updated.getDescription());
        assertEquals(Map.of("team", "sre"), updated.getFreeformTags());
        assertNotEquals(etag, updated.getEtag());
    }

    @Test
    void deleteTopicRemovesItsSubscriptions() {
        StoredTopic topic = topic("alerts");
        StoredSubscription subscription = subscribe(topic, "EMAIL", "ops@example.com");

        service.deleteTopic(topic.getTopicId(), null);

        assertEquals(404, assertThrows(OciException.class, () -> service.getTopic(topic.getTopicId())).getHttpStatus());
        assertEquals(404, assertThrows(OciException.class,
                () -> service.getSubscription(subscription.getId())).getHttpStatus());
    }

    @Test
    void functionSubscriptionsAreActiveWithoutConfirmation() {
        StoredTopic topic = topic("alerts");
        StoredSubscription subscription = subscribe(topic, "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.f");

        assertTrue(subscription.getId().startsWith("ocid1.onssubscription.oc1.iad."));
        assertEquals("ACTIVE", subscription.getLifecycleState());
        verify(dispatcher, never()).deliver(any(), any());
    }

    @Test
    void unknownFunctionIsARelatedResourceError() {
        StoredTopic topic = topic("alerts");
        when(functions.getFunction("ocid1.fnfunc.oc1.iad.missing"))
                .thenThrow(OciException.notAuthorizedOrNotFound("missing"));

        OciException e = assertThrows(OciException.class,
                () -> subscribe(topic, "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.missing"));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
    }

    @Test
    void otherProtocolsArePendingAndGetAConfirmation() {
        StoredTopic topic = topic("alerts");
        StoredSubscription subscription = subscribe(topic, "EMAIL", "ops@example.com");

        assertEquals("PENDING", subscription.getLifecycleState());
        NotificationDispatcher.Target target = lastTarget();
        assertEquals(subscription.getId(), target.subscriptionId());
        assertTrue(target.confirmationUrl().startsWith("http://localhost:4599/20181201/subscriptions/"
                + subscription.getId() + "/confirmation?token="));
        assertEquals("EMAIL", query(target.confirmationUrl()).get("protocol"));
    }

    @Test
    void confirmationActivatesAndUnsubscribeDeletes() {
        StoredTopic topic = topic("alerts");
        StoredSubscription subscription = subscribe(topic, "SLACK", "https://hooks.slack.com/services/a/b");
        String token = query(lastTarget().confirmationUrl()).get("token");

        assertEquals(404, assertThrows(OciException.class,
                () -> service.confirmSubscription(subscription.getId(), "bad.token", "SLACK")).getHttpStatus());
        assertEquals(404, assertThrows(OciException.class,
                () -> service.confirmSubscription(subscription.getId(), token, "EMAIL")).getHttpStatus());

        NotificationsService.Confirmation confirmation = service.confirmSubscription(subscription.getId(), token, "SLACK");
        assertEquals("ACTIVE", confirmation.subscription().getLifecycleState());
        assertEquals(topic.getName(), confirmation.topic().getName());
        assertEquals("ACTIVE", service.getSubscription(subscription.getId()).getLifecycleState());

        String unsubscribeToken = query(confirmation.unsubscribeUrl()).get("token");
        service.unsubscribe(subscription.getId(), unsubscribeToken, "SLACK");
        assertThrows(OciException.class, () -> service.getSubscription(subscription.getId()));
    }

    @Test
    void subscriptionMustBeInTheTopicCompartment() {
        StoredTopic topic = topic("alerts");
        OciException e = assertThrows(OciException.class, () -> service.createSubscription(Map.of(
                "topicId", topic.getTopicId(), "compartmentId", "ocid1.compartment.oc1..other",
                "protocol", "EMAIL", "endpoint", "a@example.com")));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void unknownTopicIsARelatedResourceError() {
        OciException e = assertThrows(OciException.class, () -> service.createSubscription(Map.of(
                "topicId", "ocid1.onstopic.oc1.iad.missing", "compartmentId", COMPARTMENT,
                "protocol", "EMAIL", "endpoint", "a@example.com")));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
    }

    @Test
    void endpointsAreValidatedPerProtocol() {
        StoredTopic topic = topic("alerts");
        for (String[] bad : new String[][] {
                {"CUSTOM_HTTPS", "https://example.com/hook?x=1"},
                {"CUSTOM_HTTPS", "ftp://example.com/hook"},
                {"CUSTOM_HTTPS", "http://169.254.169.254/latest"},
                {"EMAIL", "not-an-address"},
                {"SMS", "4255550100"},
                {"CARRIER_PIGEON", "coop"}}) {
            OciException e = assertThrows(OciException.class, () -> subscribe(topic, bad[0], bad[1]),
                    bad[0] + " " + bad[1]);
            assertEquals("InvalidParameter", e.getCode(), bad[0] + " " + bad[1]);
        }
        assertEquals("PENDING", subscribe(topic, "SMS", "+14255550100").getLifecycleState());
    }

    @Test
    void defaultDeliveryPolicyIsTwoHoursExponential() {
        StoredSubscription subscription = subscribe(topic("alerts"), "EMAIL", "a@example.com");
        assertEquals(Map.of("backoffRetryPolicy", Map.of("maxRetryDuration", 7_200_000L, "policyType", "EXPONENTIAL")),
                subscription.getDeliveryPolicy());
    }

    @Test
    void updateSubscriptionValidatesTheDeliveryPolicy() {
        StoredSubscription subscription = subscribe(topic("alerts"), "EMAIL", "a@example.com");

        assertThrows(OciException.class, () -> service.updateSubscription(subscription.getId(), Map.of(
                "deliveryPolicy", Map.of("backoffRetryPolicy", Map.of("maxRetryDuration", 60000,
                        "policyType", "LINEAR"))), null));
        StoredSubscription updated = service.updateSubscription(subscription.getId(), Map.of(
                "deliveryPolicy", Map.of("backoffRetryPolicy", Map.of("maxRetryDuration", 60000,
                        "policyType", "EXPONENTIAL"))), null);

        assertEquals(Map.of("backoffRetryPolicy", Map.of("maxRetryDuration", 60000L, "policyType", "EXPONENTIAL")),
                updated.getDeliveryPolicy());
    }

    @Test
    void publishGoesToActiveSubscriptionsOnly() {
        StoredTopic topic = topic("alerts");
        subscribe(topic, "EMAIL", "pending@example.com");
        StoredSubscription function = subscribe(topic, "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.f");
        clearInvocations(dispatcher);

        NotificationsService.PublishResult result = service.publish(topic.getTopicId(),
                Map.of("body", "disk full", "title", "Alert"), null);

        assertNotNull(result.messageId());
        ArgumentCaptor<NotificationDispatcher.Message> message = ArgumentCaptor.forClass(NotificationDispatcher.Message.class);
        ArgumentCaptor<NotificationDispatcher.Target> target = ArgumentCaptor.forClass(NotificationDispatcher.Target.class);
        verify(dispatcher, times(1)).deliver(message.capture(), target.capture());
        assertEquals(function.getId(), target.getValue().subscriptionId());
        assertEquals("disk full", message.getValue().body());
        assertEquals("Alert", message.getValue().title());
        assertEquals("Notification", message.getValue().messageType());
        assertEquals(result.messageId(), message.getValue().messageId());
    }

    @Test
    void jsonMessagesNeedADefaultAndPickTheEmailKey() {
        StoredTopic topic = topic("alerts");
        StoredSubscription email = subscribe(topic, "EMAIL", "a@example.com");
        String token = query(lastTarget().confirmationUrl()).get("token");
        service.confirmSubscription(email.getId(), token, "EMAIL");
        clearInvocations(dispatcher);

        assertThrows(OciException.class, () -> service.publish(topic.getTopicId(),
                Map.of("body", "{\"Email\":\"x\"}"), "JSON"));
        assertThrows(OciException.class, () -> service.publish(topic.getTopicId(),
                Map.of("body", "x"), "XML"));
        service.publish(topic.getTopicId(), Map.of("body", "{\"default\":\"d\",\"Email\":\"e\"}"), "JSON");

        ArgumentCaptor<NotificationDispatcher.Message> message = ArgumentCaptor.forClass(NotificationDispatcher.Message.class);
        verify(dispatcher).deliver(message.capture(), any());
        assertEquals("e", message.getValue().body());
    }

    @Test
    void oversizedMessagesAreRejected() {
        StoredTopic topic = topic("alerts");
        OciException e = assertThrows(OciException.class, () -> service.publish(topic.getTopicId(),
                Map.of("body", "x".repeat(64 * 1024 + 1)), null));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void publishEventDeliversTheEnvelopeAsJson() {
        StoredTopic topic = topic("alerts");
        subscribe(topic, "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.f");

        service.publishEvent(topic.getTopicId(), "{\"eventType\":\"x\"}".getBytes());

        ArgumentCaptor<NotificationDispatcher.Message> message = ArgumentCaptor.forClass(NotificationDispatcher.Message.class);
        verify(dispatcher).deliver(message.capture(), any());
        assertEquals("{\"eventType\":\"x\"}", message.getValue().body());
        assertEquals("application/json", message.getValue().contentType());
    }

    @Test
    void resourcesInListsTopicsAndSubscriptions() {
        StoredTopic topic = topic("alerts");
        StoredSubscription subscription = subscribe(topic, "EMAIL", "a@example.com");

        assertEquals(List.of("topic " + topic.getTopicId(), "subscription " + subscription.getId()),
                service.resourcesIn("ocid1.tenancy.oc1..ignored", COMPARTMENT));
    }
}
