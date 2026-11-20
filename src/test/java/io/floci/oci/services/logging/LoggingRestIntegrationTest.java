package io.floci.oci.services.logging;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class LoggingRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itlogging");
        }
    }

    private static String identifierOf(String workRequestId, String entityType) {
        return given().when().get("/20200531/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].entityType", equalTo(entityType))
                .extract().path("resources[0].identifier");
    }

    @Test
    void logGroupAndLogLifecycle() {
        String name = "it-group-" + System.nanoTime();
        String groupWr = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", name, "description", "it"))
            .when().post("/20200531/logGroups")
            .then().statusCode(202)
                .header("opc-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        String groupId = identifierOf(groupWr, "loggroup");

        given().when().get("/20200531/logGroups/" + groupId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("displayName", equalTo(name))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("timeCreated", notNullValue());
        given().queryParam("compartmentId", compartment).queryParam("displayName", name)
            .when().get("/20200531/logGroups")
            .then().statusCode(200).body("size()", equalTo(1)).body("[0].id", equalTo(groupId));
        given().contentType("application/json").body(Map.of("compartmentId", compartment, "displayName", name))
            .when().post("/20200531/logGroups")
            .then().statusCode(409).body("code", equalTo("Conflict"));

        String logWr = given().contentType("application/json").body(Map.of("displayName", "app", "logType", "CUSTOM"))
            .when().post("/20200531/logGroups/" + groupId + "/logs")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String logId = identifierOf(logWr, "log");
        given().when().get("/20200531/logGroups/" + groupId + "/logs/" + logId)
            .then().statusCode(200)
                .body("logGroupId", equalTo(groupId))
                .body("logType", equalTo("CUSTOM"))
                .body("retentionDuration", equalTo(30))
                .body("isEnabled", equalTo(true))
                .body("tenancyId", notNullValue())
                .body("compartmentId", equalTo(compartment));
        given().when().get("/20200531/logGroups/" + groupId + "/logs")
            .then().statusCode(200).body("[0].id", equalTo(logId)).body("[0].tenancyId", nullValue());

        given().contentType("application/json").body(Map.of("retentionDuration", 60))
            .when().put("/20200531/logGroups/" + groupId + "/logs/" + logId)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20200531/logGroups/" + groupId + "/logs/" + logId)
            .then().body("retentionDuration", equalTo(60));

        given().when().delete("/20200531/logGroups/" + groupId).then().statusCode(409);
        given().when().delete("/20200531/logGroups/" + groupId + "/logs/" + logId).then().statusCode(202);
        given().when().delete("/20200531/logGroups/" + groupId).then().statusCode(202);
        given().when().get("/20200531/logGroups/" + groupId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().queryParam("compartmentId", compartment)
            .when().get("/20200531/workRequests")
            .then().statusCode(200).body("id", hasItem(groupWr));
    }

    @Test
    void putLogsThenSearchLogs() {
        String groupName = "it-search-" + System.nanoTime();
        String groupId = identifierOf(given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", groupName))
                .when().post("/20200531/logGroups").then().statusCode(202).extract().header("opc-work-request-id"),
                "loggroup");
        String logId = identifierOf(given().contentType("application/json")
                .body(Map.of("displayName", "api", "logType", "CUSTOM"))
                .when().post("/20200531/logGroups/" + groupId + "/logs")
                .then().statusCode(202).extract().header("opc-work-request-id"), "log");

        String now = Instant.now().minusSeconds(5).toString();
        given().contentType("application/json")
                .body(Map.of("specversion", "1.0", "logEntryBatches", List.of(Map.of(
                        "source", "api-1", "type", "com.example.api", "defaultlogentrytime", now,
                        "entries", List.of(
                                Map.of("data", "{\"level\":\"ERROR\",\"path\":\"/orders\"}", "id", "1"),
                                Map.of("data", "{\"level\":\"INFO\",\"path\":\"/health\"}", "id", "2"))))))
            .when().post("/20200831/logs/" + logId + "/actions/push")
            .then().statusCode(200).header("opc-request-id", notNullValue());

        String start = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String end = Instant.now().plus(1, ChronoUnit.MINUTES).toString();
        given().contentType("application/json")
                .body(Map.of("timeStart", start, "timeEnd", end, "isReturnFieldInfo", true,
                        "searchQuery", "search \"" + compartment + "/" + groupName + "/api\" | where data.level = 'ERROR'"))
            .when().post("/20190909/search")
            .then().statusCode(200)
                .body("summary.resultCount", equalTo(1))
                .body("results[0].data.logContent.data.path", equalTo("/orders"))
                .body("results[0].data.logContent.oracle.logid", equalTo(logId))
                .body("results[0].data.logContent.source", equalTo("api-1"))
                .body("results[0].data.datetime", notNullValue())
                .body("fields.fieldName", hasItem("logContent.data.level"));

        given().contentType("application/json").queryParam("limit", 1)
                .body(Map.of("timeStart", start, "timeEnd", end, "searchQuery", "search \"" + compartment + "\""))
            .when().post("/20190909/search")
            .then().statusCode(200)
                .header("opc-next-page", notNullValue())
                .body("results.size()", equalTo(1));

        given().contentType("application/json")
                .body(Map.of("timeStart", start, "timeEnd", end, "searchQuery", "search"))
            .when().post("/20190909/search")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().contentType("application/json")
                .body(Map.of("specversion", "1.0", "logEntryBatches", List.of()))
            .when().post("/20200831/logs/ocid1.log.oc1.iad.missing/actions/push")
            .then().statusCode(404);
    }

    @Test
    void savedSearchCrud() {
        String id = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "name", "errors",
                        "query", "search \"" + compartment + "\" | where data.level = 'ERROR'"))
            .when().post("/20200531/logSavedSearches")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.logsavedsearch."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .extract().path("id");
        given().queryParam("compartmentId", compartment)
            .when().get("/20200531/logSavedSearches")
            .then().statusCode(200).body("items.id", hasItem(id));
        given().contentType("application/json").body(Map.of("description", "all errors"))
            .when().put("/20200531/logSavedSearches/" + id)
            .then().statusCode(200).body("description", equalTo("all errors"));
        given().when().delete("/20200531/logSavedSearches/" + id).then().statusCode(204);
        given().when().get("/20200531/logSavedSearches/" + id).then().statusCode(404);
    }
}
