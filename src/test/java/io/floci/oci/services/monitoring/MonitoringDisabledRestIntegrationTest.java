package io.floci.oci.services.monitoring;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(MonitoringDisabledRestIntegrationTest.DisabledMonitoringProfile.class)
class MonitoringDisabledRestIntegrationTest {

    @Test
    void disabledMonitoringReturns503WithOciError() {
        given()
            .when().get("/20180401/alarms")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service monitoring is not enabled."));
        given().contentType("application/json").body("{}")
            .when().post("/20180401/metrics")
            .then().statusCode(503);
    }

    public static class DisabledMonitoringProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.monitoring.enabled", "false");
        }
    }
}
