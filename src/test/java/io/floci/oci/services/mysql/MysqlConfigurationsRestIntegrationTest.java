package io.floci.oci.services.mysql;

import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class MysqlConfigurationsRestIntegrationTest {

    private static String compartment;
    private static TestNetworks.Network network;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itmysqlconfig");
            network = TestNetworks.create(compartment);
        }
    }

    @Test
    void defaultConfigurationsAreListedPerShapeAndResolveById() {
        String id = given().queryParam("compartmentId", compartment)
                .queryParam("type", "DEFAULT").queryParam("shapeName", "MySQL.4")
            .when().get("/20190415/configurations")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .body("displayName", containsInAnyOrder("MySQL.4.Standalone", "MySQL.4.HA"))
                .body("[0]", not(hasKey("variables")))
                .extract().path("find { it.displayName == 'MySQL.4.Standalone' }.id");

        given().when().get("/20190415/configurations/" + id)
            .then().statusCode(200)
                .body("type", equalTo("DEFAULT"))
                .body("shapeName", equalTo("MySQL.4"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("options", empty())
                .body("variables", anEmptyMap());

        given().contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20190415/configurations/" + id)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void customConfigurationCrudRoundtrip() {
        String parent = given().queryParam("compartmentId", compartment).queryParam("type", "DEFAULT")
                .queryParam("displayName", "MySQL.2.Standalone")
            .when().get("/20190415/configurations")
            .then().statusCode(200).extract().path("[0].id");

        Response created = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "shapeName", "MySQL.2", "displayName", "tuned",
                        "parentConfigurationId", parent, "variables", Map.of("maxConnections", 500)))
            .when().post("/20190415/configurations")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("location", startsWith("/20190415/configurations/"))
                .body("type", equalTo("CUSTOM"))
                .body("parentConfigurationId", equalTo(parent))
                .body("variables.maxConnections", equalTo(500))
                .extract().response();
        String id = created.path("id");

        given().queryParam("compartmentId", compartment).queryParam("type", "CUSTOM")
            .when().get("/20190415/configurations")
            .then().statusCode(200).body("id", hasItem(id));

        given().contentType("application/json").header("if-match", created.header("etag"))
                .body(Map.of("description", "for orders"))
            .when().put("/20190415/configurations/" + id)
            .then().statusCode(200).body("description", equalTo("for orders"));

        // A DB system using it keeps it from being deleted.
        String dbSystemId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "shapeName", "MySQL.2", "subnetId", network.subnetId(),
                        "configurationId", id, "adminUsername", "adminUser", "adminPassword", "BEstrO0ng_#11"))
            .when().post("/20190415/dbSystems")
            .then().statusCode(200).body("configurationId", equalTo(id)).extract().path("id");
        given().when().delete("/20190415/configurations/" + id)
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().delete("/20190415/dbSystems/" + dbSystemId).then().statusCode(202);
        given().when().delete("/20190415/configurations/" + id).then().statusCode(204);
        given().when().get("/20190415/configurations/" + id)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
    }

    @Test
    void customConfigurationOptionsRoundTrip() {
        List<Map<String, String>> options = List.of(Map.of("name", "max_connections", "value", "500"),
                Map.of("name", "sql_mode", "value", ""));
        String id = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "shapeName", "MySQL.2", "displayName", "with-options",
                        "options", options))
            .when().post("/20190415/configurations")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("options", equalTo(options))
                .extract().path("id");

        given().when().get("/20190415/configurations/" + id)
            .then().statusCode(200).body("options", equalTo(options));
        given().queryParam("compartmentId", compartment).queryParam("configurationId", id)
            .when().get("/20190415/configurations")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0]", not(hasKey("options")));

        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "shapeName", "MySQL.2",
                        "options", List.of(Map.of("value", "500"))))
            .when().post("/20190415/configurations")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void aConfigurationForAnotherShapeIsRejected() {
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "shapeName", "MySQL.2", "subnetId", network.subnetId(),
                        "configurationId", MysqlCatalog.defaultConfigurationId("MySQL.8.HA", "oc1"),
                        "adminUsername", "adminUser", "adminPassword", "BEstrO0ng_#11"))
            .when().post("/20190415/dbSystems")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void anUnknownConfigurationIsNotAuthorizedOrNotFound() {
        given().when().get("/20190415/configurations/ocid1.mysqlconfiguration.oc1.iad.missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
