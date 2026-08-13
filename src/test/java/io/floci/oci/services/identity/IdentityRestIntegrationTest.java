package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class IdentityRestIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    @Test
    void compartmentCrudRoundtrip() {
        String id = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY,
                        "name", "it-compartment-" + System.nanoTime(),
                        "description", "integration test"))
            .when()
                .post("/20160918/compartments")
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .body("id", startsWith("ocid1.compartment.oc1.."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .extract().path("id");

        given()
            .when().get("/20160918/compartments/" + id)
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(id));

        // Lists are bare JSON arrays.
        given()
            .queryParam("compartmentId", TENANCY)
            .when().get("/20160918/compartments")
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].id", startsWith("ocid1.compartment"));

        // Async delete: 202 + opc-work-request-id, pollable to SUCCEEDED.
        String workRequestId = given()
            .when().delete("/20160918/compartments/" + id)
            .then()
                .statusCode(202)
                .header("opc-work-request-id", startsWith("ocid1.coreservicesworkrequest"))
                .extract().header("opc-work-request-id");

        given()
            .when().get("/20160918/workRequests/" + workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("operationType", equalTo("DELETE_COMPARTMENT"))
                .body("resources[0].identifier", equalTo(id));

        given()
            .when().get("/20160918/compartments/" + id)
            .then()
                .statusCode(200)
                .body("lifecycleState", equalTo("DELETED"));
    }

    @Test
    void userGroupMembershipRoundtrip() {
        String suffix = String.valueOf(System.nanoTime());
        String userId = given()
                .contentType("application/json")
                .body(Map.of("name", "it-user-" + suffix, "description", "u",
                        "email", "u@example.com"))
            .when().post("/20160918/users")
            .then().statusCode(200)
                .body("isMfaActivated", equalTo(false))
                .body("compartmentId", equalTo(TENANCY))
                .extract().path("id");

        String groupId = given()
                .contentType("application/json")
                .body(Map.of("name", "it-group-" + suffix, "description", "g"))
            .when().post("/20160918/groups")
            .then().statusCode(200).extract().path("id");

        String membershipId = given()
                .contentType("application/json")
                .body(Map.of("userId", userId, "groupId", groupId))
            .when().post("/20160918/userGroupMemberships")
            .then().statusCode(200)
                .body("userId", equalTo(userId))
                .body("groupId", equalTo(groupId))
                .extract().path("id");

        given()
            .queryParam("compartmentId", TENANCY)
            .queryParam("userId", userId)
            .when().get("/20160918/userGroupMemberships")
            .then().statusCode(200).body("size()", equalTo(1));

        given().when().delete("/20160918/userGroupMemberships/" + membershipId)
            .then().statusCode(204);
        given().when().delete("/20160918/users/" + userId).then().statusCode(204);
        given().when().delete("/20160918/groups/" + groupId).then().statusCode(204);
    }

    @Test
    void policyValidationAndRoundtrip() {
        given()
            .contentType("application/json")
            .body(Map.of("compartmentId", TENANCY, "name", "it-policy-bad", "description", "d"))
            .when().post("/20160918/policies")
            .then()
                .statusCode(400)
                .body("code", equalTo("InvalidParameter"));

        String id = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY,
                        "name", "it-policy-" + System.nanoTime(),
                        "description", "d",
                        "statements", java.util.List.of(
                                "Allow group admins to manage all-resources in tenancy")))
            .when().post("/20160918/policies")
            .then().statusCode(200)
                .body("statements", hasSize(1))
                .extract().path("id");

        given().when().delete("/20160918/policies/" + id).then().statusCode(204);
    }

    @Test
    void staleIfMatchIs412WithOciErrorBody() {
        String id = given()
                .contentType("application/json")
                .body(Map.of("name", "it-user-etag-" + System.nanoTime(), "description", "u"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");

        given()
            .contentType("application/json")
            .header("if-match", "stale-etag")
            .body(Map.of("description", "updated"))
            .when().put("/20160918/users/" + id)
            .then()
                .statusCode(412)
                .body("code", equalTo("NoEtagMatch"))
                .body("message", notNullValue());

        given().when().delete("/20160918/users/" + id).then().statusCode(204);
    }

    @Test
    void missingResourceIsNotAuthorizedOrNotFound() {
        given()
            .when().get("/20160918/users/ocid1.user.oc1..doesnotexist")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void referenceDataEndpoints() {
        given().queryParam("compartmentId", TENANCY)
            .when().get("/20160918/availabilityDomains")
            .then().statusCode(200).body("size()", equalTo(3))
                .body("[0].name", notNullValue());

        given().when().get("/20160918/regions")
            .then().statusCode(200)
                .body("[0].key", equalTo("IAD"))
                .body("[0].name", equalTo("us-ashburn-1"));

        given().when().get("/20160918/tenancies/" + TENANCY)
            .then().statusCode(200).body("id", equalTo(TENANCY));

        given().when().get("/20160918/tenancies/" + TENANCY + "/regionSubscriptions")
            .then().statusCode(200)
                .body("[0].status", equalTo("READY"))
                .body("[0].isHomeRegion", equalTo(true));
    }

    @Test
    void listPaginationUsesOpcNextPageHeader() {
        String marker = "it-page-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            given()
                .contentType("application/json")
                .body(Map.of("name", marker + "-" + i, "description", "g"))
                .when().post("/20160918/groups")
                .then().statusCode(200);
        }

        String nextPage = given()
                .queryParam("compartmentId", TENANCY)
                .queryParam("limit", 2)
            .when().get("/20160918/groups")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .header("opc-next-page", notNullValue())
                .extract().header("opc-next-page");

        given()
            .queryParam("compartmentId", TENANCY)
            .queryParam("limit", 2)
            .queryParam("page", nextPage)
            .when().get("/20160918/groups")
            .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }
}
