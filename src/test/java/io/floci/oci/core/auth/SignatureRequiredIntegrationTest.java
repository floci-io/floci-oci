package io.floci.oci.core.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code require-signature=true}: service APIs refuse unsigned requests, while the health probe,
 * the {@code /_floci-oci} control plane and pre-authenticated requests still answer without one.
 */
@QuarkusTest
@TestProfile(SignatureRequiredIntegrationTest.RequireSignatureProfile.class)
class SignatureRequiredIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    private static String signed() {
        return "Signature version=\"1\",keyId=\"" + TENANCY + "/ocid1.user.oc1..siguser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    @Test
    void healthAnswersUnsigned() {
        given()
            .when().get("/health")
            .then().statusCode(200);
    }

    @Test
    void theControlPlaneAnswersUnsigned() {
        given()
            .when().get("/_floci-oci/health")
            .then().statusCode(200);
        given()
            .when().get("/_floci-oci/info")
            .then().statusCode(200);
    }

    @Test
    void aPreAuthenticatedRequestIsNotRefusedForMissingASignature() {
        // An unknown token is the PAR controller's own 404, not the auth filter's 401.
        given()
            .when().get("/p/unknown-token/n/floci-local/b/bucket/o/object")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"))
                .header("opc-request-id", notNullValue());
    }

    @Test
    void anUnsignedServiceRequestIsNotAuthenticated() {
        given()
            .queryParam("compartmentId", TENANCY)
            .when().get("/20160918/compartments")
            .then()
                .statusCode(401)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void aLookalikeOfTheControlPlaneStillNeedsASignature() {
        given()
            .when().get("/_floci-ociX")
            .then()
                .statusCode(401)
                .body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void aSignedServiceRequestIsServed() {
        given()
            .header("Authorization", signed())
            .queryParam("compartmentId", TENANCY)
            .when().get("/20160918/compartments")
            .then().statusCode(200);
    }

    public static class RequireSignatureProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.auth.require-signature", "true");
        }
    }
}
