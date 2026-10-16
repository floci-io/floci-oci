package io.floci.oci.services.psql;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The DB system wire contract in mock mode: no container, every transition completes at once. */
@QuarkusTest
class PsqlRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itpsqlcompartment");
        }
    }

    private static Map<String, Object> createDetails(String displayName) {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", displayName);
        details.put("compartmentId", compartment);
        details.put("dbVersion", "15");
        details.put("shape", "PostgreSQL.VM.Standard.E4.Flex.2.32GB");
        details.put("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", true));
        details.put("networkDetails", Map.of("subnetId", "ocid1.subnet.oc1.iad.itpsqlsubnet"));
        details.put("credentials", Map.of("username", "adminUser",
                "passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "BEstrO0ng_#11")));
        return details;
    }

    private static Response create(String displayName) {
        return given().contentType("application/json").body(createDetails(displayName))
            .when().post("/20220915/dbSystems")
            .then().statusCode(200).extract().response();
    }

    @Test
    void createReturnsTheDbSystemWithItsEtagAndWorkRequest() {
        given().contentType("application/json").body(createDetails("it-create"))
            .when().post("/20220915/dbSystems")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("opc-work-request-id", startsWith("ocid1."))
                .body("id", startsWith("ocid1.postgresqldbsystem.oc1.iad."))
                .body("displayName", equalTo("it-create"))
                .body("compartmentId", equalTo(compartment))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("systemType", equalTo("OCI_OPTIMIZED_STORAGE"))
                .body("systemRole", equalTo("STANDALONE_DB_SYSTEM"))
                .body("dbVersion", equalTo("15"))
                .body("shape", equalTo("VM.Standard.E4.Flex"))
                .body("instanceOcpuCount", equalTo(2))
                .body("instanceMemorySizeInGBs", equalTo(32))
                .body("instanceCount", equalTo(1))
                .body("adminUsername", equalTo("adminUser"))
                .body("credentials", nullValue())
                .body("storageDetails.isRegionallyDurable", equalTo(true))
                .body("networkDetails.subnetId", equalTo("ocid1.subnet.oc1.iad.itpsqlsubnet"))
                .body("managementPolicy.backupPolicy.kind", equalTo("NONE"))
                .body("source.sourceType", equalTo("NONE"))
                .body("instances[0].lifecycleState", equalTo("ACTIVE"))
                .body("configId", startsWith("ocid1.postgresqldefaultconfiguration.oc1.iad."));
    }

    @Test
    void theCreateWorkRequestNamesTheDbSystem() {
        Response created = create("it-work-request");
        String id = created.path("id");
        String workRequestId = created.header("opc-work-request-id");

        given()
            .when().get("/20220915/workRequests/" + workRequestId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("operationType", equalTo("CREATE_POSTGRESQL_DB_SYSTEM"))
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue())
                .body("errors", nullValue())
                .body("resources[0].entityType", equalTo("postgresqlDbSystem"))
                .body("resources[0].actionType", equalTo("CREATED"))
                .body("resources[0].identifier", equalTo(id));

        given().queryParam("compartmentId", compartment).queryParam("resourceId", id)
            .when().get("/20220915/workRequests")
            .then().statusCode(200).body("items.id", equalTo(List.of(workRequestId)));
        given()
            .when().get("/20220915/workRequests/" + workRequestId + "/errors")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given()
            .when().get("/20220915/workRequests/" + workRequestId + "/logs")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given().queryParam("compartmentId", compartment).queryParam("sortOrder", "SIDEWAYS")
            .when().get("/20220915/workRequests")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void getListAndConnectionDetails() {
        String id = create("it-read").path("id");

        given()
            .when().get("/20220915/dbSystems/" + id)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(id));
        given().queryParam("excludedFields", "dbConfigurationParams")
            .when().get("/20220915/dbSystems/" + id)
            .then().statusCode(200);
        given().queryParam("excludedFields", "everything")
            .when().get("/20220915/dbSystems/" + id)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        given().queryParam("compartmentId", compartment).queryParam("displayName", "it-read")
            .when().get("/20220915/dbSystems")
            .then().statusCode(200)
                .body("items.id", equalTo(List.of(id)))
                .body("items[0].instanceCount", equalTo(1))
                .body("items[0].freeformTags", notNullValue())
                .body("items[0].networkDetails", nullValue());

        given()
            .when().get("/20220915/dbSystems/" + id + "/connectionDetails")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("caCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
                .body("primaryDbEndpoint.fqdn", equalTo("localhost"))
                .body("primaryDbEndpoint.port", equalTo(15432))
                .body("instanceEndpoints.size()", equalTo(1));
        String instanceId = given()
            .when().get("/20220915/dbSystems/" + id + "/primaryDbInstance")
            .then().statusCode(200).extract().path("dbInstanceId");
        given()
            .when().get("/20220915/dbSystems/" + id)
            .then().body("instances[0].id", equalTo(instanceId));
    }

    @Test
    void listIsPaged() {
        create("it-page-a");
        create("it-page-b");

        String next = given().queryParam("compartmentId", compartment).queryParam("limit", 1)
            .when().get("/20220915/dbSystems")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .extract().header("opc-next-page");
        given().queryParam("compartmentId", compartment).queryParam("limit", 1).queryParam("page", next)
            .when().get("/20220915/dbSystems")
            .then().statusCode(200).body("items.size()", equalTo(1));
    }

    @Test
    void updateStopStartAndResetReturnOnlyAWorkRequest() {
        Response created = create("it-actions");
        String id = created.path("id");
        String etag = created.header("etag");
        String instanceId = created.path("instances[0].id");

        given().contentType("application/json").header("if-match", etag)
                .body(Map.of("displayName", "it-actions-renamed"))
            .when().put("/20220915/dbSystems/" + id)
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .header("opc-request-id", notNullValue());
        String newEtag = given().when().get("/20220915/dbSystems/" + id)
            .then().body("displayName", equalTo("it-actions-renamed")).extract().header("etag");
        assertNotEquals(etag, newEtag);
        given().contentType("application/json").header("if-match", etag).body(Map.of("displayName", "stale"))
            .when().put("/20220915/dbSystems/" + id)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));

        given().contentType("application/json")
                .body(Map.of("passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "N3w_passw0rd#")))
            .when().post("/20220915/dbSystems/" + id + "/actions/resetMasterUserPassword")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().contentType("application/json")
                .body(Map.of("dbInstanceId", instanceId, "restartType", "NORMAL"))
            .when().post("/20220915/dbSystems/" + id + "/actions/restartDbInstance")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().contentType("application/json").body(Map.of("displayName", "node-1"))
            .when().put("/20220915/dbSystems/" + id + "/dbinstances/" + instanceId)
            .then().statusCode(202);
        given().when().get("/20220915/dbSystems/" + id)
            .then().body("instances[0].displayName", equalTo("node-1"));

        given().when().post("/20220915/dbSystems/" + id + "/actions/stop")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20220915/dbSystems/" + id)
            .then().body("lifecycleState", equalTo("INACTIVE"));
        given().when().post("/20220915/dbSystems/" + id + "/actions/stop")
            .then().statusCode(409).body("code", equalTo("IncorrectState"));
        given().contentType("application/json").body(Map.of())
            .when().post("/20220915/dbSystems/" + id + "/actions/start")
            .then().statusCode(202);
        given().when().get("/20220915/dbSystems/" + id)
            .then().body("lifecycleState", equalTo("ACTIVE"));
    }

    @Test
    void aDeletedDbSystemIsReturnedAsDeleted() {
        String id = create("it-delete").path("id");

        String workRequestId = given()
            .when().delete("/20220915/dbSystems/" + id)
            .then().statusCode(202).extract().header("opc-work-request-id");

        given().when().get("/20220915/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("DELETE_POSTGRESQL_DB_SYSTEM"))
                .body("status", equalTo("SUCCEEDED"));
        given().when().get("/20220915/dbSystems/" + id)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
        given().queryParam("compartmentId", compartment).queryParam("lifecycleState", "ACTIVE")
            .when().get("/20220915/dbSystems")
            .then().body("items.id", not(hasItem(id)));
    }

    @Test
    void changeCompartmentMovesTheDbSystem() {
        String id = create("it-move").path("id");
        String target = TestCompartments.create("itpsqltarget");

        given().contentType("application/json").body(Map.of("compartmentId", target))
            .when().post("/20220915/dbSystems/" + id + "/actions/changeCompartment")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());

        given().when().get("/20220915/dbSystems/" + id)
            .then().body("compartmentId", equalTo(target));
    }

    @Test
    void errorsCarryTheOciShape() {
        given().contentType("application/json").body(Map.of("displayName", "incomplete"))
            .when().post("/20220915/dbSystems")
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("MissingParameter"))
                .body("message", equalTo("Missing required parameter: compartmentId"));

        Map<String, Object> unknownCompartment = createDetails("it-nowhere");
        unknownCompartment.put("compartmentId", "ocid1.compartment.oc1..itpsqlnosuchcompartment");
        given().contentType("application/json").body(unknownCompartment)
            .when().post("/20220915/dbSystems")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));

        given().when().get("/20220915/dbSystems/ocid1.postgresqldbsystem.oc1.iad.missing")
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"));

        Map<String, Object> fromBackup = createDetails("it-from-backup");
        fromBackup.put("source", Map.of("sourceType", "BACKUP", "backupId", "ocid1.postgresqlbackup.oc1.iad.x"));
        given().contentType("application/json").body(fromBackup)
            .when().post("/20220915/dbSystems")
            .then().statusCode(501);
    }

    @Test
    void aRegionTheTenancyHasNotSubscribedToIsRejected() {
        given().header("Host", "postgresql.ap-osaka-1.oci.oraclecloud.com")
                .queryParam("compartmentId", compartment)
            .when().get("/20220915/dbSystems")
            .then().statusCode(401).body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void theCatalogListsShapesAndDefaultConfigurations() {
        given().queryParam("compartmentId", compartment)
            .when().get("/20220915/shapes")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("items.shape", hasItems("VM.Standard.E4.Flex", "VM.Standard.E5.Flex"))
                .body("items[0].ocpuCount", equalTo(2))
                .body("items[0].memorySizeInGBs", equalTo(32));

        String configurationId = given().queryParam("dbVersion", "15")
            .when().get("/20220915/defaultConfigurations")
            .then().statusCode(200)
                .body("items.size()", equalTo(3))
                .body("items[0].isFlexible", equalTo(true))
                .body("items[0].compatibleShapes", hasItems("VM.Standard.E4.Flex"))
                .body("items[1].isFlexible", equalTo(false))
                .extract().path("items[0].id");
        given()
            .when().get("/20220915/defaultConfigurations/" + configurationId)
            .then().statusCode(200)
                .body("id", equalTo(configurationId))
                .body("dbVersion", equalTo("15"))
                .body("configurationDetails.items.size()", equalTo(0));
        given()
            .when().get("/20220915/defaultConfigurations/ocid1.postgresqldefaultconfiguration.oc1.iad.missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
