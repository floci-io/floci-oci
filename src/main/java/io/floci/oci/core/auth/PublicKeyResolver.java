package io.floci.oci.core.auth;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Finds the public key an API-key caller signs with: Identity implements it with the keys uploaded
 * through {@code UploadApiKey}. Empty when the key is unknown, or when the service that holds keys is
 * disabled.
 */
public interface PublicKeyResolver {

    Optional<PublicKey> resolve(AuthContext caller);
}
