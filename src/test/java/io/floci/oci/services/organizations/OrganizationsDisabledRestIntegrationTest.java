package io.floci.oci.services.organizations;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(OrganizationsDisabledRestIntegrationTest.DisabledOrganizationsProfile.class)
class OrganizationsDisabledRestIntegrationTest {

    @Test
    void disabledOrganizationsReturns503WithOciError() {
        given()
            .when().get("/20230401/organizations")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service organizations is not enabled."));
    }

    public static class DisabledOrganizationsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.organizations.enabled", "false");
        }
    }
}
