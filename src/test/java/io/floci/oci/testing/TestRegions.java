package io.floci.oci.testing;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/** Region setup for REST tests: a tenancy has to subscribe a region before using its endpoints. */
public final class TestRegions {

    private TestRegions() {
    }

    /** Subscribes {@code tenancyId} to the region with {@code regionKey}; a no-op when it already is. */
    public static void subscribe(String tenancyId, String regionKey) {
        given().contentType("application/json")
                .body(Map.of("regionKey", regionKey))
            .when().post("/20160918/tenancies/" + tenancyId + "/regionSubscriptions")
            .then().statusCode(anyOf(is(200), is(409)));
    }
}
