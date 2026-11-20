package io.floci.oci.services.notifications;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(NotificationsDisabledRestIntegrationTest.DisabledNotificationsProfile.class)
class NotificationsDisabledRestIntegrationTest {

    @Test
    void disabledNotificationsReturns503WithOciError() {
        given()
            .when().get("/20181201/topics")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service notifications is not enabled."));
        given()
            .when().get("/20181201/subscriptions")
            .then().statusCode(503);
        given()
            .when().get("/_floci-oci/notifications/messages")
            .then().statusCode(503);
    }

    public static class DisabledNotificationsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.notifications.enabled", "false");
        }
    }
}
