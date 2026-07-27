package io.floci.oci.services.streaming;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StreamingDisabledRestIntegrationTest.DisabledStreamingProfile.class)
class StreamingDisabledRestIntegrationTest {

    @Test
    void disabledStreamingReturns503WithOciError() {
        given()
            .queryParam("compartmentId", "ocid1.compartment.oc1..x")
            .when().get("/20180418/streams")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service streaming is not enabled."));
    }

    public static class DisabledStreamingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.streaming.enabled", "false");
        }
    }
}
