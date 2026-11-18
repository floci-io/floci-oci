package io.floci.oci.services.artifacts;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** The management plane in mock mode (the test profile sets artifacts.mock=true). */
@QuarkusTest
class ArtifactsRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itartifacts");
        }
    }

    @Test
    void repositoryCrudRoundtrip() {
        String name = "it/app-" + System.nanoTime();
        String repositoryId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", name))
            .when().post("/20160918/container/repositories")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .body("id", startsWith("ocid1.containerrepo."))
                .body("lifecycleState", equalTo("AVAILABLE"))
                .body("isPublic", equalTo(false))
                .body("isImmutable", equalTo(false))
                .body("imageCount", equalTo(0))
                .body("namespace", notNullValue())
                .body("readme", nullValue())
                .body("multiRegionPeers", empty())
                .extract().path("id");

        given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/repositories")
            .then().statusCode(200)
                .body("repositoryCount", greaterThanOrEqualTo(1))
                .body("remainingItemsCount", equalTo(0))
                .body("items.find { it.id == '" + repositoryId + "' }.displayName", equalTo(name));

        given().contentType("application/json").body(Map.of("isPublic", true))
            .when().put("/20160918/container/repositories/" + repositoryId)
            .then().statusCode(200).body("isPublic", equalTo(true));

        given().when().delete("/20160918/container/repositories/" + repositoryId).then().statusCode(204);
        given().when().get("/20160918/container/repositories/" + repositoryId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void imagesAreAnEmptyCollectionInMockMode() {
        given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/images")
            .then().statusCode(200)
                .body("items", empty())
                .body("remainingItemsCount", equalTo(0));
    }

    @Test
    void configurationRoundtrip() {
        given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/configuration")
            .then().statusCode(200)
                .body("namespace", notNullValue())
                .body("isRepositoryCreatedOnFirstPush", equalTo(false));

        given().contentType("application/json").queryParam("compartmentId", compartment)
                .body(Map.of("isRepositoryCreatedOnFirstPush", true))
            .when().put("/20160918/container/configuration")
            .then().statusCode(200).body("isRepositoryCreatedOnFirstPush", equalTo(true));

        given().contentType("application/json").queryParam("compartmentId", compartment)
                .body(Map.of("isRepositoryCreatedOnFirstPush", false))
            .when().put("/20160918/container/configuration")
            .then().statusCode(200).body("isRepositoryCreatedOnFirstPush", equalTo(false));
    }

    @Test
    void invalidNameIsInvalidParameter() {
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "UPPER"))
            .when().post("/20160918/container/repositories")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void listWithoutCompartmentIsMissingParameter() {
        given().when().get("/20160918/container/repositories")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }

    @Test
    void dataPlaneIsUnavailableInMockMode() {
        given().header("Host", "iad.ocir.localhost.floci.io")
            .when().get("/v2/")
            .then().statusCode(503).body("errors[0].code", equalTo("UNAVAILABLE"));
    }

    @Test
    void dataPlaneIgnoresHostsWithoutAnOcirLabel() {
        given().when().get("/v2/")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void tokenEndpointIssuesAToken() {
        given().when().get("/20180419/docker/token")
            .then().statusCode(200).body("token", notNullValue()).body("access_token", notNullValue());
    }
}
