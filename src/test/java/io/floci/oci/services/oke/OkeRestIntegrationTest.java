package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestRegions;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OkeRestIntegrationTest {

    private static String compartment;
    private static final String VCN = "ocid1.vcn.oc1.iad.restvcn";
    private static final String USER = "ocid1.user.oc1..restwebhookuser";

    @Inject
    EmulatorConfig config;

    @Inject
    WorkRequestService workRequests;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("oketestrest");
        }
    }

    @Test
    void testOkeClusterAndNodePoolLifecycle() {
        // 1. Create Cluster
        String createWorkRequest = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", compartment,
                "name", "rest-cluster",
                "vcnId", VCN,
                "kubernetesVersion", "v1.30.1"
            ))
            .when().post("/20180222/clusters")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body(emptyString())
                .extract().header("opc-work-request-id");
        String clusterId = OkeWorkRequests.createdId(createWorkRequest);

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
            .queryParam("compartmentId", compartment)
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
        String createPoolWorkRequest = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", compartment,
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
                .body(emptyString())
                .extract().header("opc-work-request-id");
        String nodePoolId = OkeWorkRequests.createdId(createPoolWorkRequest);

        // 6. Get Node Pool
        given()
            .when().get("/20180222/nodePools/{id}", nodePoolId)
            .then()
                .statusCode(200)
                .body("id", equalTo(nodePoolId))
                .body("quantityPerSubnet", equalTo(2))
                .body("systemTags", equalTo(Map.of()))
                .body("timeCreated", nullValue());

        given()
            .queryParam("compartmentId", compartment)
            .queryParam("clusterId", clusterId)
            .when().get("/20180222/nodePools")
            .then()
                .statusCode(200)
                .body("[0].id", equalTo(nodePoolId))
                .body("[0].systemTags", equalTo(Map.of()));

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
        String clusterId = createCluster("exec-cluster");

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
        String clusterId = createCluster("dto-cluster");

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
        String clusterId = createCluster("webhook-cluster");
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
        String clusterId = createCluster("webhook-reject");
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

    @Test
    void tokenWebhookAuthenticatesATokenForAClusterInAnotherRegion() {
        TestRegions.subscribe(config.defaultTenancyId(), "PHX");
        String phoenix = "containerengine.us-phoenix-1.oci.oraclecloud.com";
        String createWorkRequest = given()
            .header("Host", phoenix)
            .contentType(ContentType.JSON)
            .body(Map.of("compartmentId", compartment, "name", "phoenix-cluster", "vcnId", VCN))
            .when().post("/20180222/clusters")
            .then().statusCode(202)
            .extract().header("opc-work-request-id");
        String clusterId = OkeWorkRequests.createdId(phoenix, createWorkRequest);
        assertTrue(clusterId.startsWith("ocid1.cluster.oc1.phx."), clusterId);
        String tenancy = config.defaultTenancyId();
        String token = ClusterTokenMinter.mint("us-phoenix-1", clusterId, tenancy, USER, Instant.now());

        given()
            .contentType(ContentType.JSON)
            .body(tokenReview("authentication.k8s.io/v1", token))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}", tenancy, clusterId)
            .then()
                .statusCode(200)
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(USER));
    }

    @Test
    void aFailedWorkRequestServesItsErrorsAndKeepsThemOffTheWorkRequest() {
        String workRequestId = workRequests.inProgress("oke", "CLUSTER_CREATE", compartment, List.of());
        workRequests.finish(config.defaultTenancyId(), config.defaultRegion(), workRequestId, "FAILED",
                List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", "The k3s sidecar exited")));

        given()
            .when().get("/20180222/workRequests/{id}/errors", workRequestId)
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("size()", equalTo(1))
                .body("[0].code", equalTo("InternalServerError"))
                .body("[0].message", equalTo("The k3s sidecar exited"))
                .body("[0].timestamp", notNullValue());
        given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("$", not(hasKey("errors")));
    }

    private static Map<String, Object> tokenReview(String apiVersion, String token) {
        return Map.of("apiVersion", apiVersion, "kind", "TokenReview", "spec", Map.of("token", token));
    }

    private static String createCluster(String name) {
        return OkeWorkRequests.createdId(given()
            .contentType(ContentType.JSON)
            .body(Map.of("compartmentId", compartment, "name", name, "vcnId", VCN))
            .when().post("/20180222/clusters")
            .then().statusCode(202)
            .extract().header("opc-work-request-id"));
    }

    @Test
    void clusterSettingsReadBackAndEndpointConfigActions() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("name", "rest-settings-cluster");
        details.put("vcnId", VCN);
        details.put("kubernetesVersion", "v1.30.1");
        details.put("type", "ENHANCED_CLUSTER");
        details.put("endpointConfig", Map.of("subnetId", "ocid1.subnet.oc1.iad.restapi", "isPublicIpEnabled", false));
        details.put("clusterPodNetworkOptions", List.of(Map.of("cniType", "FLANNEL_OVERLAY")));
        details.put("options", Map.of("kubernetesNetworkConfig",
                Map.of("podsCidr", "10.244.0.0/16", "servicesCidr", "10.96.0.0/16"),
                "serviceLbSubnetIds", List.of("ocid1.subnet.oc1.iad.restlb")));
        details.put("imagePolicyConfig", Map.of("isPolicyEnabled", false));
        String clusterId = OkeWorkRequests.createdId(given().contentType(ContentType.JSON).body(details)
            .when().post("/20180222/clusters")
            .then().statusCode(202).extract().header("opc-work-request-id"));

        String etag = given().when().get("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("type", equalTo("ENHANCED_CLUSTER"))
                .body("endpointConfig.subnetId", equalTo("ocid1.subnet.oc1.iad.restapi"))
                .body("endpointConfig.isPublicIpEnabled", equalTo(false))
                .body("clusterPodNetworkOptions[0].cniType", equalTo("FLANNEL_OVERLAY"))
                .body("options.kubernetesNetworkConfig.podsCidr", equalTo("10.244.0.0/16"))
                .body("options.serviceLbSubnetIds[0]", equalTo("ocid1.subnet.oc1.iad.restlb"))
                .body("imagePolicyConfig.isPolicyEnabled", equalTo(false))
                .body("availableKubernetesUpgrades.size()", equalTo(0))
                .extract().header("etag");

        given().contentType(ContentType.JSON).header("if-match", etag)
                .body(Map.of("freeformTags", Map.of("team", "rest"), "type", "ENHANCED_CLUSTER"))
            .when().put("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        String workRequestId = given().contentType(ContentType.JSON).body(Map.of("isPublicIpEnabled", true))
            .when().post("/20180222/clusters/{id}/actions/updateEndpointConfig", clusterId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given().when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("operationType", equalTo("CLUSTER_UPDATE"))
                .body("resources[0].entityType", equalTo("cluster"))
                .body("resources[0].actionType", equalTo("UPDATED"));
        given().when().get("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(200)
                .body("freeformTags.team", equalTo("rest"))
                .body("endpointConfig.isPublicIpEnabled", equalTo(true))
                .body("endpointConfig.subnetId", equalTo("ocid1.subnet.oc1.iad.restapi"));
        given().contentType(ContentType.JSON).body(Map.of("type", "HUGE_CLUSTER"))
            .when().put("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
        given().contentType(ContentType.JSON).body(Map.of())
            .when().post("/20180222/clusters/{id}/actions/migrateToNativeVcn", clusterId)
            .then().statusCode(400).body("code", equalTo("MissingParameter"));

        given().when().delete("/20180222/clusters/{id}", clusterId).then().statusCode(202);
    }
}
