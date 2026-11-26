package io.floci.oci.services.mysql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(MysqlDisabledRestIntegrationTest.DisabledMysqlProfile.class)
class MysqlDisabledRestIntegrationTest {

    @Test
    void disabledMysqlReturns503() {
        given()
            .when().get("/20190415/dbSystems")
            .then()
                .statusCode(503)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service mysql is not enabled."));
    }

    @Test
    void disabledMysqlAlsoDisablesTheCatalog() {
        given()
            .when().get("/20190415/shapes")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledMysqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.mysql.enabled", "false");
        }
    }
}
