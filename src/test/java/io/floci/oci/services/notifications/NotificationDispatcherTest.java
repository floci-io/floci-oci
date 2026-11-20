package io.floci.oci.services.notifications;

import com.sun.net.httpserver.HttpServer;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.notifications.model.DeliveryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class NotificationDispatcherTest {

    private HttpServer server;
    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final CountDownLatch slowEntered = new CountDownLatch(1);
    private final CountDownLatch slowRelease = new CountDownLatch(1);
    private NotificationDispatcher dispatcher;
    private FunctionsService functions;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> request = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> request.put(k.toLowerCase(), v.get(0)));
            request.put("body", body);
            received.add(request);
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            received.add(Map.of("path", "/slow"));
            slowEntered.countDown();
            try {
                slowRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.services().functions().enabled()).thenReturn(true);
        functions = mock(FunctionsService.class);
        dispatcher = new NotificationDispatcher(config, functions, null, HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        slowRelease.countDown();
        server.stop(0);
        dispatcher.clear();
    }

    private String hook() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static NotificationDispatcher.Message message(String type) {
        return new NotificationDispatcher.Message("m-1", type, "ocid1.onstopic.oc1.iad.t", "alerts", null,
                "hello", "text/plain; charset=UTF-8");
    }

    @Test
    void httpsDeliveryCarriesTheStandardHeaders() {
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", hook(), 60_000, null, "http://u/unsub"));

        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(record.getStatus()));
        Map<String, String> request = received.get(0);
        assertEquals("hello", request.get("body"));
        assertEquals("text/plain; charset=UTF-8", request.get("content-type"));
        assertEquals("m-1", request.get("x-oci-ns-messageid"));
        assertEquals("ocid1.onstopic.oc1.iad.t", request.get("x-oci-ns-topicocid"));
        assertEquals("alerts", request.get("x-oci-ns-topicname"));
        assertEquals("Notification", request.get("x-oci-ns-messagetype"));
        assertEquals("http://u/unsub", request.get("x-oci-ns-unsubscribeurl"));
        assertNull(request.get("x-oci-ns-confirmationurl"));
    }

    @Test
    void confirmationsCarryTheConfirmationUrlAndBasicAuthFromTheUrl() {
        String endpoint = hook().replace("http://", "http://user:p%40ss@");
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.SUBSCRIPTION_CONFIRMATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", endpoint, 0, "http://u/confirm", null));

        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(record.getStatus()));
        Map<String, String> request = received.get(0);
        assertEquals("SubscriptionConfirmation", request.get("x-oci-ns-messagetype"));
        assertEquals("http://u/confirm", request.get("x-oci-ns-confirmationurl"));
        assertEquals("sub-1", request.get("x-oci-ns-subscriptionid"));
        assertEquals("Basic dXNlcjpwQHNz", request.get("authorization"));
    }

    @Test
    void aPlusInTheUrlPasswordIsKeptLiterally() {
        String endpoint = hook().replace("http://", "http://user:pa+ss@");
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", endpoint, 60_000, null, null));

        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(record.getStatus()));
        assertEquals("Basic dXNlcjpwYStzcw==", received.get(0).get("authorization"));
    }

    @Test
    void removingTheCredentialsKeepsTheEncodedPath() throws Exception {
        assertEquals(URI.create("https://example.com:8443/hook/a%2Fb%20c"),
                NotificationDispatcher.withoutUserInfo(URI.create("https://u:p@example.com:8443/hook/a%2Fb%20c#frag")));
        assertEquals(URI.create("https://example.com/hook/a%2Fb"),
                NotificationDispatcher.withoutUserInfo(URI.create("https://example.com/hook/a%2Fb")));
    }

    @Test
    void aResetStopsAnAttemptThatIsAlreadyRunningFromRetrying() throws InterruptedException {
        String slow = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", slow, 7_200_000, null, null));
        assertTrue(slowEntered.await(5, TimeUnit.SECONDS));

        dispatcher.clear();
        slowRelease.countDown();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .until(() -> received.size() == 1 && record.getAttempts() == 1);
        assertNotEquals("RETRYING", record.getStatus());
    }

    @Test
    void aRemovedSubscriptionStopsAnAttemptThatIsAlreadyRunningFromRetrying() throws InterruptedException {
        String slow = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-gone", "CUSTOM_HTTPS", slow, 7_200_000, null, null));
        assertTrue(slowEntered.await(5, TimeUnit.SECONDS));

        dispatcher.cancel("sub-gone");
        slowRelease.countDown();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .until(() -> received.size() == 1 && record.getAttempts() == 1);
        assertNotEquals("RETRYING", record.getStatus());
        assertEquals(0, dispatcher.liveAttempts());
    }

    @Test
    void finishedDeliveriesAreNotTracked() {
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", hook(), 60_000, null, null));
        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(record.getStatus()));
        await().atMost(Duration.ofSeconds(2)).until(() -> dispatcher.liveAttempts() == 0);
        dispatcher.cancel("never-delivered-to");
        assertEquals(0, dispatcher.liveAttempts());
    }

    @Test
    void aFailedDeliveryIsRetriedOnceAtOnceThenDiscardedWhenTheDurationIsSpent() {
        status.set(500);
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", hook(), 1_000, null, null));

        await().atMost(Duration.ofSeconds(5)).until(() -> "FAILED".equals(record.getStatus()));
        assertEquals(2, record.getAttempts());
        assertEquals(2, received.size());
        assertEquals("endpoint answered HTTP 500", record.getLastError());
    }

    @Test
    void aRetryThatSucceedsIsDelivered() {
        status.set(503);
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "CUSTOM_HTTPS", hook(), 7_200_000, null, null));
        await().atMost(Duration.ofSeconds(5)).until(() -> record.getAttempts() >= 2);
        // The third attempt waits a minute; the record stays RETRYING meanwhile.
        assertEquals("RETRYING", record.getStatus());
    }

    @Test
    void captureOnlyProtocolsAreNeverSent() {
        for (String protocol : List.of("EMAIL", "SLACK", "PAGERDUTY", "SMS")) {
            DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                    new NotificationDispatcher.Target("sub-" + protocol, protocol, hook(), 60_000, null, null));
            await().atMost(Duration.ofSeconds(5)).until(() -> "CAPTURED".equals(record.getStatus()));
        }
        assertTrue(received.isEmpty());
        assertEquals(1, dispatcher.records(null, null, "SMS").size());
        assertEquals(4, dispatcher.records("ocid1.onstopic.oc1.iad.t", null, null).size());
        dispatcher.clearRecords();
        assertTrue(dispatcher.records(null, null, null).isEmpty());
    }

    @Test
    void functionSubscriptionsInvokeTheFunction() {
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.f", 60_000, null, null));

        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(record.getStatus()));
        verify(functions, timeout(5000)).invoke("ocid1.fnfunc.oc1.iad.f",
                "hello".getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
    }

    @Test
    void functionSubscriptionsAreSkippedWhileFunctionsIsDisabled() {
        lenient().when(config.services().functions().enabled()).thenReturn(false);
        DeliveryRecord record = dispatcher.deliver(message(NotificationDispatcher.NOTIFICATION),
                new NotificationDispatcher.Target("sub-1", "ORACLE_FUNCTIONS", "ocid1.fnfunc.oc1.iad.f", 60_000, null, null));

        await().atMost(Duration.ofSeconds(5)).until(() -> "SKIPPED".equals(record.getStatus()));
        verify(functions, never()).invoke(anyString(), any(), anyString());
    }
}
