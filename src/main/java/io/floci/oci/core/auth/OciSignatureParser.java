package io.floci.oci.core.auth;

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
 * Only the structure is validated; the signature value is never verified.
 */
public final class OciSignatureParser {

    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private OciSignatureParser() {
    }

    /**
     * Extracts the caller identity from an Authorization header.
     * Returns empty when the header is absent, not a Signature scheme,
     * or its keyId is not {@code tenancy/user/fingerprint}.
     */
    public static Optional<AuthContext> parse(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String header = authorizationHeader.trim();
        if (!header.toLowerCase(Locale.ROOT).startsWith("signature ")) {
            return Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(header.substring("signature ".length()));
        while (m.find()) {
            params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        String keyId = params.get("keyid");
        if (keyId == null) {
            return Optional.empty();
        }
        // Instance/resource principals use "ST$<token>" keyIds — no tenancy triple to extract.
        String[] parts = keyId.split("/");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AuthContext(parts[0], parts[1], parts[2]));
    }

    /** True when the header uses the Signature scheme at all (even if malformed). */
    public static boolean isSignatureScheme(String authorizationHeader) {
        return authorizationHeader != null
                && authorizationHeader.trim().toLowerCase(Locale.ROOT).startsWith("signature ");
    }
}
