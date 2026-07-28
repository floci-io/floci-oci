package io.floci.oci.services.functions;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(FunctionsDisabledRestIntegrationTest.DisabledFunctionsProfile.class)
class FunctionsDisabledRestIntegrationTest {

    @Test
    void disabledFunctionsReturns503OnBothPlanes() {
        given()
            .queryParam("compartmentId", "ocid1.compartment.oc1..x")
            .when().get("/20181201/applications")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service functions is not enabled."));

        given()
            .body("{}".getBytes())
            .when().post("/20181201/functions/ocid1.fnfunc.oc1.iad.x/actions/invoke")
            .then().statusCode(503);
    }

    public static class DisabledFunctionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.functions.enabled", "false");
        }
    }
}
