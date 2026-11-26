package io.floci.oci.services.dns;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(DnsDisabledRestIntegrationTest.DisabledDnsProfile.class)
class DnsDisabledRestIntegrationTest {

    @Test
    void disabledDnsReturns503WithOciError() {
        given()
            .when().get("/20180115/zones")
            .then()
                .statusCode(503)
                .body("code", equalTo("ServiceUnavailable"))
                .body("message", equalTo("Service dns is not enabled."));
        given().when().get("/20180115/views").then().statusCode(503);
        given().contentType("application/json").body("{}")
            .when().patch("/20180115/zones/example.com/records")
            .then().statusCode(503);
    }

    public static class DisabledDnsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.dns.enabled", "false");
        }
    }
}
