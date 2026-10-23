package io.floci.oci.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.kms.model.StoredKey;
import io.floci.oci.services.kms.model.StoredKey.StoredKeyVersion;
import io.floci.oci.services.kms.model.StoredVault;
import io.floci.oci.services.kms.model.StoredWrappingKey;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * KMS vaults, keys and the crypto data plane. Crypto is real: AES-GCM, RSA and ECDSA
 * via JCA. See {@link #managementEndpoint} for why every vault shares one endpoint.
 */
@ApplicationScoped
public class KmsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(KmsService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int WRAPPING_KEY_BITS = 4096;
    private static final String WRAPPING_KEY_SLOT = "default";
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final StorageBackend<String, StoredVault> vaults;
    private final StorageBackend<String, StoredKey> keys;
    private final StorageBackend<String, StoredWrappingKey> wrappingKeys;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public KmsService(StorageFactory storageFactory, EmulatorConfig config,
                      ServiceRegistry serviceRegistry, OciContext ociContext) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.vaults = storageFactory.create("kms", "kms-vaults.json",
                new TypeReference<Map<String, StoredVault>>() {});
        this.keys = storageFactory.create("kms", "kms-keys.json",
                new TypeReference<Map<String, StoredKey>>() {});
        this.wrappingKeys = storageFactory.create("kms", "kms-wrapping-keys.json",
                new TypeReference<Map<String, StoredWrappingKey>>() {});
    }

    KmsService(StorageBackend<String, StoredVault> vaults, StorageBackend<String, StoredKey> keys,
               StorageBackend<String, StoredWrappingKey> wrappingKeys, EmulatorConfig config) {
        this.ociContext = OciContext.fromConfig(config);
        this.vaults = vaults;
        this.keys = keys;
        this.wrappingKeys = wrappingKeys;
        this.config = config;
        this.serviceRegistry = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kms")
                .enabled(config.services().kms().enabled())
                .storageKey("kms")
                .resourceClasses(KmsVaultController.class, KmsManagementController.class,
                        KmsWrappingKeyController.class, KmsCryptoController.class)
                .build());
    }

    // ── Vaults ─────────────────────────────────────────────────────────────────

    public StoredVault createVault(String compartmentId, String displayName, String vaultType,
                                   Map<String, String> freeformTags,
                                   Map<String, Map<String, Object>> definedTags) {
        require(compartmentId, "compartmentId");
        require(displayName, "displayName");
        require(vaultType, "vaultType");
        StoredVault v = new StoredVault();
        v.setId(Ocids.generate("vault", ociContext.realm(), ociContext.regionCode()));
        v.setCompartmentId(compartmentId);
        v.setDisplayName(displayName);
        v.setVaultType(vaultType);
        v.setLifecycleState("ACTIVE");
        v.setTimeCreated(Instant.now().toString());
        v.setWrappingkeyId(Ocids.generate("key", ociContext.realm(), ociContext.regionCode()));
        v.setFreeformTags(freeformTags);
        v.setDefinedTags(definedTags);
        v.setEtag(Etags.newEtag());
        vaults.put(v.getId(), v);
        LOG.infof("createVault %s (%s)", displayName, v.getId());
        return v;
    }

    public StoredVault getVault(String vaultId) {
        return vaults.get(vaultId).orElseThrow(() -> notFound("vault", vaultId));
    }

    public List<StoredVault> listVaults(String compartmentId) {
        require(compartmentId, "compartmentId");
        return vaults.scan(k -> true).stream()
                .filter(v -> compartmentId.equals(v.getCompartmentId()))
                .sorted(Comparator.comparing(StoredVault::getTimeCreated))
                .toList();
    }

    public StoredVault updateVault(String vaultId, String displayName,
                                   Map<String, String> freeformTags,
                                   Map<String, Map<String, Object>> definedTags,
                                   String ifMatch) {
        StoredVault v = getVault(vaultId);
        Etags.checkIfMatch(ifMatch, v.getEtag());
        if (displayName != null) {
            v.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            v.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            v.setDefinedTags(definedTags);
        }
        v.setEtag(Etags.newEtag());
        vaults.put(vaultId, v);
        return v;
    }

    public StoredVault scheduleVaultDeletion(String vaultId, String timeOfDeletion, String ifMatch) {
        StoredVault v = getVault(vaultId);
        Etags.checkIfMatch(ifMatch, v.getEtag());
        v.setLifecycleState("PENDING_DELETION");
        v.setTimeOfDeletion(timeOfDeletion != null ? timeOfDeletion
                : Instant.now().plusSeconds(30 * 24 * 3600).toString());
        v.setEtag(Etags.newEtag());
        vaults.put(vaultId, v);
        return v;
    }

    public StoredVault cancelVaultDeletion(String vaultId, String ifMatch) {
        StoredVault v = getVault(vaultId);
        Etags.checkIfMatch(ifMatch, v.getEtag());
        v.setLifecycleState("ACTIVE");
        v.setTimeOfDeletion(null);
        v.setEtag(Etags.newEtag());
        vaults.put(vaultId, v);
        return v;
    }

    public void changeVaultCompartment(String vaultId, String compartmentId, String ifMatch) {
        StoredVault v = getVault(vaultId);
        Etags.checkIfMatch(ifMatch, v.getEtag());
        require(compartmentId, "compartmentId");
        v.setCompartmentId(compartmentId);
        v.setEtag(Etags.newEtag());
        vaults.put(vaultId, v);
    }

    /**
     * Real OCI gives each vault its own management/crypto hostname. The OCI SDKs reject
     * endpoints containing a path ("endpoint must not contain user info, path, query, or
     * fragment" — oci-go-sdk common/client.go), so the emulator cannot encode the vault in
     * a path suffix and instead serves every vault from the single emulator host.
     *
     * <p>Consequence: {@code CreateKey} carries no vaultId, so the key is attached to the
     * caller's compartment vault — see {@link #resolveVaultForCompartment}.
     */
    public String managementEndpoint(String vaultId) {
        return ociContext.baseUrl();
    }

    public String cryptoEndpoint(String vaultId) {
        return ociContext.baseUrl();
    }

    /**
     * Picks the vault a new key belongs to. With one vault per compartment (the normal
     * case) this is exact; with several, the most recently created ACTIVE vault wins.
     */
    String resolveVaultForCompartment(String compartmentId) {
        return vaults.scan(k -> true).stream()
                .filter(v -> compartmentId.equals(v.getCompartmentId()))
                .filter(v -> "ACTIVE".equals(v.getLifecycleState()))
                .max(Comparator.comparing(StoredVault::getTimeCreated))
                .map(StoredVault::getId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "No active vault in compartment " + compartmentId
                                + " — create a vault before creating keys."));
    }

    // ── Keys ───────────────────────────────────────────────────────────────────

    public StoredKey createKey(String vaultId, String compartmentId, String displayName,
                               String algorithm, Integer length, String curveId,
                               String protectionMode,
                               Map<String, String> freeformTags,
                               Map<String, Map<String, Object>> definedTags) {
        require(compartmentId, "compartmentId");
        String resolvedVaultId = vaultId != null ? vaultId : resolveVaultForCompartment(compartmentId);
        getVault(resolvedVaultId);
        require(displayName, "displayName");
        require(algorithm, "keyShape.algorithm");
        if (length == null) {
            throw OciException.missingParameter("Missing required parameter: keyShape.length");
        }
        StoredKey key = new StoredKey();
        key.setId(Ocids.generate("key", ociContext.realm(), ociContext.regionCode()));
        key.setVaultId(resolvedVaultId);
        key.setCompartmentId(compartmentId);
        key.setDisplayName(displayName);
        key.setAlgorithm(algorithm);
        key.setLength(length);
        key.setCurveId(curveId);
        key.setProtectionMode(protectionMode != null ? protectionMode : "HSM");
        key.setLifecycleState("ENABLED");
        key.setTimeCreated(Instant.now().toString());
        key.setEtag(Etags.newEtag());
        addVersion(key);
        keys.put(key.getId(), key);
        LOG.infof("createKey %s (%s, %s-%d)", displayName, key.getId(), algorithm, length);
        return key;
    }

    /** {@code vaultId} may be null: the key already knows its vault. */
    public StoredKey getKey(String vaultId, String keyId) {
        StoredKey key = keys.get(keyId).orElseThrow(() -> notFound("key", keyId));
        if (vaultId != null && !key.getVaultId().equals(vaultId)) {
            throw notFound("key", keyId);
        }
        return key;
    }

    public List<StoredKey> listKeys(String vaultId, String compartmentId) {
        require(compartmentId, "compartmentId");
        return keys.scan(k -> true).stream()
                .filter(key -> vaultId == null || key.getVaultId().equals(vaultId))
                .filter(key -> compartmentId.equals(key.getCompartmentId()))
                .sorted(Comparator.comparing(StoredKey::getTimeCreated))
                .toList();
    }

    public StoredKey updateKey(String vaultId, String keyId, String displayName,
                               Map<String, String> freeformTags,
                               Map<String, Map<String, Object>> definedTags,
                               String ifMatch) {
        StoredKey key = getKey(vaultId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        if (displayName != null) {
            key.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            key.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            key.setDefinedTags(definedTags);
        }
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return key;
    }

    public StoredKey setKeyEnabled(String vaultId, String keyId, boolean enabled, String ifMatch) {
        StoredKey key = getKey(vaultId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        key.setLifecycleState(enabled ? "ENABLED" : "DISABLED");
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return key;
    }

    public StoredKey scheduleKeyDeletion(String vaultId, String keyId, String timeOfDeletion,
                                         String ifMatch) {
        StoredKey key = getKey(vaultId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        key.setLifecycleState("PENDING_DELETION");
        key.setTimeOfDeletion(timeOfDeletion != null ? timeOfDeletion
                : Instant.now().plusSeconds(30 * 24 * 3600).toString());
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return key;
    }

    public StoredKey cancelKeyDeletion(String vaultId, String keyId, String ifMatch) {
        StoredKey key = getKey(vaultId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        key.setLifecycleState("ENABLED");
        key.setTimeOfDeletion(null);
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return key;
    }

    public StoredKeyVersion createKeyVersion(String vaultId, String keyId) {
        StoredKey key = getKey(vaultId, keyId);
        StoredKeyVersion version = addVersion(key);
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return version;
    }

    public StoredKeyVersion getKeyVersion(String vaultId, String keyId, String keyVersionId) {
        return getKey(vaultId, keyId).getVersions().stream()
                .filter(v -> v.getId().equals(keyVersionId))
                .findFirst()
                .orElseThrow(() -> notFound("keyVersion", keyVersionId));
    }

    public List<StoredKeyVersion> listKeyVersions(String vaultId, String keyId) {
        return List.copyOf(getKey(vaultId, keyId).getVersions());
    }

    // ── Key import ─────────────────────────────────────────────────────────────

    /**
     * Real OCI holds one wrapping key per vault and picks the vault from the request
     * hostname. GetWrappingKey carries no vault id, and every vault shares the emulator
     * host (see {@link #managementEndpoint}), so every vault keeps its own
     * {@code wrappingkeyId} but they all share this one RSA key pair. It is generated on
     * first use, so creating a vault does not pay for a 4096-bit key it may never need.
     */
    public synchronized StoredWrappingKey wrappingKey() {
        return wrappingKeys.get(WRAPPING_KEY_SLOT).orElseGet(() -> {
            StoredWrappingKey wrappingKey = new StoredWrappingKey();
            wrappingKey.setEtag(Etags.newEtag());
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(WRAPPING_KEY_BITS);
                KeyPair pair = generator.generateKeyPair();
                wrappingKey.setPublicKeyPem(toPem(pair.getPublic()));
                wrappingKey.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            } catch (Exception e) {
                throw OciException.internalServerError("Wrapping key generation failed: " + e.getMessage());
            }
            wrappingKeys.put(WRAPPING_KEY_SLOT, wrappingKey);
            return wrappingKey;
        });
    }

    /** The vault GetWrappingKey reports: the most recently created ACTIVE vault. */
    public StoredVault wrappingKeyVault() {
        return vaults.scan(k -> true).stream()
                .filter(v -> "ACTIVE".equals(v.getLifecycleState()))
                .max(Comparator.comparing(StoredVault::getTimeCreated))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "No active vault: create a vault before requesting its wrapping key."));
    }

    public StoredKey importKey(String compartmentId, String displayName, String algorithm,
                               Integer length, String curveId, String protectionMode,
                               String wrappedKeyMaterial, String wrappingAlgorithm,
                               Map<String, String> freeformTags,
                               Map<String, Map<String, Object>> definedTags) {
        require(compartmentId, "compartmentId");
        String vaultId = resolveVaultForCompartment(compartmentId);
        require(displayName, "displayName");
        require(algorithm, "keyShape.algorithm");
        if (length == null) {
            throw OciException.missingParameter("Missing required parameter: keyShape.length");
        }
        require(wrappedKeyMaterial, "wrappedImportKey.keyMaterial");
        require(wrappingAlgorithm, "wrappedImportKey.wrappingAlgorithm");
        if (protectionMode != null && !"HSM".equals(protectionMode) && !"SOFTWARE".equals(protectionMode)) {
            throw OciException.invalidParameter("Unsupported protectionMode for ImportKey: " + protectionMode);
        }
        if ("ECDSA".equals(algorithm)) {
            throw OciException.invalidParameter("ECDSA keys can't be imported");
        }
        if (!"AES".equals(algorithm)) {
            throw OciException.notImplemented(
                    "Importing " + algorithm + " key material is not supported by the emulator yet");
        }
        if (length != 16 && length != 24 && length != 32) {
            throw OciException.invalidParameter(
                    "keyShape.length for AES must be 16, 24 or 32 bytes, not " + length);
        }
        byte[] material = unwrap(wrappedKeyMaterial, wrappingAlgorithm);
        if (material.length != length) {
            throw OciException.invalidParameter("Key material is " + material.length
                    + " bytes but keyShape.length is " + length);
        }

        StoredKey key = new StoredKey();
        key.setId(Ocids.generate("key", ociContext.realm(), ociContext.regionCode()));
        key.setVaultId(vaultId);
        key.setCompartmentId(compartmentId);
        key.setDisplayName(displayName);
        key.setAlgorithm(algorithm);
        key.setLength(length);
        key.setCurveId(curveId);
        key.setProtectionMode(protectionMode != null ? protectionMode : "HSM");
        key.setLifecycleState("ENABLED");
        key.setTimeCreated(Instant.now().toString());
        key.setFreeformTags(freeformTags);
        key.setDefinedTags(definedTags);
        key.setEtag(Etags.newEtag());
        StoredKeyVersion version = newVersion("EXTERNAL");
        version.setKeyMaterial(Base64.getEncoder().encodeToString(material));
        key.getVersions().add(version);
        key.setCurrentKeyVersionId(version.getId());
        keys.put(key.getId(), key);
        LOG.infov("importKey {0} ({1}, {2}-{3})", displayName, key.getId(), algorithm, length);
        return key;
    }

    public StoredKeyVersion importKeyVersion(String keyId) {
        if ("ECDSA".equals(getKey(null, keyId).getAlgorithm())) {
            throw OciException.invalidParameter("ECDSA keys can't be imported");
        }
        throw OciException.notImplemented("ImportKeyVersion is not supported by the emulator yet");
    }

    /**
     * RSA_OAEP_SHA256 is the material encrypted directly under the wrapping key.
     * RSA_OAEP_AES_SHA256 is a temporary AES key encrypted under the wrapping key,
     * followed by the material wrapped with that AES key (RFC 5649 key wrap with padding).
     * Both use RSA-OAEP with SHA-256 for the digest and MGF1.
     */
    private byte[] unwrap(String wrappedKeyMaterial, String wrappingAlgorithm) {
        byte[] wrapped = decodeBase64(wrappedKeyMaterial, "wrappedImportKey.keyMaterial");
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, wrappingPrivateKey(), OAEP_SHA256);
            return switch (wrappingAlgorithm) {
                case "RSA_OAEP_SHA256" -> rsa.doFinal(wrapped);
                case "RSA_OAEP_AES_SHA256" -> {
                    int rsaBlock = WRAPPING_KEY_BITS / 8;
                    if (wrapped.length <= rsaBlock) {
                        throw OciException.invalidParameter(
                                "keyMaterial is too short for RSA_OAEP_AES_SHA256");
                    }
                    byte[] aesKey = rsa.doFinal(Arrays.copyOfRange(wrapped, 0, rsaBlock));
                    Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
                    kwp.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
                    yield kwp.doFinal(Arrays.copyOfRange(wrapped, rsaBlock, wrapped.length));
                }
                default -> throw OciException.invalidParameter(
                        "Unsupported wrappingAlgorithm: " + wrappingAlgorithm);
            };
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw OciException.invalidParameter(
                    "Unable to unwrap keyMaterial with " + wrappingAlgorithm + ": " + e.getMessage());
        }
    }

    private PrivateKey wrappingPrivateKey() throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(wrappingKey().getPrivateKey())));
    }

    private static String toPem(PublicKey publicKey) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    // ── Crypto data plane ──────────────────────────────────────────────────────

    public record EncryptResult(String ciphertext, String keyId, String keyVersionId) {
    }

    public EncryptResult encrypt(String vaultId, String keyId, String plaintextBase64,
                                 String keyVersionId) {
        StoredKey key = requireUsable(vaultId, keyId);
        StoredKeyVersion version = keyVersionId != null
                ? getKeyVersion(vaultId, keyId, keyVersionId)
                : currentVersion(key);
        byte[] plaintext = decodeBase64(plaintextBase64, "plaintext");
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(key, version), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            // Envelope: keyVersionId|iv|ciphertext so decrypt can resolve the version.
            byte[] versionBytes = version.getId().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(4 + versionBytes.length + iv.length + ct.length);
            buffer.putInt(versionBytes.length).put(versionBytes).put(iv).put(ct);
            return new EncryptResult(Base64.getEncoder().encodeToString(buffer.array()),
                    key.getId(), version.getId());
        } catch (Exception e) {
            throw OciException.invalidParameter("Unable to encrypt: " + e.getMessage());
        }
    }

    public record DecryptResult(String plaintext, String plaintextChecksum,
                                String keyId, String keyVersionId) {
    }

    public DecryptResult decrypt(String vaultId, String keyId, String ciphertextBase64) {
        StoredKey key = requireUsable(vaultId, keyId);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(decodeBase64(ciphertextBase64, "ciphertext"));
            byte[] versionBytes = new byte[buffer.getInt()];
            buffer.get(versionBytes);
            String versionId = new String(versionBytes, StandardCharsets.UTF_8);
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] ct = new byte[buffer.remaining()];
            buffer.get(ct);
            StoredKeyVersion version = getKeyVersion(vaultId, keyId, versionId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(key, version), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ct);
            return new DecryptResult(Base64.getEncoder().encodeToString(plaintext),
                    crc32(plaintext), key.getId(), versionId);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw OciException.invalidParameter("Unable to decrypt: " + e.getMessage());
        }
    }

    public record GeneratedKeyResult(String ciphertext, String plaintext, String plaintextChecksum) {
    }

    public GeneratedKeyResult generateDataEncryptionKey(String vaultId, String keyId,
                                                        boolean includePlaintextKey,
                                                        int lengthBytes) {
        requireUsable(vaultId, keyId);
        byte[] material = new byte[lengthBytes];
        RANDOM.nextBytes(material);
        String materialBase64 = Base64.getEncoder().encodeToString(material);
        EncryptResult wrapped = encrypt(vaultId, keyId, materialBase64, null);
        return new GeneratedKeyResult(wrapped.ciphertext(),
                includePlaintextKey ? materialBase64 : null, crc32(material));
    }

    public record SignResult(String signature, String keyId, String keyVersionId,
                             String signingAlgorithm) {
    }

    public SignResult sign(String vaultId, String keyId, String messageBase64,
                           String signingAlgorithm, String keyVersionId) {
        StoredKey key = requireUsable(vaultId, keyId);
        StoredKeyVersion version = keyVersionId != null
                ? getKeyVersion(vaultId, keyId, keyVersionId)
                : currentVersion(key);
        try {
            Signature signature = Signature.getInstance(jcaSignature(signingAlgorithm));
            signature.initSign(privateKey(key, version));
            signature.update(decodeBase64(messageBase64, "message"));
            return new SignResult(Base64.getEncoder().encodeToString(signature.sign()),
                    key.getId(), version.getId(), signingAlgorithm);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw OciException.invalidParameter("Unable to sign: " + e.getMessage());
        }
    }

    public boolean verify(String vaultId, String keyId, String keyVersionId, String signatureBase64,
                          String messageBase64, String signingAlgorithm) {
        StoredKey key = requireUsable(vaultId, keyId);
        StoredKeyVersion version = getKeyVersion(vaultId, keyId, keyVersionId);
        try {
            Signature signature = Signature.getInstance(jcaSignature(signingAlgorithm));
            signature.initVerify(publicKey(key, version));
            signature.update(decodeBase64(messageBase64, "message"));
            return signature.verify(decodeBase64(signatureBase64, "signature"));
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private StoredKeyVersion newVersion(String origin) {
        StoredKeyVersion version = new StoredKeyVersion();
        version.setId(Ocids.generate("keyversion", ociContext.realm(), ociContext.regionCode()));
        version.setTimeCreated(Instant.now().toString());
        version.setLifecycleState("ENABLED");
        version.setOrigin(origin);
        return version;
    }

    private StoredKeyVersion addVersion(StoredKey key) {
        StoredKeyVersion version = newVersion("INTERNAL");
        try {
            switch (key.getAlgorithm()) {
                case "AES" -> {
                    KeyGenerator generator = KeyGenerator.getInstance("AES");
                    generator.init(key.getLength() * 8);
                    version.setKeyMaterial(Base64.getEncoder()
                            .encodeToString(generator.generateKey().getEncoded()));
                }
                case "RSA" -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(key.getLength() * 8);
                    storeKeyPair(version, generator.generateKeyPair());
                }
                case "ECDSA" -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec(jcaCurve(key.getCurveId())));
                    storeKeyPair(version, generator.generateKeyPair());
                }
                default -> throw OciException.invalidParameter(
                        "Unsupported key algorithm: " + key.getAlgorithm());
            }
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw OciException.internalServerError("Key generation failed: " + e.getMessage());
        }
        key.getVersions().add(version);
        key.setCurrentKeyVersionId(version.getId());
        return version;
    }

    private static void storeKeyPair(StoredKeyVersion version, KeyPair pair) {
        version.setKeyMaterial(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        version.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    private StoredKey requireUsable(String vaultId, String keyId) {
        require(keyId, "keyId");
        StoredKey key = getKey(vaultId, keyId);
        if (!"ENABLED".equals(key.getLifecycleState())) {
            throw OciException.conflict("Key " + keyId + " is " + key.getLifecycleState());
        }
        return key;
    }

    private StoredKeyVersion currentVersion(StoredKey key) {
        return key.getVersions().stream()
                .filter(v -> v.getId().equals(key.getCurrentKeyVersionId()))
                .findFirst()
                .orElseThrow(() -> OciException.internalServerError("Key has no current version"));
    }

    private static SecretKey aesKey(StoredKey key, StoredKeyVersion version) {
        if (!"AES".equals(key.getAlgorithm())) {
            throw OciException.invalidParameter("Key algorithm " + key.getAlgorithm()
                    + " does not support encrypt/decrypt in the emulator");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(version.getKeyMaterial()), "AES");
    }

    private static PrivateKey privateKey(StoredKey key, StoredKeyVersion version) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA".equals(key.getAlgorithm()) ? "RSA" : "EC");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(version.getKeyMaterial())));
    }

    private static PublicKey publicKey(StoredKey key, StoredKeyVersion version) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA".equals(key.getAlgorithm()) ? "RSA" : "EC");
        return factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(version.getPublicKey())));
    }

    private static String jcaSignature(String ociAlgorithm) {
        if (ociAlgorithm == null) {
            throw OciException.missingParameter("Missing required parameter: signingAlgorithm");
        }
        return switch (ociAlgorithm) {
            case "SHA_224_RSA_PKCS1_V1_5" -> "SHA224withRSA";
            case "SHA_256_RSA_PKCS1_V1_5" -> "SHA256withRSA";
            case "SHA_384_RSA_PKCS1_V1_5" -> "SHA384withRSA";
            case "SHA_512_RSA_PKCS1_V1_5" -> "SHA512withRSA";
            case "ECDSA_SHA_256" -> "SHA256withECDSA";
            case "ECDSA_SHA_384" -> "SHA384withECDSA";
            case "ECDSA_SHA_512" -> "SHA512withECDSA";
            default -> throw OciException.invalidParameter(
                    "Unsupported signingAlgorithm: " + ociAlgorithm);
        };
    }

    private static String jcaCurve(String curveId) {
        return switch (curveId != null ? curveId : "NIST_P256") {
            case "NIST_P256" -> "secp256r1";
            case "NIST_P384" -> "secp384r1";
            case "NIST_P521" -> "secp521r1";
            default -> throw OciException.invalidParameter("Unsupported curveId: " + curveId);
        };
    }

    private static byte[] decodeBase64(String value, String field) {
        if (value == null) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter(field + " must be base64-encoded");
        }
    }

    private static String crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return Long.toHexString(crc.getValue());
    }


    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
    }

    private static OciException notFound(String kind, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + kind + " " + id);
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return Stream.concat(
                CompartmentUsage.live(vaults, tenancyId, compartmentId, StoredVault::getCompartmentId,
                        StoredVault::getLifecycleState, v -> "vault " + v.getId()),
                CompartmentUsage.live(keys, tenancyId, compartmentId, StoredKey::getCompartmentId,
                        StoredKey::getLifecycleState, k -> "key " + k.getId())).toList();
    }
}
