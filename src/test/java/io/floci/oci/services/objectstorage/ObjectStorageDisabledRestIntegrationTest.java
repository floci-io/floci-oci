package io.floci.oci.services.objectstorage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ObjectStorageDisabledRestIntegrationTest.DisabledObjectStorageProfile.class)
class ObjectStorageDisabledRestIntegrationTest {

    @Test
    void disabledObjectStorageReturns503WithOciError() {
        given()
            .when().get("/n")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service objectstorage is not enabled."));
    }

    @Test
    void theS3CompatibilityRootIsDisabledToo() {
        given()
            .when().get("/_s3compat/floci-test")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    @Test
    void anS3HostNeverReachesANativeRoute() {
        given().header("Host", "floci-test.compat.objectstorage.us-ashburn-1.localhost.floci.io")
                .queryParam("X-Amz-Credential", "anything/20261118/us-ashburn-1/s3/aws4_request")
            .when().get("/20160918/users")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"));
    }

    public static class DisabledObjectStorageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.objectstorage.enabled", "false");
        }
    }
}
