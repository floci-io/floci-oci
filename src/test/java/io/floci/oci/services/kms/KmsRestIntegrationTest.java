package io.floci.oci.services.kms;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Exercises the endpoint-indirection contract end to end: the vault's
 * managementEndpoint/cryptoEndpoint returned by the control plane are path-suffixed
 * base URLs on this same emulator, and the SDK-style paths appended to them resolve.
 */
@QuarkusTest
class KmsRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..itkmscompartment";

    private String createVault(String name) {
        return given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT, "displayName", name,
                        "vaultType", "DEFAULT"))
            .when().post("/20180608/vaults")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("wrappingkeyId", startsWith("ocid1.key."))
                .body("managementEndpoint", notNullValue())
                .body("cryptoEndpoint", notNullValue())
                .extract().path("id");
    }

    private String createAesKey(String vaultId, String name) {
        return given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT, "displayName", name,
                        "keyShape", Map.of("algorithm", "AES", "length", 32)))
            .when().post("/20180608/keys")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("lifecycleState", equalTo("ENABLED"))
                .body("keyShape.algorithm", equalTo("AES"))
                .body("currentKeyVersion", startsWith("ocid1.keyversion."))
                .extract().path("id");
    }

    @Test
    void vaultLifecycleWithScheduledDeletion() {
        String vaultId = createVault("it-vault-" + System.nanoTime());

        given()
            .when().get("/20180608/vaults/" + vaultId)
            .then().statusCode(200)
                .body("id", equalTo(vaultId))
                .body("managementEndpoint", equalTo("http://localhost:4599"));

        // Bare-array list.
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20180608/vaults")
            .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));

        given()
            .contentType("application/json").body(Map.of())
            .when().post("/20180608/vaults/" + vaultId + "/actions/scheduleDeletion")
            .then().statusCode(200)
                .body("lifecycleState", equalTo("PENDING_DELETION"))
                .body("timeOfDeletion", notNullValue());

        given()
            .contentType("application/json")
            .when().post("/20180608/vaults/" + vaultId + "/actions/cancelDeletion")
            .then().statusCode(200)
                .body("lifecycleState", equalTo("ACTIVE"));
    }

    @Test
    void keyManagementThroughVaultScopedEndpoint() {
        String vaultId = createVault("it-keys-" + System.nanoTime());
        String keyId = createAesKey(vaultId, "it-aes");
        String base = "/20180608/keys";

        // KeySummary: flat algorithm, no keyShape.
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get(base)
            .then().statusCode(200)
                .body("[0].algorithm", equalTo("AES"))
                .body("[0].keyShape", org.hamcrest.Matchers.nullValue());

        given()
            .contentType("application/json")
            .when().post(base + "/" + keyId + "/actions/disable")
            .then().statusCode(200).body("lifecycleState", equalTo("DISABLED"));

        given()
            .contentType("application/json")
            .when().post(base + "/" + keyId + "/actions/enable")
            .then().statusCode(200).body("lifecycleState", equalTo("ENABLED"));

        // Rotation: second version becomes current.
        String newVersion = given()
                .contentType("application/json")
            .when().post(base + "/" + keyId + "/keyVersions")
            .then().statusCode(200)
                .body("keyId", equalTo(keyId))
                .extract().path("id");

        given()
            .when().get(base + "/" + keyId)
            .then().statusCode(200)
                .body("currentKeyVersion", equalTo(newVersion));

        given()
            .when().get(base + "/" + keyId + "/keyVersions")
            .then().statusCode(200).body("size()", equalTo(2));
    }

    @Test
    void cryptoRoundtripThroughVaultScopedEndpoint() {
        String vaultId = createVault("it-crypto-" + System.nanoTime());
        String keyId = createAesKey(vaultId, "it-crypto-key");
        String cryptoBase = "/20180608";
        String plaintext = Base64.getEncoder().encodeToString("wire crypto".getBytes());

        String ciphertext = given()
                .contentType("application/json")
                .body(Map.of("keyId", keyId, "plaintext", plaintext))
            .when().post(cryptoBase + "/encrypt")
            .then().statusCode(200)
                .body("keyId", equalTo(keyId))
                .body("keyVersionId", startsWith("ocid1.keyversion."))
                .extract().path("ciphertext");

        given()
            .contentType("application/json")
            .body(Map.of("keyId", keyId, "ciphertext", ciphertext))
            .when().post(cryptoBase + "/decrypt")
            .then().statusCode(200)
                .body("plaintext", equalTo(plaintext))
                .body("plaintextChecksum", notNullValue());

        given()
            .contentType("application/json")
            .body(Map.of("keyId", keyId, "includePlaintextKey", true,
                    "keyShape", Map.of("algorithm", "AES", "length", 32)))
            .when().post(cryptoBase + "/generateDataEncryptionKey")
            .then().statusCode(200)
                .body("ciphertext", notNullValue())
                .body("plaintext", notNullValue())
                .body("plaintextChecksum", notNullValue());
    }

    @Test
    void signAndVerifyThroughCryptoEndpoint() {
        String vaultId = createVault("it-sign-" + System.nanoTime());
        String keyId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", COMPARTMENT, "displayName", "it-rsa",
                        "keyShape", Map.of("algorithm", "RSA", "length", 256)))
            .when().post("/20180608/keys")
            .then().statusCode(200).extract().path("id");

        String message = Base64.getEncoder().encodeToString("sign this".getBytes());
        String cryptoBase = "/20180608";

        Map<String, String> signed = given()
                .contentType("application/json")
                .body(Map.of("keyId", keyId, "message", message,
                        "signingAlgorithm", "SHA_256_RSA_PKCS1_V1_5"))
            .when().post(cryptoBase + "/sign")
            .then().statusCode(200)
                .body("signature", notNullValue())
                .extract().as(new io.restassured.common.mapper.TypeRef<Map<String, String>>() {});

        given()
            .contentType("application/json")
            .body(Map.of("keyId", keyId, "keyVersionId", signed.get("keyVersionId"),
                    "signature", signed.get("signature"), "message", message,
                    "signingAlgorithm", "SHA_256_RSA_PKCS1_V1_5"))
            .when().post(cryptoBase + "/verify")
            .then().statusCode(200)
                .body("isSignatureValid", equalTo(true));
    }
}
