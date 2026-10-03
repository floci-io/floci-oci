package io.floci.oci.services.oke;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OkeRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..oketestrest";
    private static final String VCN = "ocid1.vcn.oc1.iad.restvcn";

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
                .body(containsString("rest-cluster"));

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
    void kubeconfigTokenIsRandomAndNeverInClusterResponses() {
        String createBody = createCluster("token-cluster-a");
        String clusterId = JsonPath.from(createBody).getString("id");
        String apiToken = kubeconfigToken(clusterId);

        assertFalse(apiToken.contains(clusterId), "token must not be derivable from the cluster OCID");
        assertNotEquals(apiToken, kubeconfigToken(JsonPath.from(createCluster("token-cluster-b")).getString("id")));

        String getBody = given().when().get("/20180222/clusters/{id}", clusterId)
            .then().statusCode(200).body("apiToken", nullValue()).extract().asString();
        String listBody = given().queryParam("compartmentId", COMPARTMENT).when().get("/20180222/clusters")
            .then().statusCode(200).extract().asString();
        assertFalse(createBody.contains(apiToken));
        assertFalse(getBody.contains(apiToken));
        assertFalse(listBody.contains(apiToken));
    }

    private static String createCluster(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of("compartmentId", COMPARTMENT, "name", name, "vcnId", VCN))
            .when().post("/20180222/clusters")
            .then().statusCode(202)
            .extract().asString();
    }

    private static String kubeconfigToken(String clusterId) {
        String kubeconfig = given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenType", "BASIC"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();
        Matcher token = Pattern.compile("token: (\\S+)").matcher(kubeconfig);
        assertTrue(token.find(), "kubeconfig must carry a bearer token");
        return token.group(1);
    }
}
