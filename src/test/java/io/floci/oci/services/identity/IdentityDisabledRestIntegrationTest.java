package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IdentityDisabledRestIntegrationTest.DisabledIdentityProfile.class)
class IdentityDisabledRestIntegrationTest {

    @Test
    void disabledIdentityReturns503WithOciError() {
        given()
            .when().get("/20160918/users")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service identity is not enabled."));
    }

    public static class DisabledIdentityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.identity.enabled", "false");
        }
    }
}
