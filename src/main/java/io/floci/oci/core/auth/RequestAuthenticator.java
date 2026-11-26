package io.floci.oci.core.auth;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.config.EmulatorConfig.SignatureVerificationMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Applies {@code floci-oci.auth.signature-verification} to a parsed caller.
 *
 * <ul>
 *   <li>{@code off}: the caller is only parsed, as before; nothing is verified.</li>
 *   <li>{@code known-keys}: a security-token caller, and an API-key caller whose key is known (uploaded
 *       through Identity, or listed in {@code trusted-public-keys}), must verify. An unknown API key
 *       passes unverified.</li>
 *   <li>{@code strict}: an API key must be known and verify.</li>
 * </ul>
 *
 * A trusted public key verifies any keyId whose fingerprint is that key's, whatever tenancy and user it
 * names, so the first {@code UploadApiKey} of a strict emulator can be signed.
 */
@ApplicationScoped
public class RequestAuthenticator {

    private static final Logger LOG = Logger.getLogger(RequestAuthenticator.class);

    private final SignatureVerificationMode mode;
    private final Map<String, PublicKey> trustedKeys;
    private final PublicKeyResolver uploadedKeys;
    private final SignatureVerifier verifier;

    @Inject
    public RequestAuthenticator(EmulatorConfig config, Instance<PublicKeyResolver> resolvers) {
        this(config.auth().signatureVerification(),
                loadTrustedKeys(config.auth().trustedPublicKeys().orElse(List.of())),
                caller -> resolvers.stream()
                        .map(resolver -> resolver.resolve(caller))
                        .flatMap(Optional::stream)
                        .findFirst(),
                Clock.systemUTC());
    }

    /** An authenticator outside CDI, for tests: {@code uploadedKeys} stands in for Identity. */
    public RequestAuthenticator(SignatureVerificationMode mode, List<PublicKey> trustedKeys,
                         PublicKeyResolver uploadedKeys, Clock clock) {
        this.mode = mode;
        this.trustedKeys = new LinkedHashMap<>();
        for (PublicKey key : trustedKeys) {
            this.trustedKeys.put(PublicKeys.fingerprint(key), key);
        }
        this.uploadedKeys = uploadedKeys;
        this.verifier = new SignatureVerifier(clock);
    }

    /** Loads the trusted keys at startup, so an unreadable path stops the emulator instead of a request. */
    void onStart(@Observes StartupEvent ev) {
        if (mode != SignatureVerificationMode.OFF) {
            LOG.infov("Request signature verification: {0}, with {1} trusted public key(s)",
                    mode.name().toLowerCase(Locale.ROOT).replace('_', '-'), trustedKeys.size());
        }
    }

    public SignatureVerificationMode mode() {
        return mode;
    }

    /** The outcome for one request: the caller to use, or why the request is not authenticated. */
    public record Authentication(AuthContext caller, String rejection) {

        static Authentication accepted(AuthContext caller) {
            return new Authentication(caller, null);
        }

        static Authentication rejected(String reason) {
            return new Authentication(null, reason);
        }

        public boolean isRejected() {
            return rejection != null;
        }
    }

    /**
     * Authenticates {@code caller}, parsed from {@code authorization}, over a request.
     *
     * @param requestTarget the raw path and, when there is one, {@code ?} and the raw query
     * @param headers       a header's first value by lowercase name, or null when it is absent
     */
    public Authentication authenticate(AuthContext caller, String authorization, String method,
                                       String requestTarget, Function<String, String> headers) {
        if (mode == SignatureVerificationMode.OFF) {
            return Authentication.accepted(caller);
        }
        Optional<PublicKey> key = keyOf(caller, authorization);
        if (key.isEmpty()) {
            if (caller.kind() == PrincipalKind.SECURITY_TOKEN) {
                return Authentication.rejected("the security token carries no RSA jwk claim");
            }
            if (mode == SignatureVerificationMode.STRICT) {
                return Authentication.rejected("the signing key is not known");
            }
            return Authentication.accepted(caller);
        }
        SignatureVerifier.Result result = verifier.verify(method, requestTarget, headers,
                OciSignatureParser.parameters(authorization), key.get());
        if (!result.verified()) {
            return Authentication.rejected(result.failure());
        }
        return Authentication.accepted(caller.asVerified());
    }

    private Optional<PublicKey> keyOf(AuthContext caller, String authorization) {
        return switch (caller.kind()) {
            case SECURITY_TOKEN -> OciSignatureParser.securityTokenPublicKey(authorization);
            case API_KEY -> Optional.ofNullable(trustedKeys.get(PublicKeys.normalizeFingerprint(caller.fingerprint())))
                    .or(() -> uploadedKeys.resolve(caller));
            case UNKNOWN -> Optional.empty();
        };
    }

    static List<PublicKey> loadTrustedKeys(List<String> paths) {
        return paths.stream()
                .filter(path -> !path.isBlank())
                .map(RequestAuthenticator::loadTrustedKey)
                .toList();
    }

    private static PublicKey loadTrustedKey(String path) {
        String pem;
        try {
            pem = Files.readString(Path.of(path.trim()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("floci-oci.auth.trusted-public-keys: cannot read " + path, e);
        }
        try {
            return PublicKeys.fromPem(pem);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("floci-oci.auth.trusted-public-keys: " + path + " is "
                    + e.getMessage(), e);
        }
    }
}
