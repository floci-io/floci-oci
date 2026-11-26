package io.floci.oci.services.mysql;

import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MysqlRestIntegrationTest {

    private static String compartment;
    private static TestNetworks.Network network;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itmysql");
            network = TestNetworks.create(compartment);
        }
    }

    private static Map<String, Object> createDetails(String displayName) {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("displayName", displayName);
        details.put("shapeName", "MySQL.2");
        details.put("subnetId", network.subnetId());
        details.put("adminUsername", "adminUser");
        details.put("adminPassword", "BEstrO0ng_#11");
        details.put("availabilityDomain", "Floc:US-ASHBURN-AD-1");
        details.put("maintenance", Map.of("windowStartTime", "sun 01:00"));
        return details;
    }

    private static Response create(String displayName) {
        return given().contentType("application/json").body(createDetails(displayName))
            .when().post("/20190415/dbSystems")
            .then().statusCode(200).extract().response();
    }

    @Test
    void createReturnsTheDbSystemWithItsEtagLocationAndWorkRequest() {
        given().contentType("application/json").body(createDetails("it-create"))
            .when().post("/20190415/dbSystems")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("location", startsWith("/20190415/dbSystems/ocid1.mysqldbsystem."))
                .header("opc-work-request-id", notNullValue())
                .body("id", startsWith("ocid1.mysqldbsystem.oc1.iad."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("shapeName", equalTo("MySQL.2"))
                .body("mysqlVersion", equalTo("8.4.4"))
                .body("subnetId", equalTo(network.subnetId()))
                .body("maintenance.windowStartTime", equalTo("sun 01:00"))
                .body("deletionPolicy.isDeleteProtected", equalTo(false))
                .body("dataStorage.dataStorageSizeInGBs", equalTo(50))
                .body("source.sourceType", equalTo("NONE"))
                .body("endpoints[0].port", notNullValue())
                .body("$", not(hasKey("adminPassword")))
                .body("$", not(hasKey("adminUsername")));
    }

    @Test
    void getAndListServeTheSystemAndListsAreBareArrays() {
        String id = create("it-get").path("id");

        given().when().get("/20190415/dbSystems/" + id)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(id))
                .body("displayName", equalTo("it-get"));

        given().queryParam("compartmentId", compartment).queryParam("displayName", "it-get")
            .when().get("/20190415/dbSystems")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(id))
                .body("[0]", not(hasKey("subnetId")));
    }

    @Test
    void anUnknownDbSystemIsNotAuthorizedOrNotFound() {
        given().when().get("/20190415/dbSystems/ocid1.mysqldbsystem.oc1.iad.missing")
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: DB system "
                        + "ocid1.mysqldbsystem.oc1.iad.missing"));
    }

    @Test
    void anUnknownSubnetIsRejected() {
        Map<String, Object> details = createDetails("it-bad-subnet");
        details.put("subnetId", "ocid1.subnet.oc1.iad.missing");
        given().contentType("application/json").body(details)
            .when().post("/20190415/dbSystems")
            .then().statusCode(400)
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: subnet "
                        + "ocid1.subnet.oc1.iad.missing"));
    }

    @Test
    void stopStartRestartAndDeleteAnswer202WithAWorkRequest() {
        String id = create("it-actions").path("id");

        given().contentType("application/json").body(Map.of("shutdownType", "FAST"))
            .when().post("/20190415/dbSystems/" + id + "/actions/stop")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20190415/dbSystems/" + id)
            .then().body("lifecycleState", equalTo("INACTIVE"));

        // StartDbSystem has no body, and clients send it without a content type.
        given().when().post("/20190415/dbSystems/" + id + "/actions/start")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());

        given().contentType("application/json").body(Map.of())
            .when().post("/20190415/dbSystems/" + id + "/actions/restart")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));

        String workRequestId = given().when().delete("/20190415/dbSystems/" + id)
            .then().statusCode(202).extract().header("opc-work-request-id");

        given().when().get("/20190415/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("DELETE_DBSYSTEM"))
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].identifier", equalTo(id))
                .body("resources[0].actionType", equalTo("DELETED"))
                .body("$", not(hasKey("errors")));
        given().when().get("/20190415/dbSystems/" + id)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
    }

    @Test
    void updateHonoursIfMatch() {
        Response created = create("it-update");
        String id = created.path("id");

        given().contentType("application/json").header("if-match", "\"stale\"").body(Map.of("displayName", "x"))
            .when().put("/20190415/dbSystems/" + id)
            .then().statusCode(412);

        given().contentType("application/json").header("if-match", created.header("etag"))
                .body(Map.of("displayName", "it-updated", "description", "renamed"))
            .when().put("/20190415/dbSystems/" + id)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20190415/dbSystems/" + id)
            .then().body("displayName", equalTo("it-updated")).body("description", equalTo("renamed"));
    }

    @Test
    void workRequestsAreListedAsBareArraysAndCannotBeCanceled() {
        String workRequestId = create("it-work-requests").header("opc-work-request-id");

        given().queryParam("compartmentId", compartment)
            .when().get("/20190415/workRequests")
            .then().statusCode(200)
                .body("find { it.id == '" + workRequestId + "' }.operationType", equalTo("CREATE_DBSYSTEM"))
                .body("find { it.id == '" + workRequestId + "' }", not(hasKey("resources")));
        given().when().get("/20190415/workRequests/" + workRequestId + "/errors")
            .then().statusCode(200).body("size()", equalTo(0));
        given().when().get("/20190415/workRequests/" + workRequestId + "/logs")
            .then().statusCode(200).body("size()", equalTo(0));
        given().when().delete("/20190415/workRequests/" + workRequestId)
            .then().statusCode(409).body("code", equalTo("IncorrectState"));
    }

    @Test
    void workRequestsSortByTheSdkFields() {
        String id = create("it-work-request-order").path("id");
        given().contentType("application/json").body(Map.of("description", "sorted"))
            .when().put("/20190415/dbSystems/" + id)
            .then().statusCode(202);

        Map<String, String> fields = Map.of("ID", "id", "OPERATION_TYPE", "operationType", "STATUS", "status",
                "TIME_ACCEPTED", "timeAccepted", "TIME_STARTED", "timeStarted", "TIME_FINISHED", "timeFinished");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            for (String sortOrder : List.of("ASC", "DESC")) {
                List<String> values = given().queryParam("compartmentId", compartment)
                        .queryParam("sortBy", field.getKey()).queryParam("sortOrder", sortOrder)
                    .when().get("/20190415/workRequests")
                    .then().statusCode(200).header("opc-request-id", notNullValue())
                    .extract().jsonPath().getList(field.getValue(), String.class);
                assertSorted(values, field.getKey().startsWith("TIME_"), "DESC".equals(sortOrder),
                        field.getKey() + " " + sortOrder);
            }
        }
    }

    @Test
    void workRequestsRejectACamelCaseSortBy() {
        given().queryParam("compartmentId", compartment).queryParam("sortBy", "timeAccepted")
            .when().get("/20190415/workRequests")
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", equalTo("Invalid sortBy: timeAccepted"));
    }

    /** Values present are in order and the missing ones (not started, not finished) come last. */
    private static void assertSorted(List<String> values, boolean times, boolean descending, String label) {
        assertFalse(values.isEmpty(), label);
        int present = 0;
        while (present < values.size() && values.get(present) != null) {
            present++;
        }
        for (int i = present; i < values.size(); i++) {
            assertNull(values.get(i), label + ": a value after a missing one");
        }
        for (int i = 1; i < present; i++) {
            int compared = times
                    ? Instant.parse(values.get(i - 1)).compareTo(Instant.parse(values.get(i)))
                    : values.get(i - 1).compareTo(values.get(i));
            assertTrue(descending ? compared >= 0 : compared <= 0, label + ": " + values);
        }
    }

    @Test
    void shapesAndVersionsAreBareArrays() {
        given().queryParam("compartmentId", compartment)
            .when().get("/20190415/shapes")
            .then().statusCode(200)
                .body("find { it.name == 'MySQL.4' }.cpuCoreCount", equalTo(4))
                .body("find { it.name == 'MySQL.4' }.memorySizeInGBs", equalTo(32))
                .body("find { it.name == 'MySQL.4' }.isSupportedFor", hasItems("DBSYSTEM", "HEATWAVECLUSTER"));
        given().queryParam("compartmentId", compartment).queryParam("isSupportedFor", "HEATWAVECLUSTER")
            .when().get("/20190415/shapes")
            .then().statusCode(200)
                .body("name", not(hasItem("MySQL.VM.Standard.E3.1.8GB")));
        given().queryParam("compartmentId", compartment)
            .when().get("/20190415/versions")
            .then().statusCode(200)
                .body("versions.flatten().version", hasItems("8.0.41", "8.4.4", "9.2.0"));
    }

    @Test
    void listsNeedAKnownCompartment() {
        given().when().get("/20190415/dbSystems")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().queryParam("compartmentId", "ocid1.compartment.oc1..missing")
            .when().get("/20190415/dbSystems")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
