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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private static final Duration API_SERVER_READY_TIMEOUT = Duration.ofSeconds(90);

    private String clusterId;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
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

        assertNotNull(clusterId);
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
    void test3_kubernetesApiAcceptsTheKubeconfigToken() throws InterruptedException {
        String kubeconfig = given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenType", "BASIC"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();
        Matcher server = Pattern.compile("server: (\\S+)").matcher(kubeconfig);
        Matcher token = Pattern.compile("token: (\\S+)").matcher(kubeconfig);
        assertTrue(server.find() && token.find(), "kubeconfig must carry a server and a token");
        int apiPort = URI.create(server.group(1)).getPort();
        String bearer = "Bearer " + token.group(1);

        int readyz = awaitReadyz(apiPort, bearer);
        assertEquals(200, readyz, "k3s API server never became ready: the sidecar must run `k3s server`");

        assertEquals(200, kubeApi(apiPort, bearer, "/api/v1/namespaces"));
        assertEquals(401, kubeApi(apiPort, "Bearer not-the-cluster-token", "/api/v1/namespaces"));
    }

    @Test
    @Order(4)
    void test4_deleteRealCluster() {
        given()
            .when().delete("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);
    }

    private static int awaitReadyz(int apiPort, String bearer) throws InterruptedException {
        Instant deadline = Instant.now().plus(API_SERVER_READY_TIMEOUT);
        int status = -1;
        while (Instant.now().isBefore(deadline)) {
            try {
                status = kubeApi(apiPort, bearer, "/readyz");
                if (status == 200) {
                    return status;
                }
            } catch (Exception expected) {
                // The API server is not listening yet: keep polling until the deadline.
            }
            Thread.sleep(2000);
        }
        return status;
    }

    private static int kubeApi(int apiPort, String bearer, String path) {
        return given()
            .relaxedHTTPSValidation()
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", bearer)
            .when().get(path)
            .then().extract().statusCode();
    }

    public static class RealOkeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.mock", "false");
        }
    }
}
