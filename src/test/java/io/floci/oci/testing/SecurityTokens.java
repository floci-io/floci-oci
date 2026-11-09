package io.floci.oci.testing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Security-token JWTs shaped like the ones {@code oci session authenticate} stores, for
 * {@code keyId="ST$<jwt>"} signatures. floci-oci never verifies a JWT, so the signature segment is
 * filler of a 2048-bit RSA signature's length, and the {@code jwk} claim carries a modulus of the
 * same size so the token is as long as a real one.
 */
public final class SecurityTokens {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private SecurityTokens() {
    }

    /** A session token whose {@code tenant} and {@code sub} claims name {@code tenancyId} and {@code userId}. */
    public static String session(String tenancyId, String userId) {
        return jwt("{\"sub\":\"" + userId + "\",\"tenant\":\"" + tenancyId + "\",\"ttype\":\"login\","
                + "\"iss\":\"authService.oracle.com\",\"aud\":\"oci\",\"iat\":1793000000,\"exp\":1793003600,"
                + "\"jwk\":{\"kid\":\"" + userId + "\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\","
                + "\"e\":\"AQAB\",\"n\":\"" + filler(342) + "\"}}");
    }

    /** A token with exactly the given claims, to exercise missing or malformed ones. */
    public static String jwt(String claimsJson) {
        String header = URL.encodeToString("{\"alg\":\"RS256\",\"kid\":\"asw\",\"typ\":\"JWT\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = URL.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + filler(342);
    }

    private static String filler(int length) {
        return "A".repeat(length);
    }
}
