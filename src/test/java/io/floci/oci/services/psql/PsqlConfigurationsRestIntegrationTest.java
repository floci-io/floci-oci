package io.floci.oci.services.psql;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** The custom configuration wire contract, in mock mode. */
@QuarkusTest
class PsqlConfigurationsRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itpsqlconfigcompartment");
        }
    }

    private static String createConfiguration(String displayName) {
        return given().contentType("application/json")
                .body(Map.of("displayName", displayName, "compartmentId", compartment, "dbVersion", "15",
                        "isFlexible", true, "dbConfigurationOverrides", Map.of("items",
                                List.of(Map.of("configKey", "work_mem", "overridenConfigValue", "16MB")))))
            .when().post("/20220915/configurations")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("opc-work-request-id", (String) null)
                .body("configType", equalTo("CUSTOM"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("shape", equalTo("PostgreSQL"))
                .body("instanceOcpuCount", equalTo(0))
                .body("instanceMemorySizeInGBs", equalTo(0))
                .body("isFlexible", equalTo(true))
                .body("id", not(containsString("defaultconfiguration")))
                .body("configurationDetails.items.find { it.configKey == 'work_mem' }.overridenConfigValue",
                        equalTo("16MB"))
                .extract().path("id");
    }

    private static Map<String, Object> dbSystem(String displayName, String configId) {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", displayName);
        details.put("compartmentId", compartment);
        details.put("dbVersion", "15");
        details.put("shape", "PostgreSQL.VM.Standard.E4.Flex");
        details.put("instanceOcpuCount", 2);
        details.put("instanceMemorySizeInGBs", 32);
        details.put("configId", configId);
        details.put("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", true));
        details.put("networkDetails", Map.of("subnetId", "ocid1.subnet.oc1.iad.itpsqlsubnet"));
        details.put("credentials", Map.of("username", "adminUser",
                "passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "BEstrO0ng_#11")));
        return details;
    }

    @Test
    void configurationLifecycle() {
        String configurationId = createConfiguration("it-tuned");

        given().queryParam("compartmentId", compartment)
            .when().get("/20220915/configurations")
            .then()
                .statusCode(200)
                .body("items.id", hasItem(configurationId))
                .body("items[0].configurationDetails", equalTo(null));

        String etag = given().when().get("/20220915/configurations/" + configurationId)
            .then().statusCode(200).extract().header("etag");
        given().header("if-match", etag).contentType("application/json").body(Map.of("displayName", "it-renamed"))
            .when().put("/20220915/configurations/" + configurationId)
            .then().statusCode(200).header("etag", notNullValue()).body("displayName", equalTo("it-renamed"));

        given().when().delete("/20220915/configurations/" + configurationId)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().when().get("/20220915/configurations/" + configurationId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
    }

    @Test
    void aConfigurationInUseCannotBeDeleted() {
        String configurationId = createConfiguration("it-in-use");
        String dbSystemId = given().contentType("application/json").body(dbSystem("it-configured", configurationId))
            .when().post("/20220915/dbSystems")
            .then().statusCode(200).body("configId", equalTo(configurationId)).extract().path("id");

        given().when().delete("/20220915/configurations/" + configurationId)
            .then()
                .statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"));

        given().when().delete("/20220915/dbSystems/" + dbSystemId).then().statusCode(202);
        given().when().delete("/20220915/configurations/" + configurationId).then().statusCode(204);
    }

    @Test
    void errorsUseTheOciShape() {
        given().contentType("application/json")
                .body(Map.of("displayName", "bad", "compartmentId", compartment, "dbVersion", "15",
                        "dbConfigurationOverrides", Map.of("items",
                                List.of(Map.of("configKey", "fsync", "overridenConfigValue", "off")))))
            .when().post("/20220915/configurations")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
        given().when().get("/20220915/configurations/ocid1.postgresqlconfiguration.oc1.iad.missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().queryParam("lifecycleState", "CREATING")
            .when().get("/20220915/configurations")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }
}
