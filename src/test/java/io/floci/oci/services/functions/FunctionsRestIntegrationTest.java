package io.floci.oci.services.functions;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Runs in mock mode (test yml sets functions.mock=true) — no Docker required. */
@QuarkusTest
class FunctionsRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..itfncompartment";
    private static final String SUBNET = "ocid1.subnet.oc1.iad.itsubnet";

    private String createApplication(String name) {
        return given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT, "displayName", name,
                        "subnetIds", List.of(SUBNET)))
            .when().post("/20181201/applications")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.fnapp."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("shape", equalTo("GENERIC_X86"))
                .extract().path("id");
    }

    private String createFunction(String applicationId, String name) {
        return given()
                .contentType("application/json")
                .body(Map.of("applicationId", applicationId, "displayName", name,
                        "image", "iad.ocir.io/tenant/" + name + ":0.0.1", "memoryInMBs", 256))
            .when().post("/20181201/functions")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.fnfunc."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("memoryInMBs", equalTo(256))
                .body("imageDigest", startsWith("sha256:"))
                .body("invokeEndpoint", notNullValue())
                .extract().path("id");
    }

    @Test
    void applicationAndFunctionCrudRoundtrip() {
        String suffix = String.valueOf(System.nanoTime());
        String appId = createApplication("it-app-" + suffix);
        String fnId = createFunction(appId, "it-fn-" + suffix);

        // Bare-array lists; summaries drop config.
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20181201/applications")
            .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("find { it.id == '" + appId + "' }.config", nullValue());

        given()
            .queryParam("applicationId", appId)
            .when().get("/20181201/functions")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].memoryInMBs", equalTo(256));

        // Image change must change the digest (Terraform contract).
        String originalDigest = given()
                .when().get("/20181201/functions/" + fnId)
                .then().statusCode(200).extract().path("imageDigest");
        given()
            .contentType("application/json")
            .body(Map.of("image", "iad.ocir.io/tenant/updated:0.0.2"))
            .when().put("/20181201/functions/" + fnId)
            .then().statusCode(200)
                .body("imageDigest", org.hamcrest.Matchers.not(equalTo(originalDigest)));

        // Application with functions refuses deletion.
        given()
            .when().delete("/20181201/applications/" + appId)
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().delete("/20181201/functions/" + fnId).then().statusCode(204);
        given().when().delete("/20181201/applications/" + appId).then().statusCode(204);
        given().when().get("/20181201/applications/" + appId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void invokeIsRawBinaryInMockMode() {
        String suffix = String.valueOf(System.nanoTime());
        String appId = createApplication("it-invoke-app-" + suffix);
        String fnId = createFunction(appId, "it-invoke-fn-" + suffix);

        // Raw bytes out — not JSON-wrapped by the emulator.
        String body = given()
                .contentType("application/json")
                .body("{\"name\":\"world\"}".getBytes())
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("mock invocation"));

        // Dry run: validates, never executes.
        given()
            .header("is-dry-run", "true")
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200);

        // Detached: immediate return.
        given()
            .header("fn-invoke-type", "detached")
            .body("x".getBytes())
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(202);

        given().when().delete("/20181201/functions/" + fnId).then().statusCode(204);
        given().when().delete("/20181201/applications/" + appId).then().statusCode(204);
    }

    @Test
    void invokeUnknownFunctionIsNotAuthorizedOrNotFound() {
        given()
            .body("{}".getBytes())
            .when().post("/20181201/functions/ocid1.fnfunc.oc1.iad.missing/actions/invoke")
            .then().statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
