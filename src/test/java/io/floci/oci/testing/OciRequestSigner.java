package io.floci.oci.testing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Signs requests the way oci-go-sdk's {@code ociRequestSigner} does (common/http_signer.go): generic
 * headers {@code date (request-target) host}, plus {@code content-length content-type x-content-sha256}
 * when the body is signed; {@code name: value} lines joined by {@code \n}; RSA PKCS#1 v1.5 SHA-256 by
 * default, PSS when asked; the Authorization header in Go's parameter order.
 */
public final class OciRequestSigner {

    /** Go's {@code http.TimeFormat}, which Python's {@code formatdate(usegmt=True)} also produces. */
    public static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    public static final List<String> GENERIC_HEADERS = List.of("date", "(request-target)", "host");
    public static final List<String> BODY_HEADERS = List.of("content-length", "content-type", "x-content-sha256");

    private final String keyId;
    private final PrivateKey key;
    private final PSSParameterSpec pss;

    private OciRequestSigner(String keyId, PrivateKey key, PSSParameterSpec pss) {
        this.keyId = keyId;
        this.key = key;
        this.pss = pss;
    }

    public static OciRequestSigner of(String keyId, PrivateKey key) {
        return new OciRequestSigner(keyId, key, null);
    }

    /** The same signer with RSASSA-PSS over SHA-256 and the given salt length. */
    public OciRequestSigner pss(int saltLength) {
        return new OciRequestSigner(keyId, key,
                new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, saltLength, 1));
    }

    /**
     * The headers to send: {@code date}, {@code host}, the body headers when {@code body} is not null,
     * and {@code authorization}.
     *
     * @param body null to sign the generic headers only, as Go's PutObject and Python's
     *             {@code enforce_content_headers=False} do
     */
    public Map<String, String> sign(String method, String requestTarget, String host, Instant date,
                                    byte[] body, String contentType) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("date", HTTP_DATE.format(date));
        headers.put("host", host);
        List<String> signed = new ArrayList<>(GENERIC_HEADERS);
        if (body != null) {
            headers.put("content-length", String.valueOf(body.length));
            headers.put("content-type", contentType);
            headers.put("x-content-sha256", sha256(body));
            signed.addAll(BODY_HEADERS);
        }
        headers.put("authorization", authorization(method, requestTarget, headers, signed));
        return headers;
    }

    /** The Authorization header over {@code signed}, reading values from {@code headers}. */
    public String authorization(String method, String requestTarget, Map<String, String> headers, List<String> signed) {
        List<String> lines = new ArrayList<>();
        for (String name : signed) {
            String value = "(request-target)".equals(name)
                    ? method.toLowerCase(Locale.ROOT) + " " + requestTarget
                    : headers.get(name);
            lines.add(name + ": " + value);
        }
        String signature = signature(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return "Signature version=\"1\",headers=\"" + String.join(" ", signed) + "\",keyId=\"" + keyId
                + "\",algorithm=\"rsa-sha256\",signature=\"" + signature + "\"";
    }

    private String signature(byte[] signingString) {
        try {
            Signature signer = Signature.getInstance(pss == null ? "SHA256withRSA" : "RSASSA-PSS");
            if (pss != null) {
                signer.setParameter(pss);
            }
            signer.initSign(key);
            signer.update(signingString);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the test request", e);
        }
    }

    public static String sha256(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate a test signing key", e);
        }
    }

    /** {@code key} as a {@code PUBLIC KEY} PEM, the form UploadApiKey takes. */
    public static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** {@code key} as a PKCS#8 {@code PRIVATE KEY} PEM, the form {@code oci setup keys} writes. */
    public static String pem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
