package io.floci.oci.services.queue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(QueueDisabledRestIntegrationTest.DisabledQueueProfile.class)
class QueueDisabledRestIntegrationTest {

    @Test
    void disabledQueueReturns503WithOciError() {
        given()
            .when().get("/20210201/queues")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service queue is not enabled."));
    }

    public static class DisabledQueueProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.queue.enabled", "false");
        }
    }
}
