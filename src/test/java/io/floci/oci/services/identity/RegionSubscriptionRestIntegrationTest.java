package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class RegionSubscriptionRestIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    @Test
    void unsubscribedRegionIsRejectedUntilSubscribed() {
        given().header("Host", "messaging.ap-tokyo-1.oci.oraclecloud.com")
                .queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(401)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthenticated"))
                .body("message", equalTo("The tenancy is not subscribed to region ap-tokyo-1."));

        given()
            .when().get("/20160918/tenancies/" + TENANCY + "/regionSubscriptions")
            .then().statusCode(200)
                .body("regionName", not(hasItem("ap-tokyo-1")));

        given().contentType("application/json")
                .body(Map.of("regionKey", "NRT"))
            .when().post("/20160918/tenancies/" + TENANCY + "/regionSubscriptions")
            .then().statusCode(200)
                .body("regionName", equalTo("ap-tokyo-1"));

        given().header("Host", "messaging.ap-tokyo-1.oci.oraclecloud.com")
                .queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(200);
    }

    @Test
    void homeRegionNeedsNoSubscription() {
        given().queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(200);
    }

    @Test
    void regionOutsideTheRealmIsRejected() {
        given().header("Host", "messaging.uk-gov-london-1.oci.oraclegovcloud.uk")
                .queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(401)
                .body("code", equalTo("NotAuthenticated"));
    }

    @Test
    void corsPreflightToAnUnsubscribedRegionIsNotRejected() {
        given().header("Host", "messaging.eu-zurich-1.oci.oraclecloud.com")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
            .when().options("/20210201/queues")
            .then().statusCode(not(equalTo(401)));

        given().header("Host", "messaging.eu-zurich-1.oci.oraclecloud.com")
                .queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(401)
                .body("code", equalTo("NotAuthenticated"));
    }
}
