package io.floci.oci.services.functions;

import io.floci.oci.services.artifacts.ArtifactsRegistryManager;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane for a function whose image is in the local OCIR: pushes the FDK fixture into the
 * {@code registry:2} sidecar, then creates the function with its {@code iad.ocir.localhost.floci.io}
 * reference and invokes it through fnserver. Skips when no Docker engine responds.
 */
@QuarkusTest
@TestProfile(FunctionsOcirDockerTest.RealFunctionsAndOcirProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FunctionsOcirDockerTest {

    private static final String FIXTURE = "floci-oci-fn-hello:latest";
    private static final String REPOSITORY = "fn-ocir/hello";

    @Inject
    ArtifactsRegistryManager registry;

    private String compartment;
    private String appId;
    private String fnId;
    private String pushed;

    @BeforeAll
    void requireDockerAndBuildFixture() throws Exception {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding; skipping the OCIR function tests");
        Path context = Path.of(getClass().getResource("/fn-hello/Dockerfile").toURI()).getParent();
        assumeTrue(docker("build", "--load", "-t", FIXTURE, context.toString()) == 0, "fixture image build failed");
        RestAssured.config = RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.socket.timeout", 90000)
                .setParam("http.connection.timeout", 90000));
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("dockerfnocir");
        }
    }

    private static int docker(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    @Order(1)
    void pushTheFixtureIntoTheRegistry() throws Exception {
        String namespace = given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/configuration")
            .then().statusCode(200).extract().path("namespace");
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", REPOSITORY))
            .when().post("/20160918/container/repositories")
            .then().statusCode(200);
        registry.ensureReady();
        pushed = "localhost:" + registry.hostPort() + "/iad/" + namespace + "/" + REPOSITORY + ":1";
        assertEquals(0, docker("tag", FIXTURE, pushed));
        assertEquals(0, docker("push", pushed));
        assertEquals(0, docker("rmi", pushed));

        appId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "ocir-app-" + System.nanoTime(),
                        "subnetIds", List.of("ocid1.subnet.oc1.iad.dockersubnet")))
            .when().post("/20181201/applications")
            .then().statusCode(200).extract().path("id");
        fnId = given().contentType("application/json")
                .body(Map.of("applicationId", appId, "displayName", "ocir-fn-" + System.nanoTime(),
                        "image", "iad.ocir.localhost.floci.io/" + namespace + "/" + REPOSITORY + ":1",
                        "memoryInMBs", 128, "timeoutInSeconds", 60))
            .when().post("/20181201/functions")
            .then().statusCode(200).extract().path("id");
    }

    @Test
    @Order(2)
    void invokeRunsTheImagePulledFromTheLocalOcir() {
        String body = given().contentType("application/json").body("{\"name\":\"ocir\"}".getBytes())
            .when().post("/20181201/functions/" + fnId + "/actions/invoke")
            .then().statusCode(200).extract().asString();
        assertTrue(body.contains("Hello ocir"), "the function pulled from OCIR should answer, got: " + body);
        given().when().get("/20181201/functions/" + fnId)
            .then().statusCode(200)
                .body("image", startsWith("iad.ocir.localhost.floci.io/"));
    }

    @Test
    @Order(3)
    void cleanUpStateAndSidecars() throws Exception {
        given().delete("/20181201/functions/" + fnId).then().statusCode(204);
        given().delete("/20181201/applications/" + appId).then().statusCode(204);
        // Stops fnserver and removes the registry container and volume via Resettable.
        given().post("/_floci-oci/state/reset").then().statusCode(200);
        docker("rmi", "-f", pushed);
    }

    public static class RealFunctionsAndOcirProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.functions.mock", "false",
                    "floci-oci.services.artifacts.mock", "false");
        }

        @Override
        public String getConfigProfile() {
            return "functions-docker";
        }
    }
}
