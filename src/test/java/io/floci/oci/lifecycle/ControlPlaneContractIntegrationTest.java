package io.floci.oci.lifecycle;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The Floci control-plane contract: the same endpoints and required fields under {@code /_floci} as every
 * other Floci emulator, with {@code /_floci-oci} and {@code /health} kept as aliases.
 */
@QuarkusTest
class ControlPlaneContractIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_floci-oci/health", "/health"})
    void healthCarriesTheRequiredFields(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("UP"))
                .body("cloud", equalTo("oci"))
                .body("version", notNullValue())
                .body("services.identity", equalTo("running"))
                .body("edition", equalTo("community"))
                .body("original_edition", equalTo("floci-always-free"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/info", "/_floci-oci/info"})
    void infoIsTheConsoleManifest(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .body("manifest_version", equalTo(1))
                .body("console_contract", equalTo(2))
                .body("cloud", equalTo("oci"))
                .body("emulator", equalTo("floci-oci"))
                .body("version", notNullValue())
                .body("edition", equalTo("community"))
                .body("original_edition", equalTo("floci-always-free"))
                .body("endpoint", startsWith("http"))
                .body("health_url", equalTo("/_floci/health"))
                .body("tls.enabled", equalTo(false))
                .body("services.identity", equalTo("enabled"))
                .body("defaults.tenancy_id", equalTo(TENANCY))
                .body("defaults.compartment_id", equalTo(TENANCY))
                .body("defaults.region", notNullValue())
                .body("defaults.namespace", notNullValue())
                .body("ui.enabled", equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/init", "/_floci-oci/init"})
    void initReportsEveryPhase(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .body("completed.keySet()", containsInAnyOrder("boot", "start", "ready", "shutdown"))
                .body("scripts.keySet()", containsInAnyOrder("boot", "start", "ready", "shutdown"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/tls-cert", "/_floci-oci/tls-cert"})
    void tlsCertExplainsWhyThereIsNoneWhenTlsIsOff(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(404)
                .contentType("application/json")
                .body("tlsEnabled", equalTo(false))
                .body("error", equalTo("TLS is not enabled"))
                .body("message", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/nope", "/_floci/state/nope", "/_floci-oci/nope", "/_floci"})
    void anUnknownControlPlanePathIsTheControlPlaneNotFound(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(404)
                .contentType("application/json")
                .header("opc-request-id", notNullValue())
                .body("error", equalTo("NotFound"))
                .body("path", equalTo(path));
    }

    @Test
    void anUnknownPathOutsideTheControlPlaneKeepsTheOciNotFound() {
        given()
            .when().get("/not-an-oci-api")
            .then()
                .statusCode(404)
                .body("code", equalTo("NotFound"))
                .body("error", nullValue());
    }

    @Test
    void anUnsubscribedRegionHostDoesNotCaptureTheControlPlane() {
        String host = "messaging.sa-santiago-1.oci.oraclecloud.com";
        given().header("Host", host)
                .queryParam("compartmentId", TENANCY)
            .when().get("/20210201/queues")
            .then().statusCode(401);

        given().header("Host", host)
            .when().get("/_floci/health")
            .then()
                .statusCode(200)
                .body("cloud", equalTo("oci"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/state/reset", "/_floci/state/nuke", "/_floci-oci/state/reset", "/_floci-oci/state/nuke"})
    void resetClearsAResourceCreatedBeforeIt(String path) {
        String compartmentId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", TENANCY, "name", "reset-" + System.nanoTime(),
                        "description", "cleared by " + path))
            .when().post("/20160918/compartments")
            .then().statusCode(200)
                .extract().path("id");

        given()
            .when().post(path)
            .then()
                .statusCode(200)
                .body("status", equalTo("OK"));

        given()
            .when().get("/20160918/compartments/{id}", compartmentId)
            .then().statusCode(404);
    }
}
