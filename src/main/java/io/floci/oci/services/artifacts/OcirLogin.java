package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.config.EmulatorConfig.SignatureVerificationMode;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.AuthTokenResolver;
import io.floci.oci.services.objectstorage.ObjectStorageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * OCIR {@code docker login}: the registry's Bearer token endpoint and the tokens it issues.
 *
 * <p>With {@code floci-oci.auth.signature-verification=strict} the endpoint takes Basic credentials
 * as OCI documents them (Registry/Tasks/registrypushingimagesusingthedockercli.htm): username
 * {@code <tenancy-namespace>/<username>}, or {@code <tenancy-namespace>/<domain-name>/<username>} for
 * a federated or identity-domain user, and an Identity auth token of that user as the password. The
 * registry then accepts only the Bearer tokens it issued, for the namespace's tenancy. floci-oci has no
 * identity domains, so the domain segment is accepted and not checked.
 *
 * <p>In every other mode the endpoint issues a token to anyone and the registry accepts any Bearer
 * token, since nothing else about the caller is checked either.
 */
@ApplicationScoped
public class OcirLogin {

    static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    private final boolean strict;
    private final AuthTokenResolver authTokens;
    private final Function<String, String> namespaceOf;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();

    @Inject
    public OcirLogin(EmulatorConfig config, AuthTokenResolver authTokens, ObjectStorageService objectStorage) {
        this(config.auth().signatureVerification() == SignatureVerificationMode.STRICT, authTokens,
                objectStorage::namespaceOf, Clock.systemUTC());
    }

    OcirLogin(boolean strict, AuthTokenResolver authTokens, Function<String, String> namespaceOf, Clock clock) {
        this.strict = strict;
        this.authTokens = authTokens;
        this.namespaceOf = namespaceOf;
        this.clock = clock;
    }

    /** A registry token the endpoint issued, for the tenancy whose user logged in (null outside strict mode). */
    record Issued(String tenancyId, Instant expires) {
    }

    /** The outcome of a token request: a token, or why none was issued. */
    record Login(String token, String rejection) {

        boolean isRejected() {
            return rejection != null;
        }
    }

    boolean strict() {
        return strict;
    }

    /** Answers the token endpoint for a request with this {@code Authorization} header. */
    Login login(String authorization) {
        if (!strict) {
            return new Login(newToken(null), null);
        }
        Optional<Credentials> credentials = Credentials.parse(authorization);
        if (credentials.isEmpty()) {
            return new Login(null, "Basic credentials <tenancy-namespace>/<username> and an auth token are required");
        }
        Credentials c = credentials.get();
        Optional<AuthContext> caller = authTokens.resolve(tenancy -> c.namespace().equals(namespaceOf.apply(tenancy)),
                c.userName(), c.password());
        if (caller.isEmpty()) {
            return new Login(null, "the username or auth token is not valid");
        }
        return new Login(newToken(caller.get().tenancyId()), null);
    }

    /** Whether a registry request may go on with this {@code Bearer} token (always, outside strict mode). */
    boolean accepts(String bearer) {
        return !strict || issued(bearer).isPresent();
    }

    /** Whether {@code bearer} was issued to a user of {@code tenancyId} (always, outside strict mode). */
    boolean allows(String bearer, String tenancyId) {
        return !strict || issued(bearer).map(i -> tenancyId.equals(i.tenancyId())).orElse(false);
    }

    private Optional<Issued> issued(String bearer) {
        if (bearer == null) {
            return Optional.empty();
        }
        Issued found = issued.get(bearer);
        if (found == null || !found.expires().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    private String newToken(String tenancyId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (strict) {
            Instant now = clock.instant();
            issued.values().removeIf(i -> !i.expires().isAfter(now));
            issued.put(token, new Issued(tenancyId, now.plus(TOKEN_LIFETIME)));
        }
        return token;
    }

    /** {@code Basic base64(<namespace>/[<domain>/]<user>:<password>)}. */
    record Credentials(String namespace, String userName, String password) {

        static Optional<Credentials> parse(String authorization) {
            if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
                return Optional.empty();
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException expected) {
                // Not base64: the same as no credentials, which the caller reports.
                return Optional.empty();
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return Optional.empty();
            }
            String[] user = decoded.substring(0, colon).split("/", -1);
            if (user.length < 2 || user.length > 3 || user[0].isEmpty() || user[user.length - 1].isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Credentials(user[0], user[user.length - 1], decoded.substring(colon + 1)));
        }
    }
}
