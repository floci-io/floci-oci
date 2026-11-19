package io.floci.oci.services.networking;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(NetworkingDisabledRestIntegrationTest.DisabledNetworkingProfile.class)
class NetworkingDisabledRestIntegrationTest {

    @Test
    void disabledNetworkingReturns503WithOciError() {
        given()
            .when().get("/20160918/vcns")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service networking is not enabled."));
    }

    @Test
    void identityStillServesItsPathsUnderTheSamePrefix() {
        given()
            .when().get("/20160918/regions")
            .then()
                .statusCode(200);
    }

    public static class DisabledNetworkingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.networking.enabled", "false");
        }
    }
}
