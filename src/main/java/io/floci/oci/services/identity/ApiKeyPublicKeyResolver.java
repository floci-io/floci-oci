package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.PrincipalKind;
import io.floci.oci.core.auth.PublicKeyResolver;
import io.floci.oci.core.auth.PublicKeys;
import io.floci.oci.services.identity.model.StoredApiKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.PublicKey;
import java.util.Optional;

/**
 * The public key of an API-key caller: the ACTIVE key uploaded for the keyId's user, in the keyId's
 * tenancy, with the keyId's fingerprint. Nothing resolves while Identity is disabled, so only trusted
 * public keys count then.
 */
@ApplicationScoped
public class ApiKeyPublicKeyResolver implements PublicKeyResolver {

    private static final Logger LOG = Logger.getLogger(ApiKeyPublicKeyResolver.class);

    private final ApiKeyService apiKeys;
    private final EmulatorConfig config;

    @Inject
    public ApiKeyPublicKeyResolver(ApiKeyService apiKeys, EmulatorConfig config) {
        this.apiKeys = apiKeys;
        this.config = config;
    }

    @Override
    public Optional<PublicKey> resolve(AuthContext caller) {
        if (caller.kind() != PrincipalKind.API_KEY || !config.services().identity().enabled()) {
            return Optional.empty();
        }
        Optional<StoredApiKey> key = apiKeys.find(caller.tenancyId(), caller.userId(),
                        PublicKeys.normalizeFingerprint(caller.fingerprint()))
                .filter(found -> "ACTIVE".equals(found.getLifecycleState()));
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PublicKeys.fromPem(key.get().getKeyValue()));
        } catch (IllegalArgumentException e) {
            LOG.warnv("Stored API key {0} is not a readable RSA key: {1}", key.get().getKeyId(), e.getMessage());
            return Optional.empty();
        }
    }
}
