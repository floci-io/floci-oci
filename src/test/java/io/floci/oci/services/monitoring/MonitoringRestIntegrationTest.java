package io.floci.oci.services.monitoring;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(MonitoringRestIntegrationTest.FastAlarmsProfile.class)
class MonitoringRestIntegrationTest {

    private static String compartment;

    public static class FastAlarmsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.monitoring.alarm-evaluation-interval-seconds", "1");
        }
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itmonitoring");
        }
    }

    private static String minutesAgo(int minutes) {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(minutes, ChronoUnit.MINUTES).plusSeconds(10)
                .toString();
    }

    private static void post(String name, String host, double value, int... minutesAgo) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int m : minutesAgo) {
            points.add(Map.of("timestamp", minutesAgo(m), "value", value));
        }
        given().contentType("application/json")
                .body(Map.of("metricData", List.of(Map.of("namespace", "it_app", "compartmentId", compartment,
                        "name", name, "dimensions", Map.of("host", host), "datapoints", points))))
            .when().post("/20180401/metrics")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("failedMetricsCount", equalTo(0));
    }

    private static Map<String, Object> alarmBody(String query, String destination) {
        return Map.of("displayName", "it-alarm-" + System.nanoTime(), "compartmentId", compartment,
                "metricCompartmentId", compartment, "namespace", "it_app", "query", query, "severity", "CRITICAL",
                "destinations", List.of(destination), "isEnabled", true);
    }

    @Test
    void postSummarizeAndListMetrics() {
        post("Latency", "a", 120, 6, 5);
        post("Latency", "b", 40, 5);

        given().contentType("application/json")
                .body(Map.of("metricData", List.of(Map.of("namespace", "oci_reserved", "compartmentId", compartment,
                        "name", "x", "dimensions", Map.of("k", "v"),
                        "datapoints", List.of(Map.of("timestamp", minutesAgo(1), "value", 1))))))
            .when().post("/20180401/metrics")
            .then().statusCode(200)
                .body("failedMetricsCount", equalTo(1))
                .body("failedMetrics[0].message", containsString("oci_"))
                .body("failedMetrics[0].metricData.name", equalTo("x"));

        given().contentType("application/json").queryParam("compartmentId", compartment)
                .body(Map.of("namespace", "it_app", "query", "Latency[1m]{host = \"a\"}.mean()"))
            .when().post("/20180401/metrics/actions/summarizeMetricsData")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("Latency"))
                .body("[0].dimensions.host", equalTo("a"))
                .body("[0].resolution", equalTo("1m"))
                .body("[0].aggregatedDatapoints.size()", equalTo(2))
                .body("[0].aggregatedDatapoints[0].value", equalTo(120.0f));

        given().contentType("application/json").queryParam("compartmentId", compartment)
                .body(Map.of("namespace", "it_app", "query", "Latency[1m].mean( >"))
            .when().post("/20180401/metrics/actions/summarizeMetricsData")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        given().contentType("application/json").queryParam("compartmentId", compartment)
                .body(Map.of("namespace", "it_app", "name", "Latency"))
            .when().post("/20180401/metrics/actions/listMetrics")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .body("[0].namespace", equalTo("it_app"))
                .body("[0].compartmentId", equalTo(compartment));
    }

    @Test
    void alarmFiresAndNotifiesItsTopic() {
        String topicId = given().contentType("application/json")
                .body(Map.of("name", "it-alarms-" + System.nanoTime(), "compartmentId", compartment))
            .when().post("/20181201/topics").then().statusCode(200).extract().path("topicId");
        String subscriptionId = given().contentType("application/json")
                .body(Map.of("topicId", topicId, "compartmentId", compartment, "protocol", "EMAIL",
                        "endpoint", "oncall@example.com"))
            .when().post("/20181201/subscriptions").then().statusCode(200).extract().path("id");
        URI confirm = URI.create(given().queryParam("subscriptionId", subscriptionId)
                .when().get("/_floci-oci/notifications/messages")
                .then().extract().<String>path("messages[0].confirmationUrl"));
        given().urlEncodingEnabled(false).when().get(confirm.getRawPath() + "?" + confirm.getRawQuery())
                .then().statusCode(200);

        post("Cpu", "web-1", 97, 6, 5, 4, 3, 2);
        Map<String, Object> body = new HashMap<>(alarmBody("Cpu[1m].max() > 90", topicId));
        body.put("body", "Follow the runbook");
        String alarmId = given().contentType("application/json").body(body)
            .when().post("/20180401/alarms")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.alarm."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("pendingDuration", equalTo("PT1M"))
                .body("resolution", equalTo("1m"))
                .body("timeCreated", notNullValue())
                .extract().path("id");

        await().atMost(Duration.ofSeconds(15)).until(() -> given().queryParam("compartmentId", compartment)
                .when().get("/20180401/alarms/status")
                .then().statusCode(200)
                .extract().<String>path("find { it.id == '" + alarmId + "' }.status"), equalTo("FIRING"));

        String delivered = await().atMost(Duration.ofSeconds(10)).until(() -> given()
                .queryParam("subscriptionId", subscriptionId)
                .when().get("/_floci-oci/notifications/messages")
                .then().extract().<String>path("messages.find { it.messageType == 'Notification' }.body"),
                notNullValue());
        JsonPath message = new JsonPath(delivered);
        assertEquals("OK_TO_FIRING", message.getString("type"));
        assertEquals("Follow the runbook", message.getString("body"));
        assertEquals(alarmId, message.getString("alarmMetaData[0].id"));
        assertEquals("web-1", message.getString("alarmMetaData[0].dimensions[0].host"));

        given().when().get("/20180401/alarms/" + alarmId + "/history")
            .then().statusCode(200)
                .body("alarmId", equalTo(alarmId))
                .body("entries.size()", greaterThanOrEqualTo(1));
        given().contentType("application/json").body(Map.of("status", "FIRING"))
            .when().post("/20180401/alarms/" + alarmId + "/actions/retrieveDimensionStates")
            .then().statusCode(200)
                .body("items[0].dimensions.host", equalTo("web-1"))
                .body("items[0].status", equalTo("FIRING"));
    }

    @Test
    void alarmCrudAndSuppressions() {
        String streamId = given().contentType("application/json")
                .body(Map.of("name", "it-alarm-stream-" + System.nanoTime(), "partitions", 1, "compartmentId", compartment))
            .when().post("/20180418/streams").then().statusCode(200).extract().path("id");
        String alarmId = given().contentType("application/json").body(alarmBody("Mem[5m].mean() > 90", streamId))
            .when().post("/20180401/alarms").then().statusCode(200).extract().path("id");

        given().queryParam("compartmentId", compartment)
            .when().get("/20180401/alarms")
            .then().statusCode(200).body("find { it.id == '" + alarmId + "' }.severity", equalTo("CRITICAL"));

        String etag = given().when().get("/20180401/alarms/" + alarmId).then().extract().header("etag");
        String from = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String until = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        given().contentType("application/json").header("if-match", etag)
                .body(Map.of("severity", "WARNING", "suppression", Map.of("timeSuppressFrom", from,
                        "timeSuppressUntil", until, "description", "deploy")))
            .when().put("/20180401/alarms/" + alarmId)
            .then().statusCode(200)
                .body("severity", equalTo("WARNING"))
                .body("suppression.description", equalTo("deploy"));
        given().queryParam("compartmentId", compartment).queryParam("status", "SUSPENDED")
            .when().get("/20180401/alarms/status")
            .then().statusCode(200).body("id", hasItem(alarmId));
        given().when().post("/20180401/alarms/" + alarmId + "/actions/removeSuppression").then().statusCode(200);
        given().when().get("/20180401/alarms/" + alarmId).then().body("suppression", nullValue());

        String suppressionId = given().contentType("application/json")
                .body(Map.of("alarmSuppressionTarget", Map.of("targetType", "ALARM", "alarmId", alarmId),
                        "level", "ALARM", "displayName", "window", "timeSuppressFrom", from, "timeSuppressUntil", until))
            .when().post("/20180401/alarmSuppressions")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.alarmsuppression."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("alarmSuppressionTarget.targetType", equalTo("ALARM"))
                .body("compartmentId", equalTo(compartment))
                .extract().path("id");
        given().queryParam("alarmId", alarmId)
            .when().get("/20180401/alarmSuppressions")
            .then().statusCode(200).body("items.id", contains(suppressionId));
        given().contentType("application/json").body(Map.of())
            .when().post("/20180401/alarms/" + alarmId + "/actions/summarizeAlarmSuppressionHistory")
            .then().statusCode(200).body("items[0].suppressionId", equalTo(suppressionId));
        given().when().delete("/20180401/alarmSuppressions/" + suppressionId).then().statusCode(204);
        given().contentType("application/json")
                .body(Map.of("alarmSuppressionTarget", Map.of("targetType", "COMPARTMENT",
                                "compartmentId", "ocid1.compartment.oc1..doesnotexist"),
                        "level", "ALARM", "displayName", "nowhere", "timeSuppressFrom", from, "timeSuppressUntil", until))
            .when().post("/20180401/alarmSuppressions")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
        given().when().get("/20180401/alarmSuppressions/" + suppressionId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));

        given().when().delete("/20180401/alarms/" + alarmId).then().statusCode(204);
        given().when().get("/20180401/alarms/" + alarmId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
        given().queryParam("compartmentId", compartment)
            .when().get("/20180401/alarms")
            .then().statusCode(200).body("id", not(hasItem(alarmId)));
    }

    @Test
    void invalidAlarmsAnswerOciErrors() {
        given().contentType("application/json").body(alarmBody("Cpu[1m].max()", "ocid1.onstopic.oc1.iad.x"))
            .when().post("/20180401/alarms")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().contentType("application/json").body(alarmBody("Cpu[1m].max() > 1", "ocid1.onstopic.oc1.iad.missing"))
            .when().post("/20180401/alarms")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
        given().when().get("/20180401/alarms")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().when().get("/20180401/alarms/ocid1.alarm.oc1.iad.missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));

        String streamId = given().contentType("application/json")
                .body(Map.of("name", "it-move-stream-" + System.nanoTime(), "partitions", 1, "compartmentId", compartment))
            .when().post("/20180418/streams").then().statusCode(200).extract().path("id");
        String alarmId = given().contentType("application/json")
                .body(alarmBody("Cpu[1m].max() > 1", streamId))
            .when().post("/20180401/alarms").then().statusCode(200).extract().path("id");
        given().contentType("application/json").body(Map.of("compartmentId", "ocid1.compartment.oc1..doesnotexist"))
            .when().put("/20180401/alarms/" + alarmId)
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
        given().contentType("application/json")
                .body(Map.of("metricCompartmentId", "ocid1.compartment.oc1..doesnotexist"))
            .when().put("/20180401/alarms/" + alarmId)
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
        given().when().get("/20180401/alarms/" + alarmId)
            .then().body("compartmentId", equalTo(compartment)).body("metricCompartmentId", equalTo(compartment));
        given().when().delete("/20180401/alarms/" + alarmId).then().statusCode(204);

        Map<String, Object> unknownMetrics = new HashMap<>(alarmBody("Cpu[1m].max() > 1", streamId));
        unknownMetrics.put("metricCompartmentId", "ocid1.compartment.oc1..doesnotexist");
        given().contentType("application/json").body(unknownMetrics)
            .when().post("/20180401/alarms")
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: compartment "
                        + "ocid1.compartment.oc1..doesnotexist"));
    }

    @Test
    void metricsForAnUnknownCompartmentFail() {
        given().contentType("application/json")
                .body(Map.of("metricData", List.of(Map.of("namespace", "it_app",
                        "compartmentId", "ocid1.compartment.oc1..doesnotexist", "name", "Cpu",
                        "dimensions", Map.of("host", "a"),
                        "datapoints", List.of(Map.of("timestamp", minutesAgo(1), "value", 1))))))
            .when().post("/20180401/metrics")
            .then().statusCode(200)
                .body("failedMetricsCount", equalTo(1))
                .body("failedMetrics[0].message", containsString("compartment"));
    }
}
