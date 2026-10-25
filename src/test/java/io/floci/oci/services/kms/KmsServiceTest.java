package io.floci.oci.services.kms;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.kms.model.StoredKey;
import io.floci.oci.services.kms.model.StoredKey.StoredKeyVersion;
import io.floci.oci.services.kms.model.StoredVault;
import io.floci.oci.services.kms.model.StoredWrappingKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;
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
        service = new KmsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                config);
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

    @Test
    void vaultsKeepTheirOwnWrappingKeyIdsButShareTheKeyPair() {
        StoredVault v1 = vault();
        StoredVault v2 = service.createVault(COMPARTMENT, "other-vault", "DEFAULT", null, null);
        assertTrue(v1.getWrappingkeyId().startsWith("ocid1.key.oc1.iad."));
        assertNotEquals(v1.getWrappingkeyId(), v2.getWrappingkeyId());
        StoredWrappingKey wrappingKey = service.wrappingKey();
        assertTrue(wrappingKey.getPublicKeyPem().startsWith("-----BEGIN PUBLIC KEY-----\n"));
        assertEquals(wrappingKey.getPublicKeyPem(), service.wrappingKey().getPublicKeyPem());
        assertEquals(v2.getId(), service.wrappingKeyVault().getId());
    }

    @Test
    void wrappingKeyVaultWithoutAnyVaultIs404() {
        OciException e = assertThrows(OciException.class, () -> service.wrappingKeyVault());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void importedAesKeyWrappedWithRsaOaepSha256EncryptsAndDecrypts() throws Exception {
        StoredVault v = vault();
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 7);

        StoredKey key = service.importKey(COMPARTMENT, "imported-aes", "AES", 32, null, null,
                Base64.getEncoder().encodeToString(rsaOaepSha256(material)), "RSA_OAEP_SHA256",
                null, null);

        assertEquals(v.getId(), key.getVaultId());
        assertEquals("ENABLED", key.getLifecycleState());
        assertEquals("HSM", key.getProtectionMode());
        assertEquals("EXTERNAL", key.getVersions().get(0).getOrigin());
        assertArrayEquals(material, Base64.getDecoder().decode(key.getVersions().get(0).getKeyMaterial()));

        String plaintext = Base64.getEncoder().encodeToString("imported".getBytes(StandardCharsets.UTF_8));
        KmsService.EncryptResult encrypted = service.encrypt(v.getId(), key.getId(), plaintext, null);
        assertEquals(plaintext, service.decrypt(v.getId(), key.getId(), encrypted.ciphertext()).plaintext());
    }

    @Test
    void importedAesKeyWrappedWithRsaOaepAesSha256UnwrapsTheMaterial() throws Exception {
        vault();
        byte[] material = new byte[16];
        Arrays.fill(material, (byte) 9);
        byte[] payload = rsaOaepAesSha256(material);

        StoredKey key = service.importKey(COMPARTMENT, "imported-aes-kwp", "AES", 16, null, "SOFTWARE",
                Base64.getEncoder().encodeToString(payload), "RSA_OAEP_AES_SHA256", null, null);

        assertEquals("SOFTWARE", key.getProtectionMode());
        assertArrayEquals(material, Base64.getDecoder().decode(key.getVersions().get(0).getKeyMaterial()));
    }

    @Test
    void importRejectsMaterialWhoseLengthDoesNotMatchTheKeyShape() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepSha256(new byte[16]));
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "short",
                "AES", 32, null, null, wrapped, "RSA_OAEP_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void importRejectsAesLengthsAesCannotUseEvenWhenTheMaterialMatches() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepSha256(new byte[17]));
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "odd",
                "AES", 17, null, null, wrapped, "RSA_OAEP_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void importedRsaKeySignsWithTheImportedPrivateKey() throws Exception {
        StoredVault v = vault();
        KeyPair local = rsaKeyPair(2048);

        StoredKey key = service.importKey(COMPARTMENT, "imported-rsa", "RSA", 256, null, null,
                Base64.getEncoder().encodeToString(rsaOaepAesSha256(local.getPrivate().getEncoded())),
                "RSA_OAEP_AES_SHA256", null, null);

        StoredKeyVersion version = key.getVersions().get(0);
        assertEquals("EXTERNAL", version.getOrigin());
        assertArrayEquals(local.getPublic().getEncoded(), Base64.getDecoder().decode(version.getPublicKey()));

        byte[] message = "signed by an imported key".getBytes(StandardCharsets.UTF_8);
        KmsService.SignResult signed = service.sign(v.getId(), key.getId(),
                Base64.getEncoder().encodeToString(message), "SHA_256_RSA_PKCS1_V1_5", null);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(local.getPublic());
        verifier.update(message);
        assertTrue(verifier.verify(Base64.getDecoder().decode(signed.signature())));
    }

    @Test
    void importRejectsRsaMaterialWrappedWithoutTheAesLayer() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepSha256(new byte[32]));
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "rsa",
                "RSA", 256, null, null, wrapped, "RSA_OAEP_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void importRejectsAnRsaKeyWhoseSizeDoesNotMatchTheKeyShape() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(
                rsaOaepAesSha256(rsaKeyPair(2048).getPrivate().getEncoded()));
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "rsa",
                "RSA", 384, null, null, wrapped, "RSA_OAEP_AES_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void importRejectsRsaMaterialThatIsNotAPkcs8PrivateKey() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepAesSha256(new byte[64]));
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "rsa",
                "RSA", 256, null, null, wrapped, "RSA_OAEP_AES_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void ecdsaKeysCannotBeImportedAsInOci() throws Exception {
        StoredVault v = vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepAesSha256(new byte[32]));
        assertEquals(400, assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "ec",
                "ECDSA", 32, "NIST_P256", null, wrapped, "RSA_OAEP_AES_SHA256", null, null)).getHttpStatus());
        StoredKey ec = service.createKey(v.getId(), COMPARTMENT, "ec-key", "ECDSA", 32, "NIST_P256",
                null, null, null);
        assertEquals(400, assertThrows(OciException.class, () -> service.importKeyVersion(ec.getId(),
                wrapped, "RSA_OAEP_AES_SHA256")).getHttpStatus());
    }

    @Test
    void importedKeyVersionBecomesCurrentAndOldCiphertextStillDecrypts() throws Exception {
        StoredVault v = vault();
        StoredKey key = aesKey(v);
        String plaintext = Base64.getEncoder().encodeToString("before import".getBytes(StandardCharsets.UTF_8));
        KmsService.EncryptResult before = service.encrypt(v.getId(), key.getId(), plaintext, null);
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 21);

        StoredKeyVersion imported = service.importKeyVersion(key.getId(),
                Base64.getEncoder().encodeToString(rsaOaepSha256(material)), "RSA_OAEP_SHA256");

        StoredKey rotated = service.getKey(v.getId(), key.getId());
        assertEquals(2, rotated.getVersions().size());
        assertEquals(imported.getId(), rotated.getCurrentKeyVersionId());
        assertEquals("EXTERNAL", imported.getOrigin());
        assertArrayEquals(material, Base64.getDecoder().decode(imported.getKeyMaterial()));
        assertEquals(imported.getId(),
                service.encrypt(v.getId(), key.getId(), plaintext, null).keyVersionId());
        assertEquals(plaintext, service.decrypt(v.getId(), key.getId(), before.ciphertext()).plaintext());
    }

    @Test
    void importedRsaKeyVersionReplacesTheSigningKey() throws Exception {
        StoredVault v = vault();
        StoredKey key = service.createKey(v.getId(), COMPARTMENT, "rsa-key", "RSA", 256, null, null, null, null);
        KeyPair local = rsaKeyPair(2048);

        StoredKeyVersion imported = service.importKeyVersion(key.getId(),
                Base64.getEncoder().encodeToString(rsaOaepAesSha256(local.getPrivate().getEncoded())),
                "RSA_OAEP_AES_SHA256");

        assertEquals(imported.getId(), service.getKey(v.getId(), key.getId()).getCurrentKeyVersionId());
        assertArrayEquals(local.getPublic().getEncoded(), Base64.getDecoder().decode(imported.getPublicKey()));
    }

    @Test
    void importKeyVersionRejectsMaterialOfADifferentLengthAndUnknownKeys() throws Exception {
        StoredKey key = aesKey(vault());
        String sixteenBytes = Base64.getEncoder().encodeToString(rsaOaepSha256(new byte[16]));
        assertEquals(400, assertThrows(OciException.class, () -> service.importKeyVersion(key.getId(),
                sixteenBytes, "RSA_OAEP_SHA256")).getHttpStatus());
        assertEquals(1, service.getKey(null, key.getId()).getVersions().size());
        assertEquals(404, assertThrows(OciException.class, () -> service.importKeyVersion(
                "ocid1.key.oc1.iad.missing", sixteenBytes, "RSA_OAEP_SHA256")).getHttpStatus());
    }

    @Test
    void importRejectsMaterialNotWrappedWithTheWrappingKey() {
        vault();
        String garbage = Base64.getEncoder().encodeToString(new byte[512]);
        OciException e = assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "garbage",
                "AES", 32, null, null, garbage, "RSA_OAEP_SHA256", null, null));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void importRejectsUnknownWrappingAlgorithmAndExternalProtectionMode() throws Exception {
        vault();
        String wrapped = Base64.getEncoder().encodeToString(rsaOaepSha256(new byte[32]));
        assertEquals(400, assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "k",
                "AES", 32, null, null, wrapped, "RSA_PKCS1", null, null)).getHttpStatus());
        assertEquals(400, assertThrows(OciException.class, () -> service.importKey(COMPARTMENT, "k",
                "AES", 32, null, "EXTERNAL", wrapped, "RSA_OAEP_SHA256", null, null)).getHttpStatus());
    }

    private byte[] rsaOaepAesSha256(byte[] material) throws Exception {
        byte[] aesKey = new byte[32];
        Arrays.fill(aesKey, (byte) 3);
        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        byte[] wrappedAesKey = rsaOaepSha256(aesKey);
        byte[] wrappedMaterial = kwp.doFinal(material);
        byte[] payload = Arrays.copyOf(wrappedAesKey, wrappedAesKey.length + wrappedMaterial.length);
        System.arraycopy(wrappedMaterial, 0, payload, wrappedAesKey.length, wrappedMaterial.length);
        return payload;
    }

    private static KeyPair rsaKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private byte[] rsaOaepSha256(byte[] plaintext) throws Exception {
        String base64 = service.wrappingKey().getPublicKeyPem()
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
