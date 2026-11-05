package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OrdersRestIntegrationTest {

    @Inject
    EmulatorConfig config;

    @Test
    void getAndActivateAnOrder() {
        String token = OrdersServiceTest.jwt("{\"orderNumber\":\"rest-" + System.nanoTime()
                + "\",\"adminEmail\":\"buyer@example.com\"}");

        given().when().get("/20230401/orders/" + token)
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("adminEmail", equalTo("buyer@example.com"))
                .body("orderState", equalTo("PENDING"))
                .body("subscriptions[0].service", notNullValue());

        String workRequestId = given().contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId()))
            .when().post("/20230401/orders/" + token + "/actions/activate")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        given().when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("ACTIVATE_ORDER_EXISTING_TENANCY"))
                .body("status", equalTo("SUCCEEDED"));
        given().when().get("/20230401/orders/" + token)
            .then().statusCode(200).body("orderState", equalTo("ACTIVATED"));

        given().contentType("application/json").body(Map.of("compartmentId", config.defaultTenancyId()))
            .when().post("/20230401/orders/" + token + "/actions/activate")
            .then().statusCode(409).body("code", equalTo("Conflict"));
    }

    @Test
    void activatingIntoAnotherTenancyIs404() {
        String token = OrdersServiceTest.jwt("{\"orderNumber\":\"foreign-" + System.nanoTime() + "\"}");

        given().contentType("application/json").body(Map.of("compartmentId", "ocid1.tenancy.oc1..someoneelse"))
            .when().post("/20230401/orders/" + token + "/actions/activate")
            .then()
                .statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void aTokenThatIsNotAJwtIsRejected() {
        given().when().get("/20230401/orders/not-a-token")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
    }
}
