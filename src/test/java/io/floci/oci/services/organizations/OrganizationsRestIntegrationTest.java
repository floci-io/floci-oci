package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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

    @Test
    void deleteAndRestoreOrganizationTenancy() {
        String child = createChild("rest-terminate-" + System.nanoTime(), "us-ashburn-1");
        String organizationId = given().queryParam("compartmentId", config.defaultTenancyId())
            .when().get("/20230401/organizations")
            .then().statusCode(200).extract().path("items[0].id");

        String terminateId = given()
            .when().delete("/20230401/organizationTenancies/" + child)
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        given().when().get("/20230401/workRequests/" + terminateId)
            .then().statusCode(200)
                .body("operationType", equalTo("TERMINATE_ORGANIZATION_TENANCY"))
                .body("status", equalTo("SUCCEEDED"));
        given().when().get("/20230401/organizations/" + organizationId + "/tenancies/" + child)
            .then().statusCode(200)
                .body("lifecycleState", equalTo("DELETED"))
                .body("timeLeft", notNullValue());
        given().queryParam("childTenancyId", child).queryParam("lifecycleState", "ACTIVE")
            .when().get("/20230401/links")
            .then().statusCode(200).body("items.size()", equalTo(0));

        given().when().post("/20230401/organizationTenancies/" + child + "/actions/restore")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue());
        given().when().get("/20230401/organizations/" + organizationId + "/tenancies/" + child)
            .then().statusCode(200).body("lifecycleState", equalTo("ACTIVE"));

        given().when().post("/20230401/organizationTenancies/" + child + "/actions/restore")
            .then()
                .statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"));
    }

    @Test
    void deleteLinkTerminatesIt() {
        String child = createChild("rest-link-" + System.nanoTime(), "us-ashburn-1");
        String linkId = given().queryParam("childTenancyId", child).queryParam("feature", "CORE")
            .when().get("/20230401/links")
            .then().statusCode(200).extract().path("items[0].id");

        given().when().delete("/20230401/links/" + linkId)
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue());

        given().when().get("/20230401/links/" + linkId)
            .then().statusCode(200)
                .body("lifecycleState", equalTo("TERMINATED"))
                .body("timeTerminated", notNullValue());
    }

    @Test
    void unknownOrganizationTenancyAndLinkAre404() {
        given().when().delete("/20230401/organizationTenancies/ocid1.tenancy.oc1..nobody")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
        given().when().delete("/20230401/links/ocid1.link.oc1..nolink")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void listLinksRejectsAnUnknownLifecycleState() {
        given().queryParam("lifecycleState", "DELETED")
            .when().get("/20230401/links")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void aChildTenancyStartsWithAnAdministrator() {
        String workRequestId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId(), "tenancyName", "admin-" + System.nanoTime(),
                        "homeRegion", "us-ashburn-1", "adminEmail", "owner@example.com",
                        "policyName", "Child_Admins"))
            .when().post("/20230401/childTenancies")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String child = given().when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200).extract().path("resources[0].identifier");

        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20160918/users")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("owner@example.com"))
                .body("[0].email", equalTo("owner@example.com"));
        String groupId = given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20160918/groups")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("Administrators"))
                .extract().path("[0].id");
        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
                .queryParam("groupId", groupId)
            .when().get("/20160918/userGroupMemberships")
            .then().statusCode(200).body("size()", equalTo(1));
        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20160918/policies")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("Child_Admins"))
                .body("[0].statements[0]", equalTo("ALLOW GROUP Administrators to manage all-resources IN TENANCY"));
        given().queryParam("compartmentId", config.defaultTenancyId())
            .when().get("/20160918/policies")
            .then().statusCode(200)
                .body("name", not(hasItem("Child_Admins")));
    }

    @Test
    void theAdministratorPolicyHasADefaultName() {
        String workRequestId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId(), "tenancyName", "default-" + System.nanoTime(),
                        "homeRegion", "us-ashburn-1", "adminEmail", "owner@example.com"))
            .when().post("/20230401/childTenancies")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String child = given().when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200).extract().path("resources[0].identifier");

        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20160918/policies")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].name", equalTo("Tenant_Admin_Policy"));
    }

    @Test
    void approveAndUnapproveForTransferReturnTheTenancy() {
        String child = createChild("transfer-" + System.nanoTime(), "us-ashburn-1");

        given().queryParam("compartmentId", config.defaultTenancyId())
            .when().post("/20230401/organizationTenancies/" + child + "/actions/approveForTransfer")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("tenancyId", equalTo(child))
                .body("isApprovedForTransfer", equalTo(true));
        given().queryParam("compartmentId", config.defaultTenancyId())
            .when().post("/20230401/organizationTenancies/" + child + "/actions/approveForTransfer")
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().queryParam("compartmentId", config.defaultTenancyId())
            .when().post("/20230401/organizationTenancies/" + child + "/actions/unapproveForTransfer")
            .then().statusCode(200).body("isApprovedForTransfer", equalTo(false));
        given()
            .when().post("/20230401/organizationTenancies/" + child + "/actions/approveForTransfer")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }

    @Test
    void listLinkFeaturesIsAWrappedCollection() {
        createChild("features-" + System.nanoTime(), "us-ashburn-1");

        given().queryParam("compartmentId", config.defaultTenancyId())
            .when().get("/20230401/linkFeatures")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("items[0].feature", equalTo("CORE"))
                .body("items.feature", hasItems("CORE", "SUBSCRIPTION_SHARING"))
                .body("items[0].displayName", notNullValue())
                .body("items[0].description", notNullValue());
    }

    @Test
    void listLinkFeaturesReturnsEveryFeatureInOneResponse() {
        String parent = "ocid1.tenancy.oc1..features" + System.nanoTime();
        List<String> features = IntStream.range(0, 120).mapToObj(i -> "FEATURE_" + i).toList();
        given().header("Authorization", signedAs(parent)).contentType("application/json")
                .body(Map.of("compartmentId", parent, "tenancyName", "many-features", "homeRegion", "us-ashburn-1",
                        "adminEmail", "admin@example.com", "features", features))
            .when().post("/20230401/childTenancies")
            .then().statusCode(202);

        given().header("Authorization", signedAs(parent)).queryParam("compartmentId", parent)
            .when().get("/20230401/linkFeatures")
            .then()
                .statusCode(200)
                .header("opc-next-page", nullValue())
                .body("items.size()", equalTo(121))
                .body("items[0].feature", equalTo("CORE"));
    }
}
