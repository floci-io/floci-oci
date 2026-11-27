package io.floci.oci.services.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.AuthTokenResolver;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.identity.model.StoredAuthToken;
import io.floci.oci.services.identity.model.StoredUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Identity auth tokens ({@code /20160918/users/{userId}/authTokens}). Kept beside {@link IdentityService},
 * in the same tenancy-wide store family as API keys and Customer Secret Keys, since users are global
 * resources.
 */
@ApplicationScoped
public class AuthTokenService implements AuthTokenResolver {

    /** "A user can have up to two auth tokens at a time" (oci-go-sdk/identity/auth_token.go). */
    static final int MAX_TOKENS_PER_USER = 2;

    /**
     * OCI documents no token format or length, so this one is floci-oci's: printable characters that
     * need no quoting in a shell, a Docker config or a Kafka JAAS line.
     */
    static final int TOKEN_LENGTH = 20;
    private static final String TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.:()+";

    private final StorageBackend<String, StoredAuthToken> tokens;
    private final IdentityService identity;
    private final OciContext ociContext;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public AuthTokenService(StorageFactory storageFactory, IdentityService identity, OciContext ociContext) {
        this.identity = identity;
        this.ociContext = ociContext;
        this.tokens = storageFactory.createGlobal("identity", "identity-auth-tokens.json",
                new TypeReference<Map<String, StoredAuthToken>>() {});
    }

    AuthTokenService(StorageBackend<String, StoredAuthToken> tokens, IdentityService identity, OciContext ociContext) {
        this.tokens = tokens;
        this.identity = identity;
        this.ociContext = ociContext;
    }

    public record CreatedToken(StoredAuthToken token, String value) {
    }

    /**
     * CreateAuthToken. {@code description} is mandatory "although it can be an empty string"
     * (identity_client.go, CreateAuthToken). The token is ACTIVE at once: CREATING is temporary, and
     * clients wait for ACTIVE.
     */
    public synchronized CreatedToken create(String userId, String description) {
        StoredUser user = identity.getUser(userId);
        if (description == null) {
            throw OciException.missingParameter("description is required");
        }
        if (list(userId).size() >= MAX_TOKENS_PER_USER) {
            throw OciException.limitExceeded("A user can have up to " + MAX_TOKENS_PER_USER + " auth tokens at a time");
        }
        String value = newTokenValue();
        StoredAuthToken token = new StoredAuthToken();
        token.setId(Ocids.generateGlobal("credential", ociContext.realm()));
        token.setUserId(userId);
        token.setUserName(user.getName());
        token.setDescription(description);
        token.setTimeCreated(Instant.now().toString());
        token.setLifecycleState("ACTIVE");
        token.setTokenSha256(sha256(value));
        token.setEtag(Etags.newEtag());
        tokens.put(token.getId(), token);
        return new CreatedToken(token, value);
    }

    public List<StoredAuthToken> list(String userId) {
        identity.getUser(userId);
        return tokens.scan(k -> true).stream()
                .filter(t -> userId.equals(t.getUserId()))
                .sorted(Comparator.comparing(StoredAuthToken::getTimeCreated))
                .toList();
    }

    /** UpdateAuthToken: {@code description} is optional, and an empty one is kept as given. */
    public synchronized StoredAuthToken update(String userId, String tokenId, String description, String ifMatch) {
        StoredAuthToken token = get(userId, tokenId);
        Etags.checkIfMatch(ifMatch, token.getEtag());
        if (description != null) {
            token.setDescription(description);
        }
        token.setEtag(Etags.newEtag());
        tokens.put(tokenId, token);
        return token;
    }

    public synchronized void delete(String userId, String tokenId, String ifMatch) {
        StoredAuthToken token = get(userId, tokenId);
        Etags.checkIfMatch(ifMatch, token.getEtag());
        tokens.delete(tokenId);
    }

    /** Removes every token of a deleted user, so its password stops working. */
    public synchronized void deleteAllOf(String userId) {
        tokens.scan(k -> true).stream()
                .filter(t -> userId.equals(t.getUserId()))
                .forEach(t -> tokens.delete(t.getId()));
    }

    private StoredAuthToken get(String userId, String tokenId) {
        identity.getUser(userId);
        return tokens.get(tokenId)
                .filter(t -> userId.equals(t.getUserId()))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: auth token " + tokenId));
    }

    /** Looks the token up in every tenancy {@code inTenancy} accepts: a login names no tenancy OCID. */
    @Override
    public Optional<AuthContext> resolve(Predicate<String> inTenancy, String userName, String token) {
        if (userName == null || token == null) {
            return Optional.empty();
        }
        byte[] presented = sha256(token).getBytes(StandardCharsets.US_ASCII);
        for (Map.Entry<String, Map<String, StoredAuthToken>> partition
                : TenancyAwareStorageBackend.entriesByPartition(tokens, ociContext.tenancyId()).entrySet()) {
            String tenancy = TenancyAwareStorageBackend.tenancyOf(partition.getKey());
            if (!inTenancy.test(tenancy)) {
                continue;
            }
            for (StoredAuthToken candidate : partition.getValue().values()) {
                if (userName.equals(candidate.getUserName())
                        && "ACTIVE".equals(candidate.getLifecycleState())
                        && MessageDigest.isEqual(presented,
                                candidate.getTokenSha256().getBytes(StandardCharsets.US_ASCII))) {
                    return Optional.of(AuthContext.unknown(tenancy, candidate.getUserId()));
                }
            }
        }
        return Optional.empty();
    }

    private String newTokenValue() {
        StringBuilder value = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            value.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        return value.toString();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw OciException.internalServerError("SHA-256 is unavailable: " + e.getMessage());
        }
    }
}
