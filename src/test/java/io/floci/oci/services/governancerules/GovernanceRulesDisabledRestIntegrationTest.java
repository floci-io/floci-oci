package io.floci.oci.services.governancerules;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GovernanceRulesDisabledRestIntegrationTest.DisabledGovernanceRulesProfile.class)
class GovernanceRulesDisabledRestIntegrationTest {

    @Test
    void disabledGovernanceRulesReturns503WithOciError() {
        given().queryParam("compartmentId", "ocid1.tenancy.oc1..any")
            .when().get("/20220504/governanceRules")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service governancerules is not enabled."));
    }

    public static class DisabledGovernanceRulesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.governancerules.enabled", "false");
        }
    }
}
