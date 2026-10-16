package io.floci.oci.core.common;

import io.floci.oci.testing.TestRegions;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

/** Regional resources are scoped to the region in the Host header; Identity is global. */
@QuarkusTest
class RegionIsolationIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String PHOENIX_QUEUE = "queue.us-phoenix-1.oci.oraclecloud.com";
    private static final String PHOENIX_IDENTITY = "identity.us-phoenix-1.oci.oraclecloud.com";

    @BeforeEach
    void subscribePhoenix() {
        TestRegions.subscribe(TENANCY, "PHX");
    }

    @Test
    void queueCreatedInPhoenixIsInvisibleFromTheDefaultRegion() {
        String workRequestId = given()
                .header("Host", PHOENIX_QUEUE)
                .contentType("application/json")
                .body(Map.of("displayName", "region-q-" + System.nanoTime(),
                        "compartmentId", TENANCY))
            .when().post("/20210201/queues")
            .then().statusCode(202)
                .extract().header("opc-work-request-id");

        String queueId = given().header("Host", PHOENIX_QUEUE)
            .when().get("/20210201/workRequests/" + workRequestId)
            .then().statusCode(200)
                .extract().path("resources[0].identifier");

        given().header("Host", PHOENIX_QUEUE)
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(200)
                .body("id", startsWith("ocid1.queue.oc1.phx."))
                .body("messagesEndpoint", equalTo("http://" + PHOENIX_QUEUE));

        given()
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));

        given().queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(200)
                .body("items.find { it.id == '" + queueId + "' }", equalTo(null));
    }

    @Test
    void identityIsGlobalAcrossRegions() {
        String compartmentId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", "region-c-" + System.nanoTime(),
                        "description", "global"))
            .when().post("/20160918/compartments")
            .then().statusCode(200)
                .extract().path("id");

        given().header("Host", PHOENIX_IDENTITY)
            .when().get("/20160918/compartments/" + compartmentId)
            .then().statusCode(200).body("id", equalTo(compartmentId));

        String workRequestId = given()
            .when().delete("/20160918/compartments/" + compartmentId)
            .then().statusCode(202)
                .extract().header("opc-work-request-id");

        given().header("Host", PHOENIX_IDENTITY)
            .when().get("/20160918/workRequests/" + workRequestId)
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));
    }

    @Test
    void compartmentHoldingAQueueInAnotherRegionCannotBeDeleted() {
        String compartmentId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", "region-del-" + System.nanoTime(),
                        "description", "holds a Phoenix queue"))
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().path("id");
        String queueWorkRequest = given().header("Host", PHOENIX_QUEUE)
                .contentType("application/json")
                .body(Map.of("displayName", "region-del-q", "compartmentId", compartmentId))
            .when().post("/20210201/queues")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String queueId = given().header("Host", PHOENIX_QUEUE)
            .when().get("/20210201/workRequests/" + queueWorkRequest)
            .then().statusCode(200).extract().path("resources[0].identifier");

        String failed = given()
            .when().delete("/20160918/compartments/" + compartmentId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given()
            .when().get("/20160918/workRequests/" + failed)
            .then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("errors[0].code", equalTo("Conflict"))
                .body("errors[0].message", equalTo("The compartment must be empty before you can delete it."
                        + " It still holds: queue " + queueId + "."));
        given()
            .when().get("/20160918/compartments/" + compartmentId)
            .then().statusCode(200).body("lifecycleState", equalTo("ACTIVE"));

        given().header("Host", PHOENIX_QUEUE)
            .when().delete("/20210201/queues/" + queueId)
            .then().statusCode(202);
        String succeeded = given()
            .when().delete("/20160918/compartments/" + compartmentId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given()
            .when().get("/20160918/workRequests/" + succeeded)
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));
        given()
            .when().get("/20160918/compartments/" + compartmentId)
            .then().statusCode(200)
                .body("lifecycleState", equalTo("DELETED"))
                .body("name", matchesPattern("region-del-\\d+\\.[A-Za-z0-9]{8}"));
    }

    @Test
    void availabilityDomainsFollowTheRequestRegion() {
        given().header("Host", PHOENIX_IDENTITY).queryParam("compartmentId", TENANCY)
            .when().get("/20160918/availabilityDomains")
            .then().statusCode(200)
                .body("[0].name", equalTo("Floc:PHX-AD-1"));

        given().header("Host", PHOENIX_IDENTITY)
            .when().get("/20160918/tenancies/" + TENANCY + "/regionSubscriptions")
            .then().statusCode(200)
                .body("find { it.isHomeRegion }.regionName", equalTo("us-ashburn-1"));
    }
}
