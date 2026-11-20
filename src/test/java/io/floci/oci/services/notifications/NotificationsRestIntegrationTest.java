package io.floci.oci.services.notifications;

import com.sun.net.httpserver.HttpServer;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class NotificationsRestIntegrationTest {

    private static String compartment;
    private static HttpServer listener;
    private static final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startListener() throws IOException {
        listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listener.createContext("/hook", exchange -> {
            Map<String, String> request = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> request.put(k.toLowerCase(), v.get(0)));
            request.put("path", exchange.getRequestURI().getPath());
            request.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(request);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        listener.start();
    }

    @AfterAll
    static void stopListener() {
        listener.stop(0);
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itons");
        }
    }

    private static String hook(String name) {
        return "http://127.0.0.1:" + listener.getAddress().getPort() + "/hook/" + name;
    }

    private static String createTopic(String name) {
        return given().contentType("application/json")
                .body(Map.of("name", name + "-" + System.nanoTime(), "compartmentId", compartment))
            .when().post("/20181201/topics")
            .then().statusCode(200).extract().path("topicId");
    }

    private static String subscribe(String topicId, String protocol, String endpoint) {
        return given().contentType("application/json")
                .body(Map.of("topicId", topicId, "compartmentId", compartment, "protocol", protocol,
                        "endpoint", endpoint))
            .when().post("/20181201/subscriptions")
            .then().statusCode(200).extract().path("id");
    }

    private static Map<String, String> awaitRequest(String path, String messageType) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> received.stream()
                .filter(r -> path.equals(r.get("path")) && messageType.equals(r.get("x-oci-ns-messagetype")))
                .findFirst().orElse(null), notNullValue());
    }

    /**
     * Opens a link the emulator minted. Links carry the configured base URL, which a test server on a
     * random port does not listen on, so only the path and query are followed.
     */
    private static ValidatableResponse follow(String link) {
        URI uri = URI.create(link);
        return given().urlEncodingEnabled(false).when().get(uri.getRawPath() + "?" + uri.getRawQuery()).then();
    }

    @Test
    void topicCrudRoundtrip() {
        String name = "it-topic-" + System.nanoTime();
        String topicId = given().contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment, "description", "first"))
            .when().post("/20181201/topics")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .body("topicId", startsWith("ocid1.onstopic."))
                .body("name", equalTo(name))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("shortTopicId", hasLength(6))
                .body("apiEndpoint", startsWith("http"))
                .body("timeCreated", notNullValue())
                .extract().path("topicId");

        given().contentType("application/json").body(Map.of("name", name, "compartmentId", compartment))
            .when().post("/20181201/topics")
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().queryParam("compartmentId", compartment).queryParam("name", name)
            .when().get("/20181201/topics")
            .then().statusCode(200).body("size()", equalTo(1)).body("[0].topicId", equalTo(topicId));

        String etag = given().when().get("/20181201/topics/" + topicId)
            .then().statusCode(200).extract().header("etag");
        given().contentType("application/json").header("if-match", "stale").body(Map.of("description", "x"))
            .when().put("/20181201/topics/" + topicId)
            .then().statusCode(412);
        given().contentType("application/json").header("if-match", etag).body(Map.of("description", "second"))
            .when().put("/20181201/topics/" + topicId)
            .then().statusCode(200).body("description", equalTo("second"));

        given().when().delete("/20181201/topics/" + topicId).then().statusCode(204);
        given().when().get("/20181201/topics/" + topicId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void emailSubscriptionIsConfirmedThroughTheCapturedLink() {
        String topicId = createTopic("it-email");
        String address = "ops-" + System.nanoTime() + "@example.com";
        String subscriptionId = given().contentType("application/json")
                .body(Map.of("topicId", topicId, "compartmentId", compartment, "protocol", "EMAIL",
                        "endpoint", address))
            .when().post("/20181201/subscriptions")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.onssubscription."))
                .body("lifecycleState", equalTo("PENDING"))
                .body("createdTime", notNullValue())
                .body("deliverPolicy", equalTo(
                        "{\"backoffRetryPolicy\":{\"maxRetryDuration\":7200000,\"policyType\":\"EXPONENTIAL\"}}"))
                .extract().path("id");

        String confirmationUrl = given().queryParam("subscriptionId", subscriptionId)
            .when().get("/_floci-oci/notifications/messages")
            .then().statusCode(200)
                .body("messages[0].messageType", equalTo("SubscriptionConfirmation"))
                .body("messages[0].status", equalTo("CAPTURED"))
                .extract().path("messages[0].confirmationUrl");

        JsonPath confirmation = follow(confirmationUrl).statusCode(200)
                .body("subscriptionId", equalTo(subscriptionId))
                .body("topicId", equalTo(topicId))
                .body("endpoint", equalTo(address))
                .body("unsubscribeUrl", containsString("/unsubscription?token="))
                .extract().jsonPath();
        given().when().get("/20181201/subscriptions/" + subscriptionId)
            .then().statusCode(200).body("lifecycleState", equalTo("ACTIVE"));

        given().contentType("application/json").body(Map.of("body", "disk full", "title", "Alert"))
            .when().post("/20181201/topics/" + topicId + "/messages")
            .then().statusCode(200).body("messageId", notNullValue()).body("timeStamp", notNullValue());
        given().queryParam("subscriptionId", subscriptionId).queryParam("protocol", "EMAIL")
            .when().get("/_floci/notifications/messages")
            .then().statusCode(200)
                .body("messages.find { it.messageType == 'Notification' }.body", equalTo("disk full"))
                .body("messages.find { it.messageType == 'Notification' }.title", equalTo("Alert"));

        String unsubscribed = follow(confirmation.getString("unsubscribeUrl")).statusCode(200)
                .contentType(containsString("application/json")).extract().asString();
        assertTrue(unsubscribed.startsWith("\"") && unsubscribed.endsWith("\""), unsubscribed);
        given().when().get("/20181201/subscriptions/" + subscriptionId).then().statusCode(404);
    }

    @Test
    void subscriptionListsUpdatesAndDeletes() {
        String topicId = createTopic("it-subs");
        String subscriptionId = subscribe(topicId, "SMS", "+14255550100");

        given().queryParam("compartmentId", compartment).queryParam("topicId", topicId)
            .when().get("/20181201/subscriptions")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(subscriptionId))
                .body("[0].deliveryPolicy.backoffRetryPolicy.policyType", equalTo("EXPONENTIAL"));

        given().contentType("application/json")
                .body(Map.of("deliveryPolicy", Map.of("backoffRetryPolicy",
                        Map.of("maxRetryDuration", 60000, "policyType", "EXPONENTIAL")),
                        "freeformTags", Map.of("team", "sre")))
            .when().put("/20181201/subscriptions/" + subscriptionId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("deliveryPolicy.backoffRetryPolicy.maxRetryDuration", equalTo(60000))
                .body("freeformTags.team", equalTo("sre"))
                .body("id", nullValue());

        given().when().post("/20181201/subscriptions/" + subscriptionId + "/resendConfirmation")
            .then().statusCode(200).body("lifecycleState", equalTo("PENDING"));

        given().when().delete("/20181201/subscriptions/" + subscriptionId).then().statusCode(204);
        given().when().get("/20181201/subscriptions/" + subscriptionId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void invalidRequestsAnswerOciErrors() {
        String topicId = createTopic("it-bad");
        given().contentType("application/json")
                .body(Map.of("topicId", topicId, "compartmentId", compartment, "protocol", "CUSTOM_HTTPS",
                        "endpoint", "https://example.com/hook?token=1"))
            .when().post("/20181201/subscriptions")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().contentType("application/json")
                .body(Map.of("topicId", "ocid1.onstopic.oc1.iad.missing", "compartmentId", compartment,
                        "protocol", "EMAIL", "endpoint", "a@example.com"))
            .when().post("/20181201/subscriptions")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
        given().when().get("/20181201/topics")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().contentType("application/json").header("messageType", "JSON").body(Map.of("body", "{}"))
            .when().post("/20181201/topics/" + topicId + "/messages")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().queryParam("token", "x.y").queryParam("protocol", "EMAIL")
            .when().get("/20181201/subscriptions/ocid1.onssubscription.oc1.iad.missing/confirmation")
            .then().statusCode(404);
    }

    @Test
    void httpsSubscriptionIsConfirmedByItsEndpointAndReceivesMessages() {
        String topicId = createTopic("it-https");
        String subscriptionId = subscribe(topicId, "CUSTOM_HTTPS", hook("direct"));

        Map<String, String> confirmation = awaitRequest("/hook/direct", "SubscriptionConfirmation");
        assertEquals(subscriptionId, confirmation.get("x-oci-ns-subscriptionid"));
        assertEquals("application/json", confirmation.get("content-type"));
        String confirmationUrl = confirmation.get("x-oci-ns-confirmationurl");
        assertTrue(confirmation.get("body").contains(confirmationUrl), confirmation.get("body"));
        follow(confirmationUrl).statusCode(200);

        given().contentType("application/json").body(Map.of("body", "hello https"))
            .when().post("/20181201/topics/" + topicId + "/messages")
            .then().statusCode(200);

        Map<String, String> message = awaitRequest("/hook/direct", "Notification");
        assertEquals("hello https", message.get("body"));
        assertEquals(topicId, message.get("x-oci-ns-topicocid"));
        assertTrue(message.get("x-oci-ns-unsubscribeurl").contains(subscriptionId));
    }

    @Test
    void eventsRuleDeliversToAnHttpsSubscriptionThroughOns() {
        String topicId = createTopic("it-events-ons");
        subscribe(topicId, "CUSTOM_HTTPS", hook("events"));
        Map<String, String> confirmation = awaitRequest("/hook/events", "SubscriptionConfirmation");
        follow(confirmation.get("x-oci-ns-confirmationurl")).statusCode(200);

        String namespace = given().when().get("/n").then().statusCode(200).extract().asString().replace("\"", "");
        String bucket = "it-ons-" + System.nanoTime();
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "name", bucket, "objectEventsEnabled", true))
            .when().post("/n/" + namespace + "/b")
            .then().statusCode(200);
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "to-ons", "isEnabled", true,
                        "condition", "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\","
                                + "\"data\":{\"additionalDetails\":{\"bucketName\":\"" + bucket + "\"}}}",
                        "actions", Map.of("actions", List.of(Map.of(
                                "actionType", "ONS", "isEnabled", true, "topicId", topicId)))))
            .when().post("/20181201/rules")
            .then().statusCode(200);

        given().contentType("application/octet-stream").body("hi".getBytes(StandardCharsets.UTF_8))
            .when().put("/n/" + namespace + "/b/" + bucket + "/o/event.txt")
            .then().statusCode(200);

        Map<String, String> delivered = awaitRequest("/hook/events", "Notification");
        assertEquals("application/json", delivered.get("content-type"));
        JsonPath envelope = new JsonPath(delivered.get("body"));
        assertEquals("com.oraclecloud.objectstorage.createobject", envelope.getString("eventType"));
        assertEquals("event.txt", envelope.getString("data.resourceName"));
        assertEquals(bucket, envelope.getString("data.additionalDetails.bucketName"));
    }
}
