package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiKeyRestIntegrationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    /** {@code openssl rsa -in a.pem -pubout -outform DER | openssl md5 -c} on the fixture's private key. */
    private static final String FINGERPRINT_A = "bf:44:8c:8d:6c:12:10:7b:75:d9:32:50:fc:0a:14:e5";

    @Inject
    ApiKeyService apiKeys;

    private static String createUser() {
        return given().contentType("application/json")
                .body(Map.of("name", "it-apikey-" + System.nanoTime(), "description", "api keys"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
    }

    @Test
    void uploadListAndDelete() {
        String userId = createUser();

        String etag = given().contentType("application/json").body(Map.of("key", ApiKeyServiceTest.pem("a")))
            .when().post("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("keyId", equalTo(TENANCY + "/" + userId + "/" + FINGERPRINT_A))
                .body("fingerprint", equalTo(FINGERPRINT_A))
                .body("keyValue", equalTo(ApiKeyServiceTest.pem("a")))
                .body("userId", equalTo(userId))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("timeCreated", notNullValue())
                .extract().header("etag");

        given().when().get("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("size()", equalTo(1))
                .body("[0].fingerprint", equalTo(FINGERPRINT_A));

        given().header("if-match", "stale")
            .when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", userId, FINGERPRINT_A)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));
        given().header("if-match", etag)
            .when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", userId, FINGERPRINT_A)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().when().get("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void errorsUseTheOciErrorBody() {
        String userId = createUser();

        given().contentType("application/json").body(Map.of("key", ApiKeyServiceTest.pem("ec")))
            .when().post("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", equalTo("key must be an RSA public key in PEM format"));
        given().contentType("application/json").body(Map.of())
            .when().post("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().when().delete("/20160918/users/{userId}/apiKeys/{fingerprint}", userId, FINGERPRINT_A)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().when().get("/20160918/users/{userId}/apiKeys", "ocid1.user.oc1..missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void deletingAUserThroughTheApiRemovesItsKeysOnly() {
        String userId = createUser();
        String otherId = createUser();
        given().contentType("application/json").body(Map.of("key", ApiKeyServiceTest.pem("a")))
            .when().post("/20160918/users/{userId}/apiKeys", userId)
            .then().statusCode(200);
        given().contentType("application/json").body(Map.of("key", ApiKeyServiceTest.pem("b")))
            .when().post("/20160918/users/{userId}/apiKeys", otherId)
            .then().statusCode(200);

        given().when().delete("/20160918/users/{userId}", userId).then().statusCode(204);

        assertTrue(apiKeys.find(TENANCY, userId, FINGERPRINT_A).isEmpty());
        given().when().get("/20160918/users/{userId}/apiKeys", otherId)
            .then().statusCode(200).body("size()", equalTo(1));
    }
}
