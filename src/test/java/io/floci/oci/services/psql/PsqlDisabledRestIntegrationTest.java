package io.floci.oci.services.psql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(PsqlDisabledRestIntegrationTest.DisabledPsqlProfile.class)
class PsqlDisabledRestIntegrationTest {

    @Test
    void disabledPsqlReturns503() {
        given()
            .when().get("/20220915/shapes")
            .then()
                .statusCode(503)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service psql is not enabled."));
    }

    public static class DisabledPsqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.psql.enabled", "false");
        }
    }
}
