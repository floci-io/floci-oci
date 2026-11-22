package io.floci.oci.services.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EventsRestIntegrationTest {

    private static String compartment;
    private static String topicId;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itevents");
            topicId = given().contentType("application/json")
                    .body(Map.of("name", "it-events-" + System.nanoTime(), "compartmentId", compartment))
                .when().post("/20181201/topics")
                .then().statusCode(200).extract().path("topicId");
        }
    }

    private static Map<String, Object> onsRule(String compartmentId, String condition) {
        return Map.of("compartmentId", compartmentId, "displayName", "it-rule", "condition", condition,
                "isEnabled", true, "actions", Map.of("actions", List.of(Map.of(
                        "actionType", "ONS", "isEnabled", true, "topicId", topicId))));
    }

    @Test
    void ruleCrudRoundtrip() {
        String condition = "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\"}";
        String ruleId = given().contentType("application/json").body(onsRule(compartment, condition))
            .when().post("/20181201/rules")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .body("id", startsWith("ocid1.eventrule."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("lifecycleMessage", equalTo(""))
                .body("condition", equalTo(condition))
                .body("actions.actions[0].actionType", equalTo("ONS"))
                .body("actions.actions[0].lifecycleState", equalTo("ACTIVE"))
                .body("actions.actions[0].topicId", equalTo(topicId))
                .extract().path("id");

        // ListRules is a bare array of RuleSummary, which has no actions.
        given().queryParam("compartmentId", compartment)
            .when().get("/20181201/rules")
            .then().statusCode(200)
                .body("find { it.id == '" + ruleId + "' }.condition", equalTo(condition))
                .body("find { it.id == '" + ruleId + "' }.actions", nullValue());

        given().contentType("application/json").body(Map.of("isEnabled", false))
            .when().put("/20181201/rules/" + ruleId)
            .then().statusCode(200).body("lifecycleState", equalTo("INACTIVE"));

        given().when().delete("/20181201/rules/" + ruleId).then().statusCode(204);
        given().when().get("/20181201/rules/" + ruleId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void invalidConditionIsInvalidParameter() {
        given().contentType("application/json").body(onsRule(compartment, "{nope"))
            .when().post("/20181201/rules")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void unknownStreamIsARelatedResourceError() {
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "r", "condition", "{}", "isEnabled", true,
                        "actions", Map.of("actions", List.of(Map.of("actionType", "OSS", "isEnabled", true,
                                "streamId", "ocid1.stream.oc1.iad.missing")))))
            .when().post("/20181201/rules")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
    }

    @Test
    void objectUploadIsDeliveredToAStream() throws Exception {
        String namespace = given().when().get("/n").then().statusCode(200).extract().asString().replace("\"", "");
        String streamId = given().contentType("application/json")
                .body(Map.of("name", "it-events-" + System.nanoTime(), "partitions", 1, "compartmentId", compartment))
            .when().post("/20180418/streams")
            .then().statusCode(200).extract().path("id");
        String bucket = "it-events-" + System.nanoTime();
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "name", bucket, "objectEventsEnabled", true))
            .when().post("/n/" + namespace + "/b")
            .then().statusCode(200).body("objectEventsEnabled", equalTo(true));

        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "to-stream", "isEnabled", true,
                        "condition", "{\"eventType\":\"com.oraclecloud.objectstorage.createobject\","
                                + "\"data\":{\"additionalDetails\":{\"bucketName\":\"" + bucket + "\"}}}",
                        "actions", Map.of("actions", List.of(Map.of(
                                "actionType", "OSS", "isEnabled", true, "streamId", streamId)))))
            .when().post("/20181201/rules")
            .then().statusCode(200);

        given().contentType("application/octet-stream").body("hello".getBytes(StandardCharsets.UTF_8))
            .when().put("/n/" + namespace + "/b/" + bucket + "/o/a.txt")
            .then().statusCode(200);

        String cursor = given().contentType("application/json")
                .body(Map.of("partition", "0", "type", "TRIM_HORIZON"))
            .when().post("/20180418/streams/" + streamId + "/cursors")
            .then().statusCode(200).extract().path("value");

        String value = await().atMost(Duration.ofSeconds(10)).until(
                () -> given().queryParam("cursor", cursor)
                        .when().get("/20180418/streams/" + streamId + "/messages")
                        .then().statusCode(200).extract().<String>path("[0].value"),
                notNullValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = new ObjectMapper().readValue(Base64.getDecoder().decode(value), Map.class);
        assertEquals("com.oraclecloud.objectstorage.createobject", envelope.get("eventType"));
        assertEquals("objectstorage", envelope.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertEquals("a.txt", data.get("resourceName"));
        assertEquals(compartment, data.get("compartmentId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) data.get("additionalDetails");
        assertEquals(bucket, details.get("bucketName"));
    }

    @Test
    void unknownTopicIsARelatedResourceError() {
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "r", "condition", "{}", "isEnabled", true,
                        "actions", Map.of("actions", List.of(Map.of("actionType", "ONS", "isEnabled", true,
                                "topicId", "ocid1.onstopic.oc1.iad.missing")))))
            .when().post("/20181201/rules")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
    }

    @Test
    void listWithoutCompartmentIsMissingParameter() {
        given().when().get("/20181201/rules")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }
}
