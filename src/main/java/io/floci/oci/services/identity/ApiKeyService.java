package io.floci.oci.services.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.identity.model.StoredApiKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;

import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Identity API signing keys ({@code /20160918/users/{userId}/apiKeys}). Kept beside
 * {@link IdentityService}, in the tenancy-wide store family, since users are global resources.
 */
@ApplicationScoped
public class ApiKeyService {

    /** "A user can have a maximum of three keys" (oci-go-sdk/identity/identity_client.go, ListApiKeys). */
    static final int MAX_KEYS_PER_USER = 3;

    private final StorageBackend<String, StoredApiKey> keys;
    private final IdentityService identity;
    private final OciContext ociContext;

    @Inject
    public ApiKeyService(StorageFactory storageFactory, IdentityService identity, OciContext ociContext) {
        this.identity = identity;
        this.ociContext = ociContext;
        this.keys = storageFactory.createGlobal("identity", "identity-api-keys.json",
                new TypeReference<Map<String, StoredApiKey>>() {});
    }

    ApiKeyService(StorageBackend<String, StoredApiKey> keys, IdentityService identity, OciContext ociContext) {
        this.keys = keys;
        this.identity = identity;
        this.ociContext = ociContext;
    }

    /**
     * UploadApiKey. The key "Must be an RSA key in PEM format" (create_api_key_details.go). It is ACTIVE
     * at once: OCI's CREATING state is temporary, and clients wait for ACTIVE.
     */
    public synchronized StoredApiKey upload(String userId, String pem) {
        identity.getUser(userId);
        if (pem == null || pem.isBlank()) {
            throw OciException.missingParameter("key is required");
        }
        String fingerprint = fingerprint(rsaPublicKey(pem));
        if (keys.get(storageKey(userId, fingerprint)).isPresent()) {
            throw OciException.conflict("The user already has an API key with fingerprint " + fingerprint);
        }
        if (list(userId).size() >= MAX_KEYS_PER_USER) {
            throw OciException.limitExceeded("A user can have a maximum of " + MAX_KEYS_PER_USER + " API keys");
        }
        StoredApiKey key = new StoredApiKey();
        key.setKeyId(ociContext.tenancyId() + "/" + userId + "/" + fingerprint);
        key.setKeyValue(pem);
        key.setFingerprint(fingerprint);
        key.setUserId(userId);
        key.setTimeCreated(Instant.now().toString());
        key.setLifecycleState("ACTIVE");
        key.setEtag(Etags.newEtag());
        keys.put(storageKey(userId, fingerprint), key);
        return key;
    }

    public List<StoredApiKey> list(String userId) {
        identity.getUser(userId);
        String prefix = userId + "/";
        return keys.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(StoredApiKey::getTimeCreated))
                .toList();
    }

    public synchronized void delete(String userId, String fingerprint, String ifMatch) {
        identity.getUser(userId);
        StoredApiKey key = keys.get(storageKey(userId, fingerprint))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: API key " + fingerprint));
        Etags.checkIfMatch(ifMatch, key.getEtag());
        keys.delete(storageKey(userId, fingerprint));
    }

    /** Removes every key of a deleted user. */
    public synchronized void deleteAllOf(String userId) {
        String prefix = userId + "/";
        keys.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(keys::delete);
    }

    /** The key a request's {@code keyId} names, in the tenancy it names; for signature verification later. */
    public Optional<StoredApiKey> find(String tenancyId, String userId, String fingerprint) {
        return TenancyAwareStorageBackend.getIn(keys, tenancyId, storageKey(userId, fingerprint));
    }

    private static String storageKey(String userId, String fingerprint) {
        return userId + "/" + fingerprint;
    }

    private static SubjectPublicKeyInfo rsaPublicKey(String pem) {
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        } catch (IOException e) {
            throw OciException.invalidParameter("key must be an RSA public key in PEM format");
        }
        if (parsed instanceof SubjectPublicKeyInfo info
                && PKCSObjectIdentifiers.rsaEncryption.equals(info.getAlgorithm().getAlgorithm())) {
            return info;
        }
        throw OciException.invalidParameter("key must be an RSA public key in PEM format");
    }

    /**
     * The MD5 of the DER-encoded SubjectPublicKeyInfo, as colon-separated lowercase hex pairs: what
     * {@code oci setup} computes (oci-cli/src/oci_cli/cli_setup.py, public_key_to_fingerprint).
     */
    static String fingerprint(SubjectPublicKeyInfo info) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("MD5").digest(info.getEncoded(ASN1Encoding.DER));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw OciException.internalServerError("Could not compute the key fingerprint: " + e.getMessage());
        }
        String hex = HexFormat.of().formatHex(digest);
        StringJoiner joined = new StringJoiner(":");
        for (int i = 0; i < hex.length(); i += 2) {
            joined.add(hex.substring(i, i + 2));
        }
        return joined.toString();
    }

    static String fingerprint(String pem) {
        return fingerprint(rsaPublicKey(pem));
    }
}
