package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 * Real-sidecar lane for OKE: flips {@code mock=false}, starts a real rancher/k3s sidecar, and
 * walks the client path real OKE users take: the cluster goes CREATING to ACTIVE with its
 * CLUSTER_CREATE work request, the kubeconfig carries the k3s CA and an
 * {@code oci ce cluster generate-token} exec user, and a token minted the way that command mints
 * it reaches the Kubernetes API through floci-oci's token webhook. Skips cleanly when no Docker
 * daemon is accessible.
 */
@QuarkusTest
@TestProfile(OkeDockerTest.RealOkeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OkeDockerTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..okedockercompartment";
    private static final String VCN = "ocid1.vcn.oc1.iad.dockervcn";
    private static final String USER = "ocid1.user.oc1..okedockeruser";
    private static final Duration ACTIVE_TIMEOUT = Duration.ofSeconds(120);

    @Inject
    EmulatorConfig config;

    @Inject
    OkeService service;

    private String clusterId;
    private String workRequestId;
    private int apiPort;
    private KeyStore clusterCa;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine / Podman machine not responding, skipping real OKE Docker test");
    }

    @Test
    @Order(1)
    void test1_createReturnsCreatingWithAnInProgressWorkRequest() {
        Response created = given()
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
                .body("lifecycleState", equalTo("CREATING"))
                .extract().response();
        clusterId = created.path("id");
        workRequestId = created.header("opc-work-request-id");
        assertNotNull(clusterId);

        given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].identifier", equalTo(clusterId));
    }

    @Test
    @Order(2)
    void test2_clusterBecomesActiveOnceTheK3sApiAnswers() throws InterruptedException {
        Instant deadline = Instant.now().plus(ACTIVE_TIMEOUT);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            state = given().when().get("/20180222/clusters/{id}", clusterId)
                .then().statusCode(200).extract().path("lifecycleState");
            if (!"CREATING".equals(state)) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals("ACTIVE", state, "the k3s sidecar must run `k3s server` and answer /readyz");

        given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue());
    }

    @Test
    @Order(3)
    void test3_kubeconfigIsExecWithTheClusterCaAndTlsVerifies() throws Exception {
        String kubeconfig = given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenVersion", "2.0.0"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();
        assertTrue(kubeconfig.contains("command: oci"), kubeconfig);
        assertTrue(kubeconfig.contains("- generate-token"), kubeconfig);

        Matcher server = Pattern.compile("server: (\\S+)").matcher(kubeconfig);
        Matcher ca = Pattern.compile("certificate-authority-data: (\\S+)").matcher(kubeconfig);
        assertTrue(server.find() && ca.find(), "kubeconfig must carry a server and the cluster CA");
        apiPort = URI.create(server.group(1)).getPort();
        clusterCa = trustStore(ca.group(1));

        assertEquals(401, kubeApi("Bearer not-a-token", "/api/v1/namespaces"),
                "TLS must verify against the kubeconfig CA with no relaxed validation");
    }

    @Test
    @Order(4)
    void test4_generateTokenTokensReachTheApiThroughTheWebhook() {
        String tenancy = config.defaultTenancyId();
        String token = ClusterTokenMinter.mint(config.defaultRegion(), clusterId, tenancy, USER, Instant.now());
        String otherCluster = ClusterTokenMinter.mint(config.defaultRegion(),
                "ocid1.cluster.oc1.iad.notthiscluster", tenancy, USER, Instant.now());

        assertEquals(200, kubeApi("Bearer " + token, "/api/v1/namespaces"),
                "k3s must send the generate-token token to floci-oci's webhook and accept its verdict");
        assertEquals(401, kubeApi("Bearer " + otherCluster, "/api/v1/namespaces"));
    }

    @Test
    @Order(5)
    void test5_theStaticTokenStillWorks() {
        String staticToken = service.findCluster(config.defaultTenancyId(), clusterId).orElseThrow().getApiToken();

        assertEquals(200, kubeApi("Bearer " + staticToken, "/api/v1/namespaces"));
    }

    @Test
    @Order(6)
    void test6_deleteRealCluster() {
        given()
            .when().delete("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);
    }

    private int kubeApi(String bearer, String path) {
        return given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", bearer)
            .when().get(path)
            .then().extract().statusCode();
    }

    private static KeyStore trustStore(String base64Pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        int index = 0;
        for (Certificate certificate : factory.generateCertificates(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64Pem)))) {
            keyStore.setCertificateEntry("k3s-ca-" + index++, certificate);
        }
        return keyStore;
    }

    /**
     * Real mode, with the test server listening on the same port as {@code floci-oci.port}: k3s
     * calls the token webhook at that port, as it does outside tests, where the two never differ.
     */
    public static class RealOkeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String port = String.valueOf(freePort());
            return Map.of(
                    "floci-oci.services.oke.mock", "false",
                    "quarkus.http.test-port", port,
                    "floci-oci.port", port);
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("No free port for the OKE Docker test", e);
            }
        }
    }
}
