package io.floci.oci.services.artifacts;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code signature-verification=strict}: the OCIR token endpoint logs a user in with an Identity auth
 * token. Signatures are not required here, so the unsigned Identity calls reach the default tenancy.
 */
@QuarkusTest
@TestProfile(OcirLoginStrictIntegrationTest.StrictProfile.class)
class OcirLoginStrictIntegrationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String TOKEN_PATH = "/20180419/docker/token";

    private static String namespace() {
        return given().when().get("/n").then().statusCode(200).extract().asString().replace("\"", "");
    }

    @Test
    void anActiveAuthTokenLogsTheUserIn() {
        String userName = "ocir-" + System.nanoTime();
        String userId = given().contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", userName, "description", "docker"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
        Map<String, Object> created = given().contentType("application/json").body(Map.of("description", "docker"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200).extract().as(Map.class);
        String token = (String) created.get("token");
        String namespace = namespace();

        given().auth().preemptive().basic(namespace + "/" + userName, token)
            .when().get(TOKEN_PATH)
            .then().statusCode(200)
                .body("token", notNullValue())
                .body("access_token", notNullValue())
                .body("expires_in", equalTo(3600));
        given().auth().preemptive().basic(namespace + "/oracleidentitycloudservice/" + userName, token)
            .when().get(TOKEN_PATH)
            .then().statusCode(200);

        given().auth().preemptive().basic(namespace + "/" + userName, token + "x")
            .when().get(TOKEN_PATH)
            .then().statusCode(401)
                .body("errors[0].code", equalTo("UNAUTHORIZED"))
                .body("errors[0].message", equalTo("the username or auth token is not valid"));

        given().when().delete("/20160918/users/{userId}/authTokens/{authTokenId}", userId, created.get("id"))
            .then().statusCode(204);
        given().auth().preemptive().basic(namespace + "/" + userName, token)
            .when().get(TOKEN_PATH)
            .then().statusCode(401).body("errors[0].code", equalTo("UNAUTHORIZED"));
    }

    @Test
    void aLoginWithoutCredentialsIsRefused() {
        given().when().get(TOKEN_PATH)
            .then().statusCode(401)
                .body("errors[0].code", equalTo("UNAUTHORIZED"))
                .body("errors[0].message",
                        equalTo("Basic credentials <tenancy-namespace>/<username> and an auth token are required"));
    }

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.auth.signature-verification", "strict");
        }
    }
}
