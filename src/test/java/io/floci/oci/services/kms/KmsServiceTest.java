package io.floci.oci.services.kms;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.kms.model.StoredKey;
import io.floci.oci.services.kms.model.StoredVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class KmsServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..kmstestcompartment";

    private KmsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        service = new KmsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config);
    }

    private StoredVault vault() {
        return service.createVault(COMPARTMENT, "test-vault", "DEFAULT", null, null);
    }

    private StoredKey aesKey(StoredVault v) {
        return service.createKey(v.getId(), COMPARTMENT, "aes-key", "AES", 32, null, null, null, null);
    }

    @Test
    void vaultLifecycleWithScheduledDeletion() {
        StoredVault v = vault();
        assertTrue(v.getId().startsWith("ocid1.vault.oc1.iad."));
        assertEquals("ACTIVE", v.getLifecycleState());
        assertNotNull(v.getWrappingkeyId());
        // The SDKs reject endpoints containing a path, so every vault shares the
        // emulator host; the endpoints must stay host-only.
        assertEquals("http://localhost:4599", service.managementEndpoint(v.getId()));
        assertEquals("http://localhost:4599", service.cryptoEndpoint(v.getId()));

        StoredVault scheduled = service.scheduleVaultDeletion(v.getId(), null, null);
        assertEquals("PENDING_DELETION", scheduled.getLifecycleState());
        assertNotNull(scheduled.getTimeOfDeletion());

        StoredVault cancelled = service.cancelVaultDeletion(v.getId(), null);
        assertEquals("ACTIVE", cancelled.getLifecycleState());
        assertNull(cancelled.getTimeOfDeletion());
    }

    @Test
    void keyReachesEnabledNotActive() {
        StoredKey key = aesKey(vault());
        assertEquals("ENABLED", key.getLifecycleState());
        assertNotNull(key.getCurrentKeyVersionId());
        assertEquals(1, key.getVersions().size());
        assertEquals("INTERNAL", key.getVersions().get(0).getOrigin());
    }

    @Test
    void keyIsScopedToItsVaultWhenTheVaultIsKnown() {
        StoredVault v1 = vault();
        StoredVault v2 = service.createVault(COMPARTMENT, "other-vault", "DEFAULT", null, null);
        StoredKey key = aesKey(v1);
        assertThrows(OciException.class, () -> service.getKey(v2.getId(), key.getId()));
        // A null vault means "the key knows its own vault" — the shared-endpoint path.
        assertEquals(v1.getId(), service.getKey(null, key.getId()).getVaultId());
    }

    @Test
    void createKeyWithoutVaultResolvesTheCompartmentVault() {
        StoredVault v = vault();
        StoredKey key = service.createKey(null, COMPARTMENT, "resolved", "AES", 32,
                null, null, null, null);
        assertEquals(v.getId(), key.getVaultId());
    }

    @Test
    void createKeyWithoutAnyVaultIs404() {
        OciException e = assertThrows(OciException.class,
                () -> service.createKey(null, "ocid1.compartment.oc1..empty", "k", "AES", 32,
                        null, null, null, null));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void encryptDecryptRoundtripWithChecksum() {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        String plaintext = Base64.getEncoder()
                .encodeToString("kms secret payload".getBytes(StandardCharsets.UTF_8));

        KmsService.EncryptResult encrypted = service.encrypt(v.getId(), key.getId(), plaintext, null);
        assertNotEquals(plaintext, encrypted.ciphertext());
        assertEquals(key.getCurrentKeyVersionId(), encrypted.keyVersionId());

        KmsService.DecryptResult decrypted = service.decrypt(v.getId(), key.getId(), encrypted.ciphertext());
        assertEquals(plaintext, decrypted.plaintext());
        assertNotNull(decrypted.plaintextChecksum());
    }

    @Test
    void rotationDecryptsOldCiphertextWithOldVersion() {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        String plaintext = Base64.getEncoder().encodeToString("before rotation".getBytes());
        KmsService.EncryptResult oldCiphertext = service.encrypt(v.getId(), key.getId(), plaintext, null);

        service.createKeyVersion(v.getId(), key.getId());
        StoredKey rotated = service.getKey(v.getId(), key.getId());
        assertEquals(2, rotated.getVersions().size());
        assertNotEquals(oldCiphertext.keyVersionId(), rotated.getCurrentKeyVersionId());

        // Envelope carries the version — old ciphertext still decrypts.
        assertEquals(plaintext, service.decrypt(v.getId(), key.getId(), oldCiphertext.ciphertext()).plaintext());
    }

    @Test
    void disabledKeyRefusesCrypto() {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        service.setKeyEnabled(v.getId(), key.getId(), false, null);
        OciException e = assertThrows(OciException.class,
                () -> service.encrypt(v.getId(), key.getId(), Base64.getEncoder().encodeToString("x".getBytes()), null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void generateDataEncryptionKeyWrapsAndOptionallyReturnsPlaintext() {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        KmsService.GeneratedKeyResult withPlain =
                service.generateDataEncryptionKey(v.getId(), key.getId(), true, 32);
        assertNotNull(withPlain.plaintext());
        assertNotNull(withPlain.plaintextChecksum());
        assertEquals(32, Base64.getDecoder().decode(withPlain.plaintext()).length);
        // The wrapped DEK decrypts back to the plaintext DEK.
        assertEquals(withPlain.plaintext(),
                service.decrypt(v.getId(), key.getId(), withPlain.ciphertext()).plaintext());

        KmsService.GeneratedKeyResult withoutPlain =
                service.generateDataEncryptionKey(v.getId(), key.getId(), false, 16);
        assertNull(withoutPlain.plaintext());
    }

    @Test
    void rsaSignVerifyRoundtrip() {
        StoredVault v = vault();
        StoredKey rsa = service.createKey(v.getId(), COMPARTMENT, "rsa-key", "RSA", 256, null, null, null, null);
        String message = Base64.getEncoder().encodeToString("sign me".getBytes());

        KmsService.SignResult signed = service.sign(v.getId(), rsa.getId(), message,
                "SHA_256_RSA_PKCS1_V1_5", null);
        assertTrue(service.verify(v.getId(), rsa.getId(), signed.keyVersionId(),
                signed.signature(), message, "SHA_256_RSA_PKCS1_V1_5"));
        assertFalse(service.verify(v.getId(), rsa.getId(), signed.keyVersionId(),
                signed.signature(), Base64.getEncoder().encodeToString("tampered".getBytes()),
                "SHA_256_RSA_PKCS1_V1_5"));
    }

    @Test
    void ecdsaSignVerifyRoundtrip() {
        StoredVault v = vault();
        StoredKey ec = service.createKey(v.getId(), COMPARTMENT, "ec-key", "ECDSA", 32,
                "NIST_P256", null, null, null);
        String message = Base64.getEncoder().encodeToString("ecdsa message".getBytes());
        KmsService.SignResult signed = service.sign(v.getId(), ec.getId(), message, "ECDSA_SHA_256", null);
        assertTrue(service.verify(v.getId(), ec.getId(), signed.keyVersionId(),
                signed.signature(), message, "ECDSA_SHA_256"));
    }

    @Test
    void keyScheduleDeletionLifecycle() {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        StoredKey pending = service.scheduleKeyDeletion(v.getId(), key.getId(), null, null);
        assertEquals("PENDING_DELETION", pending.getLifecycleState());
        StoredKey restored = service.cancelKeyDeletion(v.getId(), key.getId(), null);
        assertEquals("ENABLED", restored.getLifecycleState());
    }

    @Test
    void listVaultsRequiresCompartment() {
        assertThrows(OciException.class, () -> service.listVaults(null));
        vault();
        assertEquals(1, service.listVaults(COMPARTMENT).size());
    }
}
