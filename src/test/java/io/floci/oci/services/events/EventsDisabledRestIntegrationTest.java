package io.floci.oci.services.events;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(EventsDisabledRestIntegrationTest.DisabledEventsProfile.class)
class EventsDisabledRestIntegrationTest {

    @Test
    void disabledEventsReturns503WithOciError() {
        given()
            .when().get("/20181201/rules")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service events is not enabled."));
    }

    public static class DisabledEventsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.events.enabled", "false");
        }
    }
}
