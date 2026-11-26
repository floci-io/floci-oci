package io.floci.oci.services.containerinstances;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(ContainerInstancesDisabledRestIntegrationTest.DisabledContainerInstancesProfile.class)
class ContainerInstancesDisabledRestIntegrationTest {

    @Test
    void disabledContainerInstancesReturns503() {
        given()
            .queryParam("compartmentId", "ocid1.tenancy.oc1..x")
            .when().get("/20210415/containerInstances")
            .then()
                .statusCode(503)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service containerinstances is not enabled."));
    }

    @Test
    void disabledContainerInstancesAlsoDisablesContainersAndWorkRequests() {
        given().when().get("/20210415/containers/ocid1.computecontainer.oc1.iad.x")
            .then().statusCode(503).body("code", equalTo("ServiceUnavailable"));
        given().when().get("/20210415/workRequests/ocid1.coreservicesworkrequest.oc1..x")
            .then().statusCode(503).body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledContainerInstancesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.containerinstances.enabled", "false");
        }
    }
}
