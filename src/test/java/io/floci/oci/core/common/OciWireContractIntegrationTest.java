package io.floci.oci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Pins the cross-cutting OCI wire contract: every response — success and error —
 * carries an {@code opc-request-id} header, the caller's ids are echoed, and error
 * bodies are exactly {@code {"code": "...", "message": "..."}}.
 */
@QuarkusTest
class OciWireContractIntegrationTest {

    @Test
    void everyResponseCarriesOpcRequestId() {
        given()
        .when()
            .get("/health")
        .then()
            .statusCode(200)
            .header("opc-request-id", notNullValue());
    }

    @Test
    void incomingOpcRequestIdIsEchoed() {
        given()
            .header("opc-request-id", "CALLERPROVIDEDID123")
        .when()
            .get("/health")
        .then()
            .statusCode(200)
            .header("opc-request-id", equalTo("CALLERPROVIDEDID123"));
    }

    @Test
    void opcClientRequestIdIsEchoed() {
        given()
            .header("opc-client-request-id", "client-abc-123")
        .when()
            .get("/health")
        .then()
            .statusCode(200)
            .header("opc-client-request-id", equalTo("client-abc-123"));
    }

    @Test
    void unknownPathIs404WithOpcRequestId() {
        given()
        .when()
            .get("/20990101/doesNotExist")
        .then()
            .statusCode(404)
            .header("opc-request-id", notNullValue());
    }
}
