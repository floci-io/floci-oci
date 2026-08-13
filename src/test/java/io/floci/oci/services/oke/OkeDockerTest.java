package io.floci.oci.services.oke;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane for OKE: flips {@code mock=false}, starts real rancher/k3s sidecars,
 * and tests cluster readiness and cleanup. Skips cleanly when no Docker daemon is accessible.
 */
@QuarkusTest
@TestProfile(OkeDockerTest.RealOkeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OkeDockerTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..okedockercompartment";
    private static final String VCN = "ocid1.vcn.oc1.iad.dockervcn";

    private String clusterId;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
        }
        assumeTrue(dockerAvailable, "Docker engine / Podman machine not responding — skipping real OKE Docker test");
    }

    @Test
    @Order(1)
    void test1_createRealCluster() {
        clusterId = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", COMPARTMENT,
                "name", "docker-oke-cluster",
                "vcnId", VCN,
                "kubernetesVersion", "v1.30.1"
            ))
            .when().post("/20180222/clusters")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .body("name", equalTo("docker-oke-cluster"))
                .extract().path("id");

        org.junit.jupiter.api.Assertions.assertNotNull(clusterId);
    }

    @Test
    @Order(2)
    void test2_getRealCluster() {
        given()
            .when().get("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(200)
                .body("id", equalTo(clusterId))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("endpoints.kubernetes", notNullValue());
    }

    @Test
    @Order(3)
    void test3_deleteRealCluster() {
        given()
            .when().delete("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);
    }

    public static class RealOkeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.mock", "false");
        }
    }
}
