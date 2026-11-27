package io.floci.oci.services.identity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AuthTokenRestIntegrationTest {

    private static String createUser() {
        return given().contentType("application/json")
                .body(Map.of("name", "it-authtoken-" + System.nanoTime(), "description", "auth tokens"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
    }

    @Test
    void createListUpdateAndDelete() {
        String userId = createUser();

        String tokenId = given().contentType("application/json").body(Map.of("description", "docker login"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("token", hasLength(AuthTokenService.TOKEN_LENGTH))
                .body("id", startsWith("ocid1.credential.oc1.."))
                .body("userId", equalTo(userId))
                .body("description", equalTo("docker login"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("timeCreated", notNullValue())
                .body("$", hasKey("timeExpires"))
                .body("timeExpires", nullValue())
                .body("inactiveStatus", nullValue())
                .extract().path("id");

        given().when().get("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(tokenId))
                .body("[0]", not(hasKey("token")));

        String etag = given().contentType("application/json").body(Map.of("description", "renamed"))
            .when().put("/20160918/users/{userId}/authTokens/{authTokenId}", userId, tokenId)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("description", equalTo("renamed"))
                .body("$", not(hasKey("token")))
                .extract().header("etag");

        given().header("if-match", "stale")
            .when().delete("/20160918/users/{userId}/authTokens/{authTokenId}", userId, tokenId)
            .then().statusCode(412)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NoEtagMatch"));
        given().header("if-match", etag)
            .when().delete("/20160918/users/{userId}/authTokens/{authTokenId}", userId, tokenId)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().when().get("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void errorsUseTheOciErrorBody() {
        String userId = createUser();

        given().contentType("application/json").body(Map.of())
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("MissingParameter"))
                .body("message", equalTo("description is required"));

        given().contentType("application/json").body(Map.of("description", ""))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200).body("description", equalTo(""));
        given().contentType("application/json").body(Map.of("description", "two"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200);
        given().contentType("application/json").body(Map.of("description", "three"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("LimitExceeded"))
                .body("message", equalTo("A user can have up to 2 auth tokens at a time"));

        given().when().delete("/20160918/users/{userId}/authTokens/{authTokenId}", userId,
                "ocid1.credential.oc1..missing")
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: auth token "
                        + "ocid1.credential.oc1..missing"));
        given().when().get("/20160918/users/{userId}/authTokens", "ocid1.user.oc1..missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void deletingAUserThroughTheApiRemovesItsTokens() {
        String userId = createUser();
        String otherId = createUser();
        given().contentType("application/json").body(Map.of("description", "a"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200);
        given().contentType("application/json").body(Map.of("description", "b"))
            .when().post("/20160918/users/{userId}/authTokens", otherId)
            .then().statusCode(200);

        given().when().delete("/20160918/users/{userId}", userId).then().statusCode(204);

        given().when().get("/20160918/users/{userId}/authTokens", otherId)
            .then().statusCode(200).body("size()", equalTo(1));
    }
}
