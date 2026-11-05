package io.floci.oci.services.oke;

import static io.restassured.RestAssured.given;

/**
 * Finds what a create made the way real clients do: CreateCluster and CreateNodePool answer with
 * a work request and no body, and the work request names the new resource.
 */
final class OkeWorkRequests {

    private OkeWorkRequests() {
    }

    static String createdId(String workRequestId) {
        return given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then().statusCode(200)
            .extract().path("resources[0].identifier");
    }

    /** For a create sent to another region: the work request lives in that region. */
    static String createdId(String host, String workRequestId) {
        return given().header("Host", host)
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then().statusCode(200)
            .extract().path("resources[0].identifier");
    }
}
