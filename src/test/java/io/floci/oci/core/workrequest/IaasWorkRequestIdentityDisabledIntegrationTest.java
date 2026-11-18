package io.floci.oci.core.workrequest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Networking work requests are polled on Identity's path, so they must not depend on Identity being enabled. */
@QuarkusTest
@TestProfile(IaasWorkRequestIdentityDisabledIntegrationTest.IdentityDisabledProfile.class)
class IaasWorkRequestIdentityDisabledIntegrationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000";

    @Test
    void networkingWorkRequestIsPolledWithIdentityDisabled() {
        given().when().get("/20160918/compartments/" + TENANCY)
            .then().statusCode(503).body("code", equalTo("ServiceUnavailable"));

        String vcnId = given().contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "cidrBlocks", List.of("10.80.0.0/16")))
            .when().post("/20160918/vcns")
            .then().statusCode(200).extract().path("id");
        String workRequestId = given().contentType("application/json")
                .body(Map.of("compartmentId", TENANCY))
            .when().post("/20160918/vcns/" + vcnId + "/actions/changeCompartment")
            .then().statusCode(202).extract().header("opc-work-request-id");

        given().when().get("/20160918/workRequests/" + workRequestId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].identifier", equalTo(vcnId));
    }

    public static class IdentityDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.identity.enabled", "false");
        }
    }
}
