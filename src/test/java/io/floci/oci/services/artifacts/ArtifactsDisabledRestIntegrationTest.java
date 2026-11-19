package io.floci.oci.services.artifacts;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ArtifactsDisabledRestIntegrationTest.DisabledArtifactsProfile.class)
class ArtifactsDisabledRestIntegrationTest {

    @Test
    void disabledArtifactsReturns503WithOciError() {
        given()
            .when().get("/20160918/container/repositories")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service artifacts is not enabled."));
    }

    public static class DisabledArtifactsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.artifacts.enabled", "false");
        }
    }
}
