package io.floci.oci.compat;

import com.oracle.bmc.keymanagement.KmsCryptoClient;
import com.oracle.bmc.keymanagement.KmsManagementClient;
import com.oracle.bmc.keymanagement.KmsVaultClient;
import com.oracle.bmc.keymanagement.model.CreateKeyDetails;
import com.oracle.bmc.keymanagement.model.CreateVaultDetails;
import com.oracle.bmc.keymanagement.model.DecryptDataDetails;
import com.oracle.bmc.keymanagement.model.DecryptedData;
import com.oracle.bmc.keymanagement.model.EncryptDataDetails;
import com.oracle.bmc.keymanagement.model.EncryptedData;
import com.oracle.bmc.keymanagement.model.ImportKeyDetails;
import com.oracle.bmc.keymanagement.model.Key;
import com.oracle.bmc.keymanagement.model.KeyShape;
import com.oracle.bmc.keymanagement.model.Vault;
import com.oracle.bmc.keymanagement.model.WrappedImportKey;
import com.oracle.bmc.keymanagement.model.WrappingKey;
import com.oracle.bmc.keymanagement.requests.CreateKeyRequest;
import com.oracle.bmc.keymanagement.requests.CreateVaultRequest;
import com.oracle.bmc.keymanagement.requests.DecryptRequest;
import com.oracle.bmc.keymanagement.requests.EncryptRequest;
import com.oracle.bmc.keymanagement.requests.GetKeyRequest;
import com.oracle.bmc.keymanagement.requests.GetVaultRequest;
import com.oracle.bmc.keymanagement.requests.GetWrappingKeyRequest;
import com.oracle.bmc.keymanagement.requests.ImportKeyRequest;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.vault.model.Base64SecretContentDetails;
import com.oracle.bmc.vault.model.CreateSecretDetails;
import com.oracle.bmc.vault.requests.CreateSecretRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Vault/KMS/Secrets against the real oci-java-sdk — including the
 * endpoint-indirection flow (GetVault → management/crypto clients built on the
 * returned endpoints).
 */
class VaultKmsCompatibilityTest {

    private static KmsVaultClient vaultClient;

    @BeforeAll
    static void setUp() {
        vaultClient = EmulatorFixture.kmsVault();
    }

    @AfterAll
    static void tearDown() {
        vaultClient.close();
    }

    private static Vault createVault(String name) {
        return vaultClient.createVault(CreateVaultRequest.builder()
                .createVaultDetails(CreateVaultDetails.builder()
                        .compartmentId(TENANCY)
                        .displayName(name)
                        .vaultType(CreateVaultDetails.VaultType.Default)
                        .build())
                .build()).getVault();
    }

    @Test
    void vaultExposesUsableManagementAndCryptoEndpoints() {
        Vault vault = createVault("sdk-vault-" + System.nanoTime());
        assertThat(vault.getLifecycleState().getValue()).isEqualTo("ACTIVE");
        assertThat(vault.getManagementEndpoint()).isNotBlank();
        assertThat(vault.getCryptoEndpoint()).isNotBlank();

        Vault fetched = vaultClient.getVault(GetVaultRequest.builder()
                .vaultId(vault.getId()).build()).getVault();
        assertThat(fetched.getId()).isEqualTo(vault.getId());

        // The SDK builds a client straight from the returned endpoint — this is the
        // contract that forbids path-suffixed endpoints.
        try (var management = EmulatorFixture.kmsManagement(vault.getManagementEndpoint())) {
            var key = management.createKey(CreateKeyRequest.builder()
                    .createKeyDetails(CreateKeyDetails.builder()
                            .compartmentId(TENANCY)
                            .displayName("sdk-key")
                            .keyShape(KeyShape.builder()
                                    .algorithm(KeyShape.Algorithm.Aes)
                                    .length(32)
                                    .build())
                            .build())
                    .build()).getKey();
            // Keys reach ENABLED, not ACTIVE.
            assertThat(key.getLifecycleState().getValue()).isEqualTo("ENABLED");
            assertThat(key.getVaultId()).isEqualTo(vault.getId());
            assertThat(key.getCurrentKeyVersion()).isNotBlank();

            var reread = management.getKey(GetKeyRequest.builder()
                    .keyId(key.getId()).build()).getKey();
            assertThat(reread.getKeyShape().getAlgorithm().getValue()).isEqualTo("AES");
        }
    }

    @Test
    void cryptoRoundtripThroughTheCryptoEndpoint() {
        Vault vault = createVault("sdk-crypto-vault-" + System.nanoTime());
        String keyId;
        try (var management = EmulatorFixture.kmsManagement(vault.getManagementEndpoint())) {
            keyId = management.createKey(CreateKeyRequest.builder()
                    .createKeyDetails(CreateKeyDetails.builder()
                            .compartmentId(TENANCY).displayName("sdk-crypto-key")
                            .keyShape(KeyShape.builder()
                                    .algorithm(KeyShape.Algorithm.Aes).length(32).build())
                            .build())
                    .build()).getKey().getId();
        }

        String plaintext = Base64.getEncoder()
                .encodeToString("sdk crypto payload".getBytes(StandardCharsets.UTF_8));
        try (var crypto = EmulatorFixture.kmsCrypto(vault.getCryptoEndpoint())) {
            var encrypted = crypto.encrypt(EncryptRequest.builder()
                    .encryptDataDetails(EncryptDataDetails.builder()
                            .keyId(keyId).plaintext(plaintext).build())
                    .build()).getEncryptedData();
            assertThat(encrypted.getCiphertext()).isNotEqualTo(plaintext);

            var decrypted = crypto.decrypt(DecryptRequest.builder()
                    .decryptDataDetails(DecryptDataDetails.builder()
                            .keyId(keyId).ciphertext(encrypted.getCiphertext()).build())
                    .build()).getDecryptedData();
            assertThat(decrypted.getPlaintext()).isEqualTo(plaintext);
            // plaintextChecksum is wire-mandatory.
            assertThat(decrypted.getPlaintextChecksum()).isNotBlank();
        }
    }

    @Test
    void secretIsWriteOnlyOnManagementAndReadableViaBundle() {
        Vault vault = createVault("sdk-secret-vault-" + System.nanoTime());
        String keyId;
        try (var management = EmulatorFixture.kmsManagement(vault.getManagementEndpoint())) {
            keyId = management.createKey(CreateKeyRequest.builder()
                    .createKeyDetails(CreateKeyDetails.builder()
                            .compartmentId(TENANCY).displayName("sdk-secret-key")
                            .keyShape(KeyShape.builder()
                                    .algorithm(KeyShape.Algorithm.Aes).length(32).build())
                            .build())
                    .build()).getKey().getId();
        }

        String secretValue = Base64.getEncoder().encodeToString("s3cret".getBytes());
        String secretId;
        try (var vaults = EmulatorFixture.vaults()) {
            var secret = vaults.createSecret(CreateSecretRequest.builder()
                    .createSecretDetails(CreateSecretDetails.builder()
                            .compartmentId(TENANCY)
                            .vaultId(vault.getId())
                            .keyId(keyId)
                            .secretName("sdk-secret-" + System.nanoTime())
                            .secretContent(Base64SecretContentDetails.builder()
                                    .content(secretValue).build())
                            .build())
                    .build()).getSecret();
            assertThat(secret.getLifecycleState().getValue()).isEqualTo("ACTIVE");
            assertThat(secret.getCurrentVersionNumber()).isEqualTo(1L);
            secretId = secret.getId();
        }

        // Content only comes back through the separate secrets (bundle) client.
        try (var secrets = EmulatorFixture.secrets()) {
            var bundle = secrets.getSecretBundle(GetSecretBundleRequest.builder()
                    .secretId(secretId).build()).getSecretBundle();
            assertThat(bundle.getVersionNumber()).isEqualTo(1L);
            var content = (com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails)
                    bundle.getSecretBundleContent();
            assertThat(content.getContent()).isEqualTo(secretValue);
        }
    }

    @Test
    void importedAesKeyRoundtripsThroughTheCryptoEndpoint() throws Exception {
        Vault vault = createVault("sdk-import-vault-" + System.nanoTime());
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 11);

        String keyId;
        try (KmsManagementClient management = EmulatorFixture.kmsManagement(vault.getManagementEndpoint())) {
            WrappingKey wrappingKey = management.getWrappingKey(GetWrappingKeyRequest.builder().build())
                    .getWrappingKey();
            assertThat(wrappingKey.getId()).isEqualTo(vault.getWrappingkeyId());
            assertThat(wrappingKey.getLifecycleState().getValue()).isEqualTo("ENABLED");

            Key key = management.importKey(ImportKeyRequest.builder()
                    .importKeyDetails(ImportKeyDetails.builder()
                            .compartmentId(TENANCY)
                            .displayName("sdk-imported-key")
                            .keyShape(KeyShape.builder()
                                    .algorithm(KeyShape.Algorithm.Aes).length(32).build())
                            .wrappedImportKey(WrappedImportKey.builder()
                                    .keyMaterial(Base64.getEncoder().encodeToString(
                                            rsaOaepAesSha256(wrappingKey.getPublicKey(), material)))
                                    .wrappingAlgorithm(WrappedImportKey.WrappingAlgorithm.RsaOaepAesSha256)
                                    .build())
                            .build())
                    .build()).getKey();
            assertThat(key.getLifecycleState().getValue()).isEqualTo("ENABLED");
            assertThat(key.getVaultId()).isEqualTo(vault.getId());
            keyId = key.getId();
        }

        String plaintext = Base64.getEncoder()
                .encodeToString("sdk imported payload".getBytes(StandardCharsets.UTF_8));
        try (KmsCryptoClient crypto = EmulatorFixture.kmsCrypto(vault.getCryptoEndpoint())) {
            EncryptedData encrypted = crypto.encrypt(EncryptRequest.builder()
                    .encryptDataDetails(EncryptDataDetails.builder()
                            .keyId(keyId).plaintext(plaintext).build())
                    .build()).getEncryptedData();
            DecryptedData decrypted = crypto.decrypt(DecryptRequest.builder()
                    .decryptDataDetails(DecryptDataDetails.builder()
                            .keyId(keyId).ciphertext(encrypted.getCiphertext()).build())
                    .build()).getDecryptedData();
            assertThat(decrypted.getPlaintext()).isEqualTo(plaintext);
        }
    }

    /** Wraps material the way OCI documents RSA_OAEP_AES_SHA256: RSA-OAEP(AES key) || AES-KWP(material). */
    private static byte[] rsaOaepAesSha256(String publicKeyPem, byte[] material) throws Exception {
        String base64 = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        byte[] aesKey = new byte[32];
        Arrays.fill(aesKey, (byte) 13);

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] wrappedAesKey = rsa.doFinal(aesKey);

        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        byte[] wrappedMaterial = kwp.doFinal(material);

        byte[] payload = Arrays.copyOf(wrappedAesKey, wrappedAesKey.length + wrappedMaterial.length);
        System.arraycopy(wrappedMaterial, 0, payload, wrappedAesKey.length, wrappedMaterial.length);
        return payload;
    }
}
