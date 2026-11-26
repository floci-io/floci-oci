package io.floci.oci.services.oke;

import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.OciSignatureParser;
import io.floci.oci.core.auth.RequestAuthenticator;
import io.floci.oci.core.auth.SignatureVerifier;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the bearer tokens {@code oci ce cluster generate-token} produces. A token is
 * {@code base64url(<signed GET URL>)}, where the URL is
 * {@code https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>?authorization=<Signature ...>&date=<RFC 1123>}
 * (oci-cli {@code containerengine_cli_extended.py}).
 *
 * <p>This checks the token's shape and binds it to the cluster: the URL must name this cluster and
 * its region, the Signature must cover {@code date (request-target) host}, its keyId tenancy (or, for
 * {@code --auth security_token}, the security token's {@code tenant} claim) must own the cluster,
 * and the date must be recent. The signature itself is then verified as any other signed request is,
 * by {@link RequestAuthenticator}: over {@code date} (the URL's {@code date}), {@code (request-target)}
 * ({@code get /cluster_request/<cluster-ocid>}, the URL before the CLI adds its query) and {@code host}.
 * With {@code floci-oci.auth.signature-verification=off}, a well-formed forged token passes, the same
 * trust floci-oci then gives every other signed request.
 */
@ApplicationScoped
public class OkeTokenValidator {

    private static final Logger LOG = Logger.getLogger(OkeTokenValidator.class);
    static final int MAX_TOKEN_LENGTH = 4096;
    private static final String REQUIRED_SIGNED_HEADERS = "date (request-target) host";
    private static final String CLUSTER_REQUEST_PATH = "/cluster_request/";
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of("authorization", "date", "opc-obo-token");

    private final SignatureVerifier dates;
    private final RequestAuthenticator authenticator;

    @Inject
    public OkeTokenValidator(RequestAuthenticator authenticator) {
        this(Clock.systemUTC(), authenticator);
    }

    OkeTokenValidator(Clock clock, RequestAuthenticator authenticator) {
        this.dates = new SignatureVerifier(clock);
        this.authenticator = authenticator;
    }

    /** The caller identity the token was signed with, when the token is valid for {@code cluster}. */
    public Optional<AuthContext> validate(String token, StoredOkeCluster cluster) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return reject(cluster, "token is missing or longer than " + MAX_TOKEN_LENGTH);
        }
        Optional<URI> url = decode(token);
        if (url.isEmpty()) {
            return reject(cluster, "token is not a base64-encoded URL");
        }
        URI uri = url.get();
        String expectedHost = "containerengine." + cluster.getRegion() + ".oraclecloud.com";
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 || !expectedHost.equalsIgnoreCase(uri.getHost())) {
            return reject(cluster, "token URL is not https://" + expectedHost);
        }
        if (!(CLUSTER_REQUEST_PATH + cluster.getId()).equals(uri.getRawPath())) {
            return reject(cluster, "token URL names another cluster");
        }
        Optional<Map<String, String>> query = queryParameters(uri.getRawQuery());
        if (query.isEmpty() || !query.get().containsKey("authorization") || !query.get().containsKey("date")) {
            return reject(cluster, "token URL lacks a single authorization and date");
        }
        Map<String, String> signature = OciSignatureParser.parameters(query.get().get("authorization"));
        if (!"rsa-sha256".equals(signature.get("algorithm"))
                || !REQUIRED_SIGNED_HEADERS.equals(signature.get("headers"))
                || signature.getOrDefault("signature", "").isBlank()) {
            return reject(cluster, "authorization is not an rsa-sha256 Signature over " + REQUIRED_SIGNED_HEADERS);
        }
        Optional<AuthContext> caller = OciSignatureParser.parse(query.get().get("authorization"));
        if (caller.isEmpty() || !caller.get().tenancyId().equals(cluster.getTenancyId())) {
            return reject(cluster, "keyId tenancy does not own the cluster");
        }
        String date = query.get().get("date");
        if (!dates.isCurrent(date)) {
            return reject(cluster, "date is not within " + SignatureVerifier.MAX_CLOCK_SKEW.toMinutes()
                    + " minutes of now");
        }
        RequestAuthenticator.Authentication outcome = authenticator.authenticate(caller.get(),
                query.get().get("authorization"), "GET", uri.getRawPath(), name -> switch (name) {
                    case "date" -> date;
                    case "host" -> uri.getRawAuthority();
                    default -> null;
                });
        if (outcome.isRejected()) {
            return reject(cluster, outcome.rejection());
        }
        return Optional.of(outcome.caller());
    }

    private Optional<URI> decode(String token) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException notUrlSafe) {
            try {
                bytes = Base64.getDecoder().decode(token);
            } catch (IllegalArgumentException notBase64) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(new URI(new String(bytes, StandardCharsets.UTF_8)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Form-decoded query parameters; empty when one is unknown or appears twice. */
    private static Optional<Map<String, String>> queryParameters(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.of(params);
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            if (!ALLOWED_QUERY_PARAMS.contains(name) || params.putIfAbsent(name, value) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(params);
    }

    private static Optional<AuthContext> reject(StoredOkeCluster cluster, String reason) {
        LOG.debugv("Rejected token for OKE cluster {0}: {1}", cluster.getId(), reason);
        return Optional.empty();
    }
}
