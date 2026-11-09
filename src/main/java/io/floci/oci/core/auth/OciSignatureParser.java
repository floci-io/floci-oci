package io.floci.oci.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the OCI request-signature {@code Authorization} header
 * (draft-cavage HTTP Signatures as profiled by OCI):
 *
 * <pre>
 * Signature version="1",keyId="&lt;tenancy&gt;/&lt;user&gt;/&lt;fingerprint&gt;",
 *           algorithm="rsa-sha256",headers="…",signature="…"
 * </pre>
 *
 * Session (security token) auth signs with {@code keyId="ST$<jwt>"} instead
 * (oci-python-sdk {@code SecurityTokenSigner}); the caller is then the JWT's {@code tenant}
 * and {@code sub} claims, the same claims oci-cli reads for the session's tenancy
 * ({@code cli_session.py}) and oci-go-sdk returns as {@code TenancyOCID()} and
 * {@code UserOCID()}.
 *
 * Only the structure is validated: neither the signature nor the JWT is verified.
 */
public final class OciSignatureParser {

    private static final Logger LOG = Logger.getLogger(OciSignatureParser.class);
    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final String SECURITY_TOKEN_PREFIX = "ST$";
    private static final ObjectMapper JSON = new ObjectMapper();

    private OciSignatureParser() {
    }

    /**
     * Extracts the caller identity from an Authorization header.
     * Returns empty when the header is absent, not a Signature scheme,
     * or its keyId is neither {@code tenancy/user/fingerprint} nor a security token
     * carrying {@code tenant} and {@code sub} claims.
     */
    public static Optional<AuthContext> parse(String authorizationHeader) {
        String keyId = parameters(authorizationHeader).get("keyid");
        if (keyId == null) {
            return Optional.empty();
        }
        if (keyId.startsWith(SECURITY_TOKEN_PREFIX)) {
            return fromSecurityToken(keyId.substring(SECURITY_TOKEN_PREFIX.length()));
        }
        String[] parts = keyId.split("/");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AuthContext(parts[0], parts[1], parts[2]));
    }

    /**
     * The caller named by an unverified security-token JWT, or empty when it is not a JWT or lacks a
     * {@code tenant} or {@code sub} claim. Resource principal tokens carry {@code res_tenant}
     * instead, so they stay unidentified, as before. A security token has no key fingerprint.
     */
    private static Optional<AuthContext> fromSecurityToken(String token) {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            return Optional.empty();
        }
        JsonNode claims;
        try {
            claims = JSON.readTree(Base64.getUrlDecoder().decode(segments[1]));
        } catch (IllegalArgumentException | IOException e) {
            LOG.debugv("Security token keyId has an unreadable JWT payload: {0}", e.getMessage());
            return Optional.empty();
        }
        String tenant = textClaim(claims, "tenant");
        String subject = textClaim(claims, "sub");
        if (tenant == null || subject == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthContext(tenant, subject, null));
    }

    private static String textClaim(JsonNode claims, String name) {
        JsonNode claim = claims == null ? null : claims.get(name);
        return claim != null && claim.isTextual() && !claim.asText().isBlank() ? claim.asText() : null;
    }

    /**
     * The Signature parameters ({@code keyid}, {@code algorithm}, {@code headers},
     * {@code signature}, {@code version}) with lower-cased names.
     * Empty when the value is absent or not a Signature scheme.
     */
    public static Map<String, String> parameters(String authorizationHeader) {
        Map<String, String> params = new LinkedHashMap<>();
        if (!isSignatureScheme(authorizationHeader)) {
            return params;
        }
        Matcher m = PARAM.matcher(authorizationHeader.trim().substring("signature ".length()));
        while (m.find()) {
            params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return params;
    }

    /** True when the header uses the Signature scheme at all (even if malformed). */
    public static boolean isSignatureScheme(String authorizationHeader) {
        return authorizationHeader != null
                && authorizationHeader.trim().toLowerCase(Locale.ROOT).startsWith("signature ");
    }
}
