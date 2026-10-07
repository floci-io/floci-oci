package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OkeRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..oketestrest";
    private static final String VCN = "ocid1.vcn.oc1.iad.restvcn";
    private static final String USER = "ocid1.user.oc1..restwebhookuser";

    @Inject
    EmulatorConfig config;

    @Test
    void testOkeClusterAndNodePoolLifecycle() {
        // 1. Create Cluster
        String clusterId = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", COMPARTMENT,
                "name", "rest-cluster",
                "vcnId", VCN,
                "kubernetesVersion", "v1.30.1"
            ))
            .when().post("/20180222/clusters")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body("name", equalTo("rest-cluster"))
                .extract().path("id");

        assertNotNull(clusterId);

        // 2. Get Cluster
        given()
            .when().get("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("id", equalTo(clusterId))
                .body("name", equalTo("rest-cluster"))
                .body("metadata.timeCreated", notNullValue())
                .body("hostPort", nullValue())
                .body("timeCreated", nullValue());

        // 3. List Clusters
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20180222/clusters")
            .then()
                .statusCode(200)
                .body("[0].id", equalTo(clusterId));

        // 4. Kubeconfig Generation
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenType", "BASIC"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then()
                .statusCode(200)
                .header("Content-Type", containsString("application/x-yaml"))
                .body(containsString("apiVersion: v1"))
                .body(containsString("- " + clusterId));

        // 5. Create Node Pool
        String nodePoolId = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", COMPARTMENT,
                "clusterId", clusterId,
                "name", "pool-rest",
                "kubernetesVersion", "v1.30.1",
                "nodeShape", "VM.Standard.E4.Flex",
                "quantityPerSubnet", 2
            ))
            .when().post("/20180222/nodePools")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .body("name", equalTo("pool-rest"))
                .extract().path("id");

        // 6. Get Node Pool
        given()
            .when().get("/20180222/nodePools/{id}", nodePoolId)
            .then()
                .statusCode(200)
                .body("id", equalTo(nodePoolId))
                .body("quantityPerSubnet", equalTo(2))
                .body("timeCreated", nullValue());

        // 7. Cluster and Node Pool Options
        given()
            .when().get("/20180222/clusterOptions/all")
            .then()
                .statusCode(200)
                .body("kubernetesVersions", notNullValue());

        given()
            .when().get("/20180222/nodePoolOptions/all")
            .then()
                .statusCode(200)
                .body("shapes", notNullValue());

        // 8. Teardown Node Pool and Cluster
        given()
            .when().delete("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(202);

        given()
            .when().delete("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);
    }

    @Test
    void kubeconfigIsTheRealOkeExecShapeByDefault() {
        String clusterId = JsonPath.from(createCluster("exec-cluster")).getString("id");

        String kubeconfig = given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenVersion", "2.0.0"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();

        assertTrue(kubeconfig.contains("""
                      command: oci
                """), kubeconfig);
        assertTrue(kubeconfig.contains("""
                      - generate-token
                      - --cluster-id
                      - %s
                      - --region
                      - %s
                """.formatted(clusterId, config.defaultRegion())), kubeconfig);
        assertFalse(kubeconfig.contains("token: "), "exec mode must not leak the static token");
    }

    @Test
    void createKubeconfigAcceptsTheSdkRequestModel() {
        String clusterId = JsonPath.from(createCluster("dto-cluster")).getString("id");

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenVersion", "2.0.0", "expiration", 2592000, "endpoint", "PUBLIC_ENDPOINT"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200);
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("endpoint", "private_endpoint"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200);
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("endpoint", "BOGUS"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void tokenWebhookAuthenticatesAGenerateTokenTokenForTheCluster() {
        String clusterId = JsonPath.from(createCluster("webhook-cluster")).getString("id");
        String tenancy = config.defaultTenancyId();
        String token = ClusterTokenMinter.mint(config.defaultRegion(), clusterId, tenancy, USER, Instant.now());

        given()
            .contentType(ContentType.JSON)
            .body(tokenReview("authentication.k8s.io/v1", token))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}", tenancy, clusterId)
            .then()
                .statusCode(200)
                .body("apiVersion", equalTo("authentication.k8s.io/v1"))
                .body("kind", equalTo("TokenReview"))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(USER))
                .body("status.user.groups", hasItem("system:masters"));
    }

    @Test
    void tokenWebhookEchoesV1beta1AndRejectsWithAuthenticatedFalse() {
        String clusterId = JsonPath.from(createCluster("webhook-reject")).getString("id");
        String tenancy = config.defaultTenancyId();
        String otherCluster = ClusterTokenMinter.mint(config.defaultRegion(),
                "ocid1.cluster.oc1.iad.notthisone", tenancy, USER, Instant.now());

        given()
            .contentType(ContentType.JSON)
            .body(tokenReview("authentication.k8s.io/v1beta1", otherCluster))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}", tenancy, clusterId)
            .then()
                .statusCode(200)
                .body("apiVersion", equalTo("authentication.k8s.io/v1beta1"))
                .body("status.authenticated", equalTo(false))
                .body("status.user", nullValue());
        given()
            .contentType(ContentType.JSON)
            .body(tokenReview("authentication.k8s.io/v1",
                    ClusterTokenMinter.mint(config.defaultRegion(), clusterId, tenancy, USER, Instant.now())))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}", "ocid1.tenancy.oc1..wrongscope", clusterId)
            .then()
                .statusCode(200)
                .body("status.authenticated", equalTo(false));
    }

    private static Map<String, Object> tokenReview(String apiVersion, String token) {
        return Map.of("apiVersion", apiVersion, "kind", "TokenReview", "spec", Map.of("token", token));
    }

    private static String createCluster(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of("compartmentId", COMPARTMENT, "name", name, "vcnId", VCN))
            .when().post("/20180222/clusters")
            .then().statusCode(202)
            .extract().asString();
    }
}
