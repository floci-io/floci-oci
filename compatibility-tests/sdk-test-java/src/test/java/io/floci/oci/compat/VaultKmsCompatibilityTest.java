package io.floci.oci.compat;

import com.oracle.bmc.keymanagement.KmsVaultClient;
import com.oracle.bmc.keymanagement.model.CreateKeyDetails;
import com.oracle.bmc.keymanagement.model.CreateVaultDetails;
import com.oracle.bmc.keymanagement.model.DecryptDataDetails;
import com.oracle.bmc.keymanagement.model.EncryptDataDetails;
import com.oracle.bmc.keymanagement.model.KeyShape;
import com.oracle.bmc.keymanagement.model.Vault;
import com.oracle.bmc.keymanagement.requests.CreateKeyRequest;
import com.oracle.bmc.keymanagement.requests.CreateVaultRequest;
import com.oracle.bmc.keymanagement.requests.DecryptRequest;
import com.oracle.bmc.keymanagement.requests.EncryptRequest;
import com.oracle.bmc.keymanagement.requests.GetKeyRequest;
import com.oracle.bmc.keymanagement.requests.GetVaultRequest;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.vault.model.Base64SecretContentDetails;
import com.oracle.bmc.vault.model.CreateSecretDetails;
import com.oracle.bmc.vault.requests.CreateSecretRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
}
