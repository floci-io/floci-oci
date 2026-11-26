package io.floci.oci.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Verifies an OCI request signature (draft-cavage HTTP Signatures as OCI profiles them) against a
 * public key. Pure: the caller supplies the method, the raw request target and a header lookup.
 *
 * <p>The signing string is rebuilt from the Signature's {@code headers} parameter, in the order given,
 * as {@code name: value} lines joined by {@code \n} (oci-go-sdk {@code common/http_signer.go},
 * {@code getSigningString}). {@code (request-target)} is the lowercase method, a space and the raw path
 * with its raw query ({@code getRequestTarget}: {@code URL.RequestURI()}; oci-java-sdk
 * {@code RequestSignerImpl.extractPath}). The list is never inferred from the method: oci-go-sdk's
 * {@code PutObject} and oci-python-sdk's {@code enforce_content_headers=False} sign a POST or PUT with the
 * generic headers only.
 *
 * <p>Only {@code rsa-sha256} is accepted: every SDK writes that name, and oci-go-sdk writes it for its
 * PSS scheme too ({@code Sign} formats {@code algorithm="rsa-sha256"} whatever the scheme), so a
 * signature is checked as PKCS#1 v1.5 first and then as RSASSA-PSS with SHA-256.
 */
public final class SignatureVerifier {

    /** How far the signed date may be from the server clock, either way. */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    public static final String ALGORITHM = "rsa-sha256";
    public static final String REQUEST_TARGET = "(request-target)";
    public static final String BODY_HASH_HEADER = "x-content-sha256";
    /** draft-cavage: a Signature without {@code headers} covers the {@code Date} header alone. */
    private static final String DEFAULT_HEADERS = "date";
    private static final int SHA256_LENGTH = 32;

    private final Clock clock;

    public SignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    /** The outcome of a verification; {@code failure} names the reason, never the signature. */
    public record Result(boolean verified, String failure) {

        static Result ok() {
            return new Result(true, null);
        }

        static Result failed(String failure) {
            return new Result(false, failure);
        }
    }

    /**
     * Checks {@code params} (the parsed Signature parameters) over a request.
     *
     * @param method        the HTTP method, any case
     * @param requestTarget the raw path and, when there is one, {@code ?} and the raw query
     * @param headers       a header's first value by lowercase name, or null when it is absent
     */
    public Result verify(String method, String requestTarget, Function<String, String> headers,
                         Map<String, String> params, PublicKey key) {
        if (!ALGORITHM.equalsIgnoreCase(params.get("algorithm"))) {
            return Result.failed("the signature algorithm must be " + ALGORITHM);
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(params.getOrDefault("signature", ""));
        } catch (IllegalArgumentException e) {
            return Result.failed("the signature is not base64");
        }
        if (signature.length == 0) {
            return Result.failed("the signature is empty");
        }
        if (!(key instanceof RSAPublicKey rsa)) {
            return Result.failed("the signing key is not an RSA key");
        }
        List<String> signed = signedHeaders(params);
        List<String> lines = new ArrayList<>(signed.size());
        for (String name : signed) {
            String value = REQUEST_TARGET.equals(name)
                    ? method.toLowerCase(Locale.ROOT) + " " + requestTarget
                    : headers.apply(name);
            if (value == null) {
                return Result.failed("the signed header " + name + " is missing from the request");
            }
            lines.add(name + ": " + value);
        }
        byte[] signingString = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        if (!matches(signingString, signature, rsa)) {
            return Result.failed("the signature does not match the signing key");
        }
        String dateHeader = signed.contains("x-date") ? "x-date" : signed.contains("date") ? "date" : null;
        if (dateHeader == null) {
            return Result.failed("the signature does not cover the date header");
        }
        if (!isCurrent(headers.apply(dateHeader))) {
            return Result.failed("the signed date is more than " + MAX_CLOCK_SKEW.toMinutes()
                    + " minutes from the server time");
        }
        return Result.ok();
    }

    /** The lowercase names the Signature's {@code headers} parameter lists, in order. */
    public static List<String> signedHeaders(Map<String, String> params) {
        String listed = params.get("headers");
        if (listed == null || listed.isBlank()) {
            listed = DEFAULT_HEADERS;
        }
        return Arrays.stream(listed.trim().split("\\s+"))
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Whether the signature covers the body, through {@code x-content-sha256}. */
    public static boolean coversBody(Map<String, String> params) {
        return signedHeaders(params).contains(BODY_HASH_HEADER);
    }

    /** The base64 SHA-256 of {@code body}, as {@code x-content-sha256} carries it (http_signer.go {@code hashAndEncode}). */
    public static String bodyHash(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Whether an RFC 1123 date is within {@link #MAX_CLOCK_SKEW} of the clock. */
    public boolean isCurrent(String date) {
        if (date == null) {
            return false;
        }
        Instant signedAt;
        try {
            signedAt = ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return false;
        }
        return Duration.between(signedAt, clock.instant()).abs().compareTo(MAX_CLOCK_SKEW) <= 0;
    }

    /**
     * PKCS#1 v1.5, then PSS. oci-go-sdk signs PSS with the caller's {@code rsa.PSSOptions}, whose zero
     * salt length means {@code PSSSaltLengthAuto} (as long as the key allows); a salt of the hash length
     * is the other common choice, so both are tried.
     */
    private static boolean matches(byte[] signingString, byte[] signature, RSAPublicKey key) {
        if (verifyWith("SHA256withRSA", null, signingString, signature, key)) {
            return true;
        }
        int emLength = (key.getModulus().bitLength() - 1 + 7) / 8;
        int maxSalt = emLength - SHA256_LENGTH - 2;
        for (int salt : new int[] {SHA256_LENGTH, maxSalt}) {
            if (salt >= 0 && verifyWith("RSASSA-PSS",
                    new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, salt, 1),
                    signingString, signature, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean verifyWith(String algorithm, PSSParameterSpec pss, byte[] signingString,
                                      byte[] signature, RSAPublicKey key) {
        try {
            Signature verifier = Signature.getInstance(algorithm);
            if (pss != null) {
                verifier.setParameter(pss);
            }
            verifier.initVerify(key);
            verifier.update(signingString);
            return verifier.verify(signature);
        } catch (GeneralSecurityException expected) {
            // A malformed signature (the wrong length for the key) is simply not a match.
            return false;
        }
    }
}
