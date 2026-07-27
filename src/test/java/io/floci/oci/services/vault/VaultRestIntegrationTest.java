package io.floci.oci.services.vault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class VaultRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..itvaultcompartment";
    private static final String VAULT = "ocid1.vault.oc1.iad.itvault";
    private static final String KEY = "ocid1.key.oc1.iad.itkey";

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    private String createSecret(String name, String value) {
        return given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT, "vaultId", VAULT, "keyId", KEY,
                        "secretName", name, "description", "it secret",
                        "secretContent", Map.of("contentType", "BASE64", "content", b64(value))))
            .when().post("/20180608/secrets")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", startsWith("ocid1.vaultsecret."))
                .body("lifecycleState", equalTo("ACTIVE"))
                // The Secret shape must NEVER echo content.
                .body("secretContent", nullValue())
                .extract().path("id");
    }

    @Test
    void secretCrudAndVersioning() {
        String secretId = createSecret("it-secret-" + System.nanoTime(), "v1");

        given()
            .when().get("/20180608/secrets/" + secretId)
            .then().statusCode(200)
                .body("currentVersionNumber", equalTo(1));

        given()
            .contentType("application/json")
            .body(Map.of("secretContent", Map.of("contentType", "BASE64", "content", b64("v2"))))
            .when().put("/20180608/secrets/" + secretId)
            .then().statusCode(200)
                .body("currentVersionNumber", equalTo(2));

        // /version/{n} singular; /versions plural.
        given()
            .when().get("/20180608/secrets/" + secretId + "/version/1")
            .then().statusCode(200)
                .body("versionNumber", equalTo(1))
                .body("stages", org.hamcrest.Matchers.hasItem("PREVIOUS"));

        given()
            .when().get("/20180608/secrets/" + secretId + "/versions")
            .then().statusCode(200)
                .body("size()", equalTo(2));
    }

    @Test
    void bundlesExposeContentById() {
        String secretId = createSecret("it-bundle-" + System.nanoTime(), "bundled-value");

        given()
            .when().get("/20190301/secretbundles/" + secretId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("secretId", equalTo(secretId))
                .body("versionNumber", equalTo(1))
                .body("secretBundleContent.contentType", equalTo("BASE64"))
                .body("secretBundleContent.content", equalTo(b64("bundled-value")));
    }

    @Test
    void getByNameIsPostWithQueryParamsAndNoEtag() {
        String name = "it-byname-" + System.nanoTime();
        createSecret(name, "find-me");

        given()
            .queryParam("secretName", name)
            .queryParam("vaultId", VAULT)
            .when().post("/20190301/secretbundles/actions/getByName")
            .then().statusCode(200)
                .header("etag", nullValue())
                .body("secretBundleContent.content", equalTo(b64("find-me")));
    }

    @Test
    void scheduleDeletionHasNoBodyAndHidesBundle() {
        String secretId = createSecret("it-delete-" + System.nanoTime(), "doomed");

        String body = given()
                .contentType("application/json").body(Map.of())
            .when().post("/20180608/secrets/" + secretId + "/actions/scheduleDeletion")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(body.isEmpty(),
                "ScheduleSecretDeletion must return no body");

        given()
            .when().get("/20190301/secretbundles/" + secretId)
            .then().statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
