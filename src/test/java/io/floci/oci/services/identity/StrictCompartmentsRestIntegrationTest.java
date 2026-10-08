package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(StrictCompartmentsRestIntegrationTest.StrictCompartmentsProfile.class)
class StrictCompartmentsRestIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String UNKNOWN = "ocid1.compartment.oc1..itnosuchcompartment";

    @Test
    void unknownCompartmentInBodyIs400RelatedResource() {
        given()
                .contentType("application/json")
                .body(Map.of("displayName", "strict-q", "compartmentId", UNKNOWN))
            .when().post("/20210201/queues")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
    }

    @Test
    void unknownCompartmentInQueryIs404() {
        given().queryParam("compartmentId", UNKNOWN)
            .when().get("/20180418/streams")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void existingCompartmentIsAccepted() {
        String compartmentId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", "strict-" + System.nanoTime(),
                        "description", "strict"))
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().path("id");

        given()
                .contentType("application/json")
                .body(Map.of("displayName", "strict-q", "compartmentId", compartmentId))
            .when().post("/20210201/queues")
            .then().statusCode(202);

        given().queryParam("compartmentId", TENANCY)
            .when().get("/20180418/streams")
            .then().statusCode(200);
    }

    public static class StrictCompartmentsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.identity.strict-compartments", "true");
        }
    }
}
