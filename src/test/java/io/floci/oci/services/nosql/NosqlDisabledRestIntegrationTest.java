package io.floci.oci.services.nosql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(NosqlDisabledRestIntegrationTest.DisabledNosqlProfile.class)
class NosqlDisabledRestIntegrationTest {

    @Test
    void disabledNosqlReturns503WithOciError() {
        given()
            .queryParam("compartmentId", "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000")
            .when().get("/20190828/tables")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service nosql is not enabled."));
    }

    @Test
    void disabledNosqlDataPlaneReturns503() {
        given()
            .contentType("application/json")
            .body(Map.of("compartmentId", "c", "statement", "SELECT * FROM t"))
            .when().post("/20190828/query")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledNosqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.nosql.enabled", "false");
        }
    }
}
