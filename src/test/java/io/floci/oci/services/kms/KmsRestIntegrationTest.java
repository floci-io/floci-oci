package io.floci.oci.services.kms;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Exercises the endpoint-indirection contract end to end: the vault's
 * managementEndpoint/cryptoEndpoint returned by the control plane are path-suffixed
 * base URLs on this same emulator, and the SDK-style paths appended to them resolve.
 */
@QuarkusTest
class KmsRestIntegrationTest {

    private static String compartment;

    private String createVault(String name) {
        return given()
                .contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", name,
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
                .body(Map.of("compartmentId", compartment, "displayName", name,
                        "keyShape", Map.of("algorithm", "AES", "length", 32)))
            .when().post("/20180608/keys")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("lifecycleState", equalTo("ENABLED"))
                .body("keyShape.algorithm", equalTo("AES"))
                .body("currentKeyVersion", startsWith("ocid1.keyversion."))
                .extract().path("id");
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itkmscompartment");
        }
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
            .queryParam("compartmentId", compartment)
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
            .queryParam("compartmentId", compartment)
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
                .body(Map.of("compartmentId", compartment, "displayName", "it-rsa",
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

    @Test
    void importedAesKeyEncryptsThroughCryptoEndpoint() throws Exception {
        String vaultId = createVault("it-import-" + System.nanoTime());

        String wrappingKeyId = given()
            .when().get("/20180608/wrappingKeys")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("vaultId", equalTo(vaultId))
                .body("compartmentId", equalTo(compartment))
                .body("lifecycleState", equalTo("ENABLED"))
                .body("publicKey", startsWith("-----BEGIN PUBLIC KEY-----"))
                .body("timeCreated", notNullValue())
                .extract().path("id");
        String publicKeyPem = given().when().get("/20180608/wrappingKeys").then().extract().path("publicKey");

        given()
            .when().get("/20180608/vaults/" + vaultId)
            .then().statusCode(200)
                .body("wrappingkeyId", equalTo(wrappingKeyId));

        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 5);
        String keyId = given()
                .contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "it-imported",
                        "keyShape", Map.of("algorithm", "AES", "length", 32),
                        "wrappedImportKey", Map.of(
                                "keyMaterial", Base64.getEncoder().encodeToString(rsaOaepSha256(publicKeyPem, material)),
                                "wrappingAlgorithm", "RSA_OAEP_SHA256")))
            .when().post("/20180608/keys/import")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("vaultId", equalTo(vaultId))
                .body("lifecycleState", equalTo("ENABLED"))
                .body("keyShape.algorithm", equalTo("AES"))
                .body("keyShape.length", equalTo(32))
                .body("protectionMode", equalTo("HSM"))
                .extract().path("id");

        given()
            .when().get("/20180608/keys/" + keyId + "/keyVersions")
            .then().statusCode(200)
                .body("[0].origin", equalTo("EXTERNAL"));

        String plaintext = Base64.getEncoder().encodeToString("imported wire crypto".getBytes());
        String ciphertext = given()
                .contentType("application/json")
                .body(Map.of("keyId", keyId, "plaintext", plaintext))
            .when().post("/20180608/encrypt")
            .then().statusCode(200).extract().path("ciphertext");

        given()
            .contentType("application/json")
            .body(Map.of("keyId", keyId, "ciphertext", ciphertext))
            .when().post("/20180608/decrypt")
            .then().statusCode(200)
                .body("plaintext", equalTo(plaintext));
    }

    @Test
    void importRejectsMaterialNotWrappedWithTheWrappingKey() {
        createVault("it-import-bad-" + System.nanoTime());
        given()
            .contentType("application/json")
            .body(Map.of("compartmentId", compartment, "displayName", "it-bad",
                    "keyShape", Map.of("algorithm", "AES", "length", 32),
                    "wrappedImportKey", Map.of(
                            "keyMaterial", Base64.getEncoder().encodeToString(new byte[512]),
                            "wrappingAlgorithm", "RSA_OAEP_SHA256")))
            .when().post("/20180608/keys/import")
            .then().statusCode(400)
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void importedKeyVersionBecomesTheCurrentVersion() throws Exception {
        String keyId = createAesKey(createVault("it-import-version-" + System.nanoTime()), "it-version");
        String publicKeyPem = given().when().get("/20180608/wrappingKeys").then().statusCode(200)
                .extract().path("publicKey");

        String versionId = given()
                .contentType("application/json")
                .body(Map.of("wrappedImportKey", Map.of(
                        "keyMaterial", Base64.getEncoder().encodeToString(rsaOaepSha256(publicKeyPem, new byte[32])),
                        "wrappingAlgorithm", "RSA_OAEP_SHA256")))
            .when().post("/20180608/keys/" + keyId + "/keyVersions/import")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("keyId", equalTo(keyId))
                .body("origin", equalTo("EXTERNAL"))
                .body("lifecycleState", equalTo("ENABLED"))
                .extract().path("id");

        given()
            .when().get("/20180608/keys/" + keyId)
            .then().statusCode(200)
                .body("currentKeyVersion", equalTo(versionId));
    }

    @Test
    void importRejectsEcdsaKeys() throws Exception {
        createVault("it-import-ecdsa-" + System.nanoTime());
        given()
            .contentType("application/json")
            .body(Map.of("compartmentId", compartment, "displayName", "it-ec",
                    "keyShape", Map.of("algorithm", "ECDSA", "length", 32, "curveId", "NIST_P256"),
                    "wrappedImportKey", Map.of("keyMaterial", "AA==", "wrappingAlgorithm", "RSA_OAEP_AES_SHA256")))
            .when().post("/20180608/keys/import")
            .then().statusCode(400)
                .body("code", equalTo("InvalidParameter"));
    }

    private static byte[] rsaOaepSha256(String publicKeyPem, byte[] plaintext) throws Exception {
        String base64 = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(plaintext);
    }
}
