package io.floci.oci.services.database;

import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AutonomousDatabaseRestIntegrationTest {

    private static final String PASSWORD = "Welcome12345#";
    private static String compartment;
    private static TestNetworks.Network network;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itadb");
            network = TestNetworks.create(compartment);
        }
    }

    private static Map<String, Object> createDetails(String dbName) {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("source", "NONE");
        details.put("dbName", dbName);
        details.put("displayName", dbName + "-display");
        details.put("adminPassword", PASSWORD);
        details.put("computeModel", "ECPU");
        details.put("computeCount", 2);
        details.put("dataStorageSizeInTBs", 1);
        details.put("dbWorkload", "OLTP");
        return details;
    }

    private static Response create(Map<String, Object> details) {
        return given().contentType("application/json").body(details)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(200).extract().response();
    }

    private static String create(String dbName) {
        return create(createDetails(dbName)).path("id");
    }

    @Test
    void createReturnsAnAvailableDatabaseWithItsEtagAndWorkRequest() {
        Map<String, Object> details = createDetails("itcreate");
        details.put("dbVersion", "23ai");
        details.put("characterSet", "WE8ISO8859P1");
        details.put("whitelistedIps", List.of("10.0.0.0/16"));
        details.put("freeformTags", Map.of("team", "data"));

        given().contentType("application/json").body(details)
            .when().post("/20160918/autonomousDatabases")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("opc-work-request-id", startsWith("ocid1.coreservicesworkrequest."))
                .body("id", startsWith("ocid1.autonomousdatabase.oc1.iad."))
                .body("compartmentId", equalTo(compartment))
                .body("lifecycleState", equalTo("AVAILABLE"))
                .body("dbName", equalTo("itcreate"))
                .body("displayName", equalTo("itcreate-display"))
                .body("dbVersion", equalTo("23ai"))
                .body("characterSet", equalTo("WE8ISO8859P1"))
                .body("ncharacterSet", equalTo("AL16UTF16"))
                .body("computeModel", equalTo("ECPU"))
                .body("computeCount", equalTo(2))
                .body("dataStorageSizeInTBs", equalTo(1))
                .body("dataStorageSizeInGBs", equalTo(1024))
                .body("dbWorkload", equalTo("OLTP"))
                .body("whitelistedIps", equalTo(List.of("10.0.0.0/16")))
                .body("freeformTags.team", equalTo("data"))
                .body("connectionStrings.high", equalTo("localhost:1522/itcreate_high.adb.oraclecloud.com"))
                .body("connectionStrings.profiles.displayName",
                        equalTo(List.of("itcreate_high", "itcreate_medium", "itcreate_low", "itcreate_tp",
                                "itcreate_tpurgent")))
                .body("connectionStrings.profiles[0].tlsAuthentication", equalTo("MUTUAL"))
                .body("timeCreated", notNullValue())
                .body("$", not(hasKey("adminPassword")))
                .body("$", not(hasKey("source")));
    }

    @Test
    void theCreateWorkRequestNamesTheDatabaseForTerraform() {
        Response created = create(createDetails("itworkreq"));
        String id = created.path("id");

        given().when().get("/20160918/workRequests/" + created.header("opc-work-request-id"))
            .then()
                .statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("operationType", equalTo("CREATE_AUTONOMOUS_DATABASE"))
                .body("compartmentId", equalTo(compartment))
                .body("resources[0].entityType", equalTo("autonomousDatabase"))
                .body("resources[0].actionType", equalTo("CREATED"))
                .body("resources[0].identifier", equalTo(id))
                .body("percentComplete", equalTo(100.0f));
    }

    @Test
    void defaultsFillWhatTheRequestLeftOut() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("adminPassword", PASSWORD);

        create(details).then()
                .body("dbName", matchesPattern("DB\\d{14}\\d*"))
                .body("characterSet", equalTo("AL32UTF8"))
                .body("ncharacterSet", equalTo("AL16UTF16"))
                .body("computeModel", equalTo("ECPU"))
                .body("dbWorkload", equalTo("OLTP"))
                .body("licenseModel", equalTo("LICENSE_INCLUDED"))
                .body("isFreeTier", equalTo(false))
                .body("isMtlsConnectionRequired", equalTo(true));
    }

    @Test
    void listIsABareArrayFilteredAndPaged() {
        String first = create("itlistone");
        String second = create("itlisttwo");

        given().queryParam("compartmentId", compartment).queryParam("sortBy", "DISPLAYNAME")
            .when().get("/20160918/autonomousDatabases")
            .then()
                .statusCode(200)
                .body("$", instanceOf(List.class))
                .body("id", hasItems(first, second))
                .body("find { it.id == '" + first + "' }.dbName", equalTo("itlistone"))
                .body("find { it.id == '" + first + "' }", not(hasKey("adminPassword")));

        given().queryParam("compartmentId", compartment).queryParam("displayName", "itlisttwo-display")
            .when().get("/20160918/autonomousDatabases")
            .then().statusCode(200).body("id", equalTo(List.of(second)));

        Response page = given().queryParam("compartmentId", compartment).queryParam("limit", 1)
            .when().get("/20160918/autonomousDatabases")
            .then().statusCode(200).body("size()", equalTo(1)).extract().response();
        assertNotNull(page.header("opc-next-page"));

        given().queryParam("compartmentId", compartment).queryParam("lifecycleState", "NOT_A_STATE")
            .when().get("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        given().when().get("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }

    @Test
    void updateEchoesTheNewValuesAndHonorsIfMatch() {
        Response created = create(createDetails("itupdate"));
        String id = created.path("id");
        String etag = created.header("etag");

        Response updated = given().contentType("application/json").header("if-match", etag)
            .body(Map.of("displayName", "renamed", "computeCount", 4, "dataStorageSizeInGBs", 2000,
                    "adminPassword", "Another12345#", "isAutoScalingEnabled", true))
            .when().put("/20160918/autonomousDatabases/" + id)
            .then()
                .statusCode(200)
                .header("opc-work-request-id", notNullValue())
                .body("displayName", equalTo("renamed"))
                .body("computeCount", equalTo(4))
                .body("dataStorageSizeInGBs", equalTo(2000))
                .body("dataStorageSizeInTBs", equalTo(2))
                .body("isAutoScalingEnabled", equalTo(true))
                .body("lifecycleState", equalTo("AVAILABLE"))
                .body("$", not(hasKey("adminPassword")))
                .extract().response();
        assertNotEquals(etag, updated.header("etag"));

        given().contentType("application/json").header("if-match", etag)
            .body(Map.of("displayName", "stale"))
            .when().put("/20160918/autonomousDatabases/" + id)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));

        given().contentType("application/json").body(Map.of("dbWorkload", "NOT_A_WORKLOAD"))
            .when().put("/20160918/autonomousDatabases/" + id)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().when().get("/20160918/autonomousDatabases/" + id)
            .then().body("dbWorkload", equalTo("OLTP")).body("displayName", equalTo("renamed"));
    }

    @Test
    void stopStartAndRestartMoveTheLifecycle() {
        String id = create("itlifecycle");

        given().when().post("/20160918/autonomousDatabases/" + id + "/actions/stop")
            .then().statusCode(200).header("opc-work-request-id", notNullValue())
                .header("etag", notNullValue()).body("lifecycleState", equalTo("STOPPED"));

        given().when().post("/20160918/autonomousDatabases/" + id + "/actions/restart")
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().post("/20160918/autonomousDatabases/" + id + "/actions/start")
            .then().statusCode(200).body("lifecycleState", equalTo("AVAILABLE"));

        given().when().post("/20160918/autonomousDatabases/" + id + "/actions/start")
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().contentType("application/json").body(Map.of())
            .when().post("/20160918/autonomousDatabases/" + id + "/actions/restart")
            .then().statusCode(200).body("lifecycleState", equalTo("AVAILABLE"));
    }

    @Test
    void generateWalletReturnsAZipNamingTheAliases() throws IOException {
        String id = create("itwallet");

        Response wallet = given().contentType("application/json").accept("application/octet-stream")
            .body(Map.of("password", "WalletPass1", "generateType", "SINGLE"))
            .when().post("/20160918/autonomousDatabases/" + id + "/actions/generateWallet")
            .then()
                .statusCode(200)
                .contentType("application/octet-stream")
                .header("etag", notNullValue())
                .header("last-modified", notNullValue())
                .extract().response();
        Map<String, String> files = unzip(wallet.asByteArray());
        assertEquals(List.of("tnsnames.ora", "sqlnet.ora", "ojdbc.properties", "README"),
                List.copyOf(files.keySet()));
        assertTrue(files.get("tnsnames.ora").contains("itwallet_high = (description="), files.get("tnsnames.ora"));
        assertTrue(files.get("tnsnames.ora").contains("service_name=itwallet_tpurgent.adb.oraclecloud.com"));

        given().contentType("application/json").accept("application/octet-stream")
            .body(Map.of("password", "short"))
            .when().post("/20160918/autonomousDatabases/" + id + "/actions/generateWallet")
            .then().statusCode(400).contentType("application/json").body("code", equalTo("InvalidParameter"));
    }

    @Test
    void deleteLeavesATerminatedTombstone() {
        String id = create("itdelete");

        given().when().delete("/20160918/autonomousDatabases/" + id)
            .then().statusCode(204).header("opc-work-request-id", notNullValue());

        given().when().get("/20160918/autonomousDatabases/" + id)
            .then().statusCode(200).body("lifecycleState", equalTo("TERMINATED"));
        given().queryParam("compartmentId", compartment).queryParam("lifecycleState", "TERMINATED")
            .when().get("/20160918/autonomousDatabases")
            .then().statusCode(200).body("id", hasItem(id));

        given().when().delete("/20160918/autonomousDatabases/" + id)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().when().post("/20160918/autonomousDatabases/" + id + "/actions/start")
            .then().statusCode(404);
    }

    @Test
    void createRejectsBadInput() {
        Map<String, Object> weak = createDetails("itweak");
        weak.put("adminPassword", "password");
        given().contentType("application/json").body(weak)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", containsString("adminPassword must be 12 to 30 characters"));

        Map<String, Object> clone = createDetails("itclone");
        clone.put("source", "DATABASE");
        clone.put("sourceId", "ocid1.autonomousdatabase.oc1.iad.other");
        given().contentType("application/json").body(clone)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        Map<String, Object> badName = createDetails("1bad_name");
        given().contentType("application/json").body(badName)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        Map<String, Object> noCompartment = createDetails("itnocomp");
        noCompartment.remove("compartmentId");
        given().contentType("application/json").body(noCompartment)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));

        Map<String, Object> unknownCompartment = createDetails("itunknown");
        unknownCompartment.put("compartmentId", "ocid1.compartment.oc1..doesnotexist");
        given().contentType("application/json").body(unknownCompartment)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));

        Map<String, Object> unknownSubnet = createDetails("itsubnet");
        unknownSubnet.put("subnetId", "ocid1.subnet.oc1.iad.doesnotexist");
        given().contentType("application/json").body(unknownSubnet)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(400).body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));

        Map<String, Object> inSubnet = createDetails("itinsubnet");
        inSubnet.put("subnetId", network.subnetId());
        create(inSubnet).then().body("subnetId", equalTo(network.subnetId()));
    }

    @Test
    void anUnknownDatabaseIsNotFound() {
        given().when().get("/20160918/autonomousDatabases/ocid1.autonomousdatabase.oc1.iad.missing")
            .then().statusCode(404).header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
