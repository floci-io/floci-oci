package io.floci.oci.services.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.auth.AccessKeyResolver;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.identity.model.StoredCustomerSecretKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Identity Customer Secret Keys ({@code /20160918/users/{userId}/customerSecretKeys}), the
 * credentials of the Amazon S3 Compatibility API. Kept beside, not inside, {@link IdentityService}
 * and in the same tenancy-wide store family, since users are global resources.
 */
@ApplicationScoped
public class CustomerSecretKeyService implements AccessKeyResolver {

    private final StorageBackend<String, StoredCustomerSecretKey> keys;
    private final IdentityService identity;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public CustomerSecretKeyService(StorageFactory storageFactory, IdentityService identity) {
        this.identity = identity;
        this.keys = storageFactory.createGlobal("identity", "identity-customer-secret-keys.json",
                new TypeReference<Map<String, StoredCustomerSecretKey>>() {});
    }

    CustomerSecretKeyService(StorageBackend<String, StoredCustomerSecretKey> keys, IdentityService identity) {
        this.keys = keys;
        this.identity = identity;
    }

    public record CreatedKey(StoredCustomerSecretKey key, String secret) {
    }

    public synchronized CreatedKey create(String userId, String displayName) {
        identity.getUser(userId);
        if (displayName == null || displayName.isBlank()) {
            throw OciException.missingParameter("displayName is required");
        }
        byte[] accessKey = new byte[20];
        byte[] secret = new byte[33];
        random.nextBytes(accessKey);
        random.nextBytes(secret);
        StoredCustomerSecretKey key = new StoredCustomerSecretKey();
        key.setId(HexFormat.of().formatHex(accessKey));
        key.setUserId(userId);
        key.setDisplayName(displayName);
        key.setTimeCreated(Instant.now().toString());
        key.setLifecycleState("ACTIVE");
        key.setEtag(Etags.newEtag());
        keys.put(key.getId(), key);
        return new CreatedKey(key, Base64.getEncoder().encodeToString(secret));
    }

    public List<StoredCustomerSecretKey> list(String userId) {
        identity.getUser(userId);
        return keys.scan(k -> true).stream()
                .filter(k -> userId.equals(k.getUserId()))
                .sorted(Comparator.comparing(StoredCustomerSecretKey::getTimeCreated))
                .toList();
    }

    public synchronized StoredCustomerSecretKey update(String userId, String keyId, String displayName,
                                                       String ifMatch) {
        StoredCustomerSecretKey key = get(userId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        if (displayName != null && !displayName.isBlank()) {
            key.setDisplayName(displayName);
        }
        key.setEtag(Etags.newEtag());
        keys.put(keyId, key);
        return key;
    }

    public synchronized void delete(String userId, String keyId, String ifMatch) {
        StoredCustomerSecretKey key = get(userId, keyId);
        Etags.checkIfMatch(ifMatch, key.getEtag());
        keys.delete(keyId);
    }

    /** Removes every key of a deleted user, so its access keys stop resolving. */
    public synchronized void deleteAllOf(String userId) {
        keys.scan(k -> true).stream()
                .filter(k -> userId.equals(k.getUserId()))
                .forEach(k -> keys.delete(k.getId()));
    }

    private StoredCustomerSecretKey get(String userId, String keyId) {
        identity.getUser(userId);
        return keys.get(keyId)
                .filter(k -> userId.equals(k.getUserId()))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: customer secret key " + keyId));
    }

    /** Looks the access key up in every tenancy: an S3 request names no tenancy until this answers. */
    @Override
    public Optional<AuthContext> resolve(String accessKey) {
        for (Map.Entry<String, Map<String, StoredCustomerSecretKey>> partition
                : TenancyAwareStorageBackend.entriesByPartition(keys).entrySet()) {
            StoredCustomerSecretKey key = partition.getValue().get(accessKey);
            if (key != null && "ACTIVE".equals(key.getLifecycleState())) {
                return Optional.of(new AuthContext(TenancyAwareStorageBackend.tenancyOf(partition.getKey()),
                        key.getUserId(), null));
            }
        }
        return Optional.empty();
    }
}
