package io.floci.oci.services.oke;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code kubeconfig-auth: token}: the kubeconfig carries each cluster's static bearer token. */
@QuarkusTest
@TestProfile(OkeTokenKubeconfigRestIntegrationTest.TokenKubeconfigProfile.class)
class OkeTokenKubeconfigRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..oketokenmode";
    private static final String VCN = "ocid1.vcn.oc1.iad.tokenmodevcn";

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
            .body(Map.of("tokenVersion", "2.0.0"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();
        Matcher token = Pattern.compile("token: (\\S+)").matcher(kubeconfig);
        assertTrue(token.find(), "kubeconfig must carry a bearer token");
        assertFalse(kubeconfig.contains("exec:"));
        return token.group(1);
    }

    public static class TokenKubeconfigProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.kubeconfig-auth", "token");
        }
    }
}
