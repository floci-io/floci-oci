package io.floci.oci.services.logging;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(LoggingDisabledRestIntegrationTest.DisabledLoggingProfile.class)
class LoggingDisabledRestIntegrationTest {

    @Test
    void disabledLoggingReturns503WithOciError() {
        given()
            .when().get("/20200531/logGroups")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service logging is not enabled."));
        given().contentType("application/json").body("{}")
            .when().post("/20200831/logs/ocid1.log.oc1.iad.x/actions/push")
            .then().statusCode(503);
        given().contentType("application/json").body("{}")
            .when().post("/20190909/search")
            .then().statusCode(503);
    }

    public static class DisabledLoggingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.logging.enabled", "false");
        }
    }
}
