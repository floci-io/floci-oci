package io.floci.oci.testing;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** Compartment setup for REST tests: other services only accept a compartment that exists. */
public final class TestCompartments {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    private TestCompartments() {
    }

    /** Creates a compartment under the test tenancy and returns its OCID. */
    public static String create(String name) {
        return given().contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", name + "-" + System.nanoTime(),
                        "description", "created for " + name))
            .when().post("/20160918/compartments")
            .then().statusCode(200)
                .extract().path("id");
    }
}
