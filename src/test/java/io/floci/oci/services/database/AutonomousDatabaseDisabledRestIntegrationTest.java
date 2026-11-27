package io.floci.oci.services.database;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(AutonomousDatabaseDisabledRestIntegrationTest.DisabledDatabaseProfile.class)
class AutonomousDatabaseDisabledRestIntegrationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000";

    @Test
    void disabledDatabaseReturns503() {
        given()
            .queryParam("compartmentId", TENANCY)
            .when().get("/20160918/autonomousDatabases")
            .then()
                .statusCode(503)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service database is not enabled."));
    }

    @Test
    void identityOnTheSameApiRootStaysAvailable() {
        given()
            .queryParam("compartmentId", TENANCY)
            .when().get("/20160918/compartments")
            .then()
                .statusCode(200);
    }

    public static class DisabledDatabaseProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.database.enabled", "false");
        }
    }
}
