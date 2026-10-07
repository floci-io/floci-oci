package io.floci.oci.services.oke;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(OkeDisabledRestIntegrationTest.DisabledOkeProfile.class)
class OkeDisabledRestIntegrationTest {

    @Test
    void disabledOkeReturns503() {
        given()
            .queryParam("compartmentId", "ocid1.compartment.oc1..x")
            .when().get("/20180222/clusters")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service oke is not enabled."));
    }

    @Test
    void disabledOkeAlsoClosesTheTokenWebhook() {
        given()
            .contentType("application/json")
            .body(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                    "spec", Map.of("token", "x")))
            .when().post("/_floci-oci/oke/token-webhook/{tenancy}/{cluster}",
                    "ocid1.tenancy.oc1..x", "ocid1.cluster.oc1.iad.x")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledOkeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.oke.enabled", "false");
        }
    }
}
