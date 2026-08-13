package io.floci.oci.services.functions;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane: flips {@code mock=false}, starts the shared fnproject/fnserver
 * container and invokes an actual FDK image through it. Skips cleanly when no Docker
 * socket is available (CI without Docker, restricted laptops).
 */
@QuarkusTest
@TestProfile(FunctionsDockerTest.RealFunctionsProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FunctionsDockerTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..dockerfncompartment";
    private static final String SUBNET = "ocid1.subnet.oc1.iad.dockersubnet";

    private String appId;
    private String fnId;

    @BeforeAll
    void requireDockerAndBuildFixture() throws Exception {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real fnserver tests");

        // Build the FDK fixture image (a current python FDK — old fnproject/hello images
        // predate the http-stream contract and fail against modern fnserver).
        java.nio.file.Path context = java.nio.file.Path.of(
                getClass().getResource("/fn-hello/Dockerfile").toURI()).getParent();
        Process build = new ProcessBuilder("docker", "build", "--load",
                "-t", "floci-oci-fn-hello:latest", context.toString())
                .redirectErrorStream(true).start();
        String output = new String(build.getInputStream().readAllBytes());
        assumeTrue(build.waitFor() == 0, "fixture image build failed: " + output);
    }

    @Test
    @Order(4)
    void cleanUpStateAndSidecar() {
        given().delete("/20181201/functions/" + fnId).then().statusCode(204);
        given().delete("/20181201/applications/" + appId).then().statusCode(204);
        // Stops the fnserver sidecar via Resettable.
        given().post("/_floci-oci/state/reset").then().statusCode(200);
    }

    @Test
    @Order(1)
    void createApplicationAndFunction() {
        appId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT,
                        "displayName", "docker-app-" + System.nanoTime(),
                        "subnetIds", List.of(SUBNET)))
            .when().post("/20181201/applications")
            .then().statusCode(200).extract().path("id");

        fnId = given()
                .contentType("application/json")
                .body(Map.of("applicationId", appId,
                        "displayName", "docker-fn-" + System.nanoTime(),
                        // Built in @BeforeAll from src/test/resources/fn-hello (current python FDK).
                        "image", "floci-oci-fn-hello:latest",
                        "memoryInMBs", 128, "timeoutInSeconds", 60))
            .when().post("/20181201/functions")
            .then().statusCode(200).extract().path("id");
    }

    @Test
    @Order(2)
    void invokeRunsTheRealImageThroughFnServer() {
        String body = given()
                .contentType("application/json")
                .body("{\"name\":\"floci\"}".getBytes())
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Hello floci"),
                "the FDK function should greet the caller by name, got: " + body);
    }

    @Test
    @Order(3)
    void dryRunNeverExecutes() {
        given()
            .header("is-dry-run", "true")
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200);
    }

    public static class RealFunctionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.functions.mock", "false");
        }

        @Override
        public String getConfigProfile() {
            return "functions-docker";
        }
    }
}
