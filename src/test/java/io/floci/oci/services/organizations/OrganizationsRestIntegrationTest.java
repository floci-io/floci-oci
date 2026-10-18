package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OrganizationsRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..orguser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    private String createChild(String name, String homeRegion) {
        String workRequestId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId(), "tenancyName", name,
                        "homeRegion", homeRegion, "adminEmail", "admin@example.com",
                        "features", List.of("SUBSCRIPTION_SHARING")))
            .when().post("/20230401/childTenancies")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");

        return given()
            .when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("CREATE_CHILD_TENANCY"))
                .body("status", equalTo("SUCCEEDED"))
                .extract().path("resources[0].identifier");
    }

    @Test
    void createChildTenancyFormsAnOrganization() {
        String child = createChild("rest-child-" + System.nanoTime(), "us-ashburn-1");

        String organizationId = given().queryParam("compartmentId", config.defaultTenancyId())
            .when().get("/20230401/organizations")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("items.size()", equalTo(1))
                .body("items[0].compartmentId", equalTo(config.defaultTenancyId()))
                .body("items[0].lifecycleState", equalTo("ACTIVE"))
                .extract().path("items[0].id");

        given().when().get("/20230401/organizations/" + organizationId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.organization.oc1.."));

        given().when().get("/20230401/organizations/" + organizationId + "/tenancies/" + child)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("tenancyId", equalTo(child))
                .body("role", equalTo("CHILD"))
                .body("governanceStatus", equalTo("OPTED_IN"));

        given().when().get("/20230401/organizations/" + organizationId + "/tenancies")
            .then().statusCode(200)
                .body("items.role", hasItems("PARENT", "CHILD"));

        given().queryParam("childTenancyId", child)
            .when().get("/20230401/links")
            .then().statusCode(200)
                .body("items.feature", hasItems("CORE", "SUBSCRIPTION_SHARING"));
    }

    @Test
    void workRequestsAreListedInTheRequestedOrder() {
        createChild("order-a-" + System.nanoTime(), "us-ashburn-1");
        createChild("order-b-" + System.nanoTime(), "us-ashburn-1");
        String tenancy = config.defaultTenancyId();

        List<String> ascending = given().queryParam("compartmentId", tenancy).queryParam("sortOrder", "ASC")
            .when().get("/20230401/workRequests")
            .then().statusCode(200).extract().path("items.id");
        List<String> descending = given().queryParam("compartmentId", tenancy).queryParam("sortOrder", "DESC")
            .when().get("/20230401/workRequests")
            .then().statusCode(200).extract().path("items.id");

        assertEquals(ascending.reversed(), descending);
        given().queryParam("compartmentId", tenancy).queryParam("sortOrder", "SIDEWAYS")
            .when().get("/20230401/workRequests")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().queryParam("limit", 1).queryParam("sortOrder", "DESC")
            .when().get("/20230401/workRequests/" + ascending.getFirst() + "/errors")
            .then().statusCode(200).body("items.size()", equalTo(0));
    }

    @Test
    void listsArePaged() {
        createChild("paged-a-" + System.nanoTime(), "us-ashburn-1");

        given().queryParam("limit", 1)
            .when().get("/20230401/links")
            .then().statusCode(200)
                .header("opc-next-page", notNullValue())
                .body("items.size()", equalTo(1));
    }

    @Test
    void aChildTenancyIsIsolatedAndSeesItsOwnHomeRegion() {
        String child = createChild("isolated-" + System.nanoTime(), "us-ashburn-1");

        given().header("Authorization", signedAs(child))
            .when().get("/20160918/tenancies/" + child)
            .then().statusCode(200)
                .body("id", equalTo(child))
                .body("homeRegionKey", equalTo("IAD"));

        String queueWorkRequest = given()
                .contentType("application/json")
                .body(Map.of("displayName", "parent-q-" + System.nanoTime(),
                        "compartmentId", config.defaultTenancyId()))
            .when().post("/20210201/queues")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String queueId = given().when().get("/20210201/workRequests/" + queueWorkRequest)
            .then().statusCode(200).extract().path("resources[0].identifier");

        given().header("Authorization", signedAs(child))
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));

        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20230401/organizations")
            .then().statusCode(200)
                .body("items[0].compartmentId", equalTo(config.defaultTenancyId()));
    }

    @Test
    void anUnsubscribedHomeRegionIs400() {
        given()
                .contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId(), "tenancyName", "nowhere",
                        "homeRegion", "ap-sydney-1", "adminEmail", "admin@example.com"))
            .when().post("/20230401/childTenancies")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void aForeignParentIs404() {
        given()
                .contentType("application/json")
                .body(Map.of("compartmentId", "ocid1.tenancy.oc1..someoneelse", "tenancyName", "x",
                        "homeRegion", "us-ashburn-1", "adminEmail", "admin@example.com"))
            .when().post("/20230401/childTenancies")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void listOrganizationsRequiresACompartment() {
        given().when().get("/20230401/organizations")
            .then()
                .statusCode(400)
                .body("code", equalTo("MissingParameter"));
    }
}
