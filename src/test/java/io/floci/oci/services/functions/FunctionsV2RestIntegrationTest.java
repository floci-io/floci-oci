package io.floci.oci.services.functions;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** The /20260325 contract, in mock mode: work requests, sourceDetails, catalogs. */
@QuarkusTest
class FunctionsV2RestIntegrationTest {

    private static String compartment;
    private static final String SUBNET = "ocid1.subnet.oc1.iad.itv2subnet";

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itfnv2compartment");
        }
    }

    private static Map<String, Object> containerImage(String image) {
        return Map.of("sourceType", "CONTAINER_IMAGE", "image", image);
    }

    private String createApplication(String name) {
        Response created = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", name, "subnetIds", List.of(SUBNET),
                        "traceConfig", Map.of("isEnabled", false), "logging", Map.of("lineFormat", "JSON")))
            .when().post("/20260325/applications")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("traceConfig.isEnabled", equalTo(false))
                .body("logging.lineFormat", equalTo("JSON"))
                .extract().response();
        assertWorkRequest(created.header("opc-work-request-id"), "CREATE_APPLICATION", "functionsApplication",
                "CREATED", created.path("id"));
        return created.path("id");
    }

    private String createFunction(String applicationId, String name) {
        Response created = given()
                .contentType("application/json")
                .body(Map.of("applicationId", applicationId, "displayName", name, "memoryInMBs", 256,
                        "sourceDetails", containerImage("iad.ocir.io/tenant/" + name + ":0.0.1"),
                        "detachedModeTimeoutInSeconds", 300))
            .when().post("/20260325/functions")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body("sourceDetails.sourceType", equalTo("CONTAINER_IMAGE"))
                .body("sourceDetails.image", equalTo("iad.ocir.io/tenant/" + name + ":0.0.1"))
                .body("sourceDetails.imageDigest", startsWith("sha256:"))
                .body("image", nullValue())
                .body("imageDigest", nullValue())
                .body("detachedModeTimeoutInSeconds", equalTo(300))
                .body("invokeEndpoint", notNullValue())
                .extract().response();
        assertWorkRequest(created.header("opc-work-request-id"), "CREATE_FUNCTION", "functionsFunction",
                "CREATED", created.path("id"));
        return created.path("id");
    }

    private static void assertWorkRequest(String workRequestId, String operationType, String entityType,
                                          String actionType, String identifier) {
        given().when().get("/20260325/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo(operationType))
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue())
                .body("resources[0].entityType", equalTo(entityType))
                .body("resources[0].actionType", equalTo(actionType))
                .body("resources[0].identifier", equalTo(identifier));
    }

    @Test
    void applicationAndFunctionLifecycle() {
        String appId = createApplication("v2-app");
        String fnId = createFunction(appId, "v2-fn");

        given().queryParam("applicationId", appId)
            .when().get("/20260325/functions")
            .then().statusCode(200).body("id", hasItem(fnId)).body("[0].sourceDetails.sourceType", notNullValue());

        String updated = given().contentType("application/json")
                .body(Map.of("memoryInMBs", 512, "sourceDetails", containerImage("iad.ocir.io/tenant/v2-fn:0.0.2")))
            .when().put("/20260325/functions/" + fnId)
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .body(emptyString())
                .extract().header("opc-work-request-id");
        assertWorkRequest(updated, "UPDATE_FUNCTION", "functionsFunction", "UPDATED", fnId);
        given().when().get("/20260325/functions/" + fnId)
            .then().statusCode(200)
                .body("memoryInMBs", equalTo(512))
                .body("sourceDetails.image", equalTo("iad.ocir.io/tenant/v2-fn:0.0.2"));

        // The same function through the old version, with its top-level image.
        given().when().get("/20181201/functions/" + fnId)
            .then().statusCode(200)
                .body("image", equalTo("iad.ocir.io/tenant/v2-fn:0.0.2"))
                .body("sourceDetails", nullValue());

        given().contentType("application/json").body(Map.of("config", Map.of("K", "V")))
            .when().put("/20260325/applications/" + appId)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());

        String deleted = given().when().delete("/20260325/functions/" + fnId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        assertWorkRequest(deleted, "DELETE_FUNCTION", "functionsFunction", "DELETED", fnId);
        given().when().delete("/20260325/applications/" + appId)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20260325/applications/" + appId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void sourcesFlociOciCannotRunAre501() {
        String appId = createApplication("v2-sources");

        given().contentType("application/json")
                .body(Map.of("applicationId", appId, "displayName", "archive", "memoryInMBs", 128,
                        "sourceDetails", Map.of("sourceType", "ARCHIVE")))
            .when().post("/20260325/functions")
            .then()
                .statusCode(501)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("MethodNotImplemented"));
        given().contentType("application/json")
                .body(Map.of("applicationId", appId, "displayName", "nosource", "memoryInMBs", 128))
            .when().post("/20260325/functions")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().contentType("application/json")
                .body(Map.of("applicationId", appId, "displayName", "odd", "memoryInMBs", 128,
                        "sourceDetails", Map.of("sourceType", "ZIP")))
            .when().post("/20260325/functions")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void workRequestsListAndCannotBeCanceled() {
        String appId = createApplication("v2-wr");

        String workRequestId = given().queryParam("compartmentId", compartment).queryParam("resourceId", appId)
            .when().get("/20260325/workRequests")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].operationType", equalTo("CREATE_APPLICATION"))
                .extract().path("items[0].id");
        given().when().get("/20260325/workRequests/" + workRequestId + "/errors")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given().when().get("/20260325/workRequests/" + workRequestId + "/logs")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given().when().delete("/20260325/workRequests/" + workRequestId)
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().queryParam("compartmentId", compartment).queryParam("status", "DONE")
            .when().get("/20260325/workRequests")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void catalogsAndV2Invoke() {
        String runtimeId = given().queryParam("name", "java17.ol9")
            .when().get("/20260325/functionsRuntimes")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].currentFunctionsRuntimeVersionId", notNullValue())
                .extract().path("items[0].id");
        given().when().get("/20260325/functionsRuntimes/" + runtimeId)
            .then().statusCode(200).body("name", equalTo("java17.ol9"));
        given().queryParam("functionsRuntimeId", runtimeId)
            .when().get("/20260325/functionsRuntimeVersions")
            .then().statusCode(200).body("items[0].supportedArchitectures", hasItem("X86"));
        given().when().get("/20260325/pbfListings")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given().when().get("/20260325/pbfListings/triggers")
            .then().statusCode(200).body("items.size()", equalTo(0));
        given().when().get("/20260325/pbfListingVersions")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().when().get("/20260325/pbfListings/ocid1.pbflisting.oc1.iad.x")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));

        String fnId = createFunction(createApplication("v2-invoke"), "v2-invoked");
        given().contentType("application/json").body("{}".getBytes())
            .when().post("/20260325/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200).header("opc-request-id", notNullValue());
    }
}
