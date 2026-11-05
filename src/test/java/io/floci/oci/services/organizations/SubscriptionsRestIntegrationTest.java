package io.floci.oci.services.organizations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SubscriptionsRestIntegrationTest {

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..subuser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    private static String createChild(String parent) {
        String workRequestId = given().header("Authorization", signedAs(parent)).contentType("application/json")
                .body(Map.of("compartmentId", parent, "tenancyName", "sub-child-" + System.nanoTime(),
                        "homeRegion", "us-ashburn-1", "adminEmail", "admin@example.com"))
            .when().post("/20230401/childTenancies")
            .then().statusCode(202).extract().header("opc-work-request-id");
        return given().header("Authorization", signedAs(parent))
            .when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200).extract().path("resources[0].identifier");
    }

    @Test
    void theOrganizationDefaultIsTheParentsOwnSubscription() {
        String parent = "ocid1.tenancy.oc1..subrest" + System.nanoTime();
        String child = createChild(parent);

        String subscriptionId = given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptions")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("items.size()", equalTo(1))
                .body("items[0].entityVersion", equalTo("V2"))
                .body("items[0].compartmentId", equalTo(parent))
                .extract().path("items[0].id");
        given().header("Authorization", signedAs(parent)).queryParam("compartmentId", parent)
            .when().get("/20230401/organizations")
            .then().statusCode(200).body("items[0].defaultUcmSubscriptionId", equalTo(subscriptionId));

        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20230401/assignedSubscriptions")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(subscriptionId))
                .body("items[0].compartmentId", equalTo(child));
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptions/" + subscriptionId + "/availableRegions")
            .then().statusCode(200).body("items.regionName", hasItem("us-ashburn-1"));
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptions/" + subscriptionId)
            .then().statusCode(200).header("etag", notNullValue()).body("id", equalTo(subscriptionId));
    }

    @Test
    void mapAndUnmapASubscription() {
        String parent = "ocid1.tenancy.oc1..submap" + System.nanoTime();
        String child = createChild(parent);
        String subscriptionId = given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptions")
            .then().statusCode(200).extract().path("items[0].id");

        String mappingId = given().header("Authorization", signedAs(parent)).contentType("application/json")
                .body(Map.of("compartmentId", child, "subscriptionId", subscriptionId))
            .when().post("/20230401/subscriptionMappings")
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("isExplicitlyAssigned", equalTo(true))
                .body("lifecycleState", equalTo("ACTIVE"))
                .extract().path("id");
        given().header("Authorization", signedAs(parent)).queryParam("subscriptionId", subscriptionId)
            .when().get("/20230401/subscriptionMappings")
            .then().statusCode(200).body("items[0].id", equalTo(mappingId));

        given().header("Authorization", signedAs(parent)).header("if-match", "stale")
            .when().delete("/20230401/subscriptionMappings/" + mappingId)
            .then().statusCode(412);
        given().header("Authorization", signedAs(parent)).contentType("application/json")
                .body(Map.of("compartmentId", child, "subscriptionId", subscriptionId))
            .when().post("/20230401/subscriptionMappings")
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().header("Authorization", signedAs(parent))
            .when().delete("/20230401/subscriptionMappings/" + mappingId)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptionMappings/" + mappingId)
            .then().statusCode(200)
                .body("lifecycleState", equalTo("DELETED"))
                .body("timeTerminated", notNullValue());
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/subscriptionMappings")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().header("Authorization", signedAs(child))
            .when().get("/20230401/subscriptions/" + subscriptionId)
            .then()
                .statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
