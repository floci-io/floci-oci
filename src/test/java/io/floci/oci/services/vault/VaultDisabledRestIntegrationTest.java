package io.floci.oci.services.vault;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(VaultDisabledRestIntegrationTest.DisabledVaultProfile.class)
class VaultDisabledRestIntegrationTest {

    @Test
    void disabledVaultReturns503OnBothPlanes() {
        given()
            .queryParam("compartmentId", "ocid1.compartment.oc1..x")
            .when().get("/20180608/secrets")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service vault is not enabled."));

        given()
            .when().get("/20190301/secretbundles/ocid1.vaultsecret.oc1.iad.x")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledVaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.vault.enabled", "false");
        }
    }
}
