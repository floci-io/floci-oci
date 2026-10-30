package io.floci.oci.services.psql;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** The backup, restore and point-in-time recovery wire contract, in mock mode. */
@QuarkusTest
class PsqlBackupsRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itpsqlbackupcompartment");
        }
    }

    private static Map<String, Object> dbSystem(String displayName) {
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

    private static String createDbSystem(Map<String, Object> details) {
        return given().contentType("application/json").body(details)
            .when().post("/20220915/dbSystems")
            .then().statusCode(200).extract().path("id");
    }

    private static String backup(String dbSystemId, String displayName) {
        String workRequestId = given().contentType("application/json")
                .body(Map.of("displayName", displayName, "compartmentId", compartment, "dbSystemId", dbSystemId))
            .when().post("/20220915/backups")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        return given().when().get("/20220915/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("CREATE_POSTGRESQL_DB_SYSTEM_BACKUP"))
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].entityType", equalTo("postgresqlBackup"))
                .body("timeFinished", notNullValue())
                .extract().path("resources[0].identifier");
    }

    @Test
    void backupLifecycle() {
        String dbSystemId = createDbSystem(dbSystem("it-backed-up"));
        String backupId = backup(dbSystemId, "it-nightly");

        String etag = given().when().get("/20220915/backups/" + backupId)
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("sourceType", equalTo("MANUAL"))
                .body("dbSystemId", equalTo(dbSystemId))
                .body("dbSystemDetails.dbVersion", equalTo("15"))
                .body("backupSize", notNullValue())
                .extract().header("etag");
        given().queryParam("id", dbSystemId)
            .when().get("/20220915/backups")
            .then().statusCode(200).body("items.id", hasItem(backupId));
        given().header("if-match", etag).contentType("application/json").body(Map.of("retentionPeriod", 7))
            .when().put("/20220915/backups/" + backupId)
            .then().statusCode(200).header("etag", notNullValue()).body("retentionPeriod", equalTo(7));

        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "regions", List.of("us-ashburn-1")))
            .when().post("/20220915/backups/" + backupId + "/actions/copy")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20220915/backups/" + backupId)
            .then().statusCode(200).body("copyStatus[0].state", equalTo("COPIED"));

        String deleteId = given().when().delete("/20220915/backups/" + backupId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given().when().get("/20220915/workRequests/" + deleteId)
            .then().statusCode(200)
                .body("operationType", equalTo("DELETE_POSTGRESQL_DB_SYSTEM_BACKUP"))
                .body("resources[0].identifier", equalTo(backupId));
        given().when().get("/20220915/backups/" + backupId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
    }

    @Test
    void aDbSystemFromABackupEchoesItsSource() {
        String backupId = backup(createDbSystem(dbSystem("it-source")), "it-for-restore");
        Map<String, Object> fromBackup = dbSystem("it-restored");
        fromBackup.put("source", Map.of("sourceType", "BACKUP", "backupId", backupId));

        String restoredId = createDbSystem(fromBackup);

        given().when().get("/20220915/dbSystems/" + restoredId)
            .then().statusCode(200)
                .body("source.sourceType", equalTo("BACKUP"))
                .body("source.backupId", equalTo(backupId));
        given().contentType("application/json").body(Map.of("backupId", backupId))
            .when().post("/20220915/dbSystems/" + restoredId + "/actions/restore")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
    }

    @Test
    void pitrDetailsAndErrors() {
        String dbSystemId = createDbSystem(dbSystem("it-pitr"));

        given().when().get("/20220915/dbSystems/" + dbSystemId + "/pitrDetails")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("pitrState", equalTo("INACTIVE"))
                .body("recoveryTimeWindows.size()", equalTo(0));
        given().contentType("application/json").body(Map.of("timeToRestore", "2001-01-01T00:00:00Z"))
            .when().post("/20220915/dbSystems/" + dbSystemId + "/actions/restore")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().when().get("/20220915/backups/ocid1.postgresqlbackup.oc1.iad.missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().contentType("application/json")
                .body(Map.of("displayName", "x", "compartmentId", compartment))
            .when().post("/20220915/backups")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }
}
