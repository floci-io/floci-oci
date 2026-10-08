package io.floci.oci.services.oke;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Mints bearer tokens the way {@code oci ce cluster generate-token} does
 * (oci-cli {@code containerengine_cli_extended.py:38-90}): RSA-sign a GET of
 * {@code https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>} over
 * {@code date (request-target) host} (oci-python-sdk {@code signer.py:257}), put the
 * {@code authorization} and {@code date} on the URL form-encoded like Python's {@code urlencode},
 * then base64url-encode the URL with padding.
 */
final class ClusterTokenMinter {

    /** Python's {@code email.utils.formatdate(usegmt=True)}: always two-digit day, literal GMT. */
    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final KeyPair KEY_PAIR = generateKeyPair();

    private ClusterTokenMinter() {
    }

    static String mint(String region, String clusterId, String tenancyId, String userId, Instant signedAt) {
        String host = "containerengine." + region + ".oraclecloud.com";
        String path = "/cluster_request/" + clusterId;
        String date = RFC_1123.format(signedAt);
        String authorization = authorization(tenancyId + "/" + userId + "/aa:bb:cc", signature(date, path, host));
        return encode("https://" + host + path
                + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8));
    }

    static String authorization(String keyId, String signature) {
        return "Signature algorithm=\"rsa-sha256\",headers=\"date (request-target) host\",keyId=\""
                + keyId + "\",signature=\"" + signature + "\",version=\"1\"";
    }

    static String encode(String url) {
        return Base64.getUrlEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private static String signature(String date, String path, String host) {
        String signingString = "date: " + date + "\n(request-target): get " + path + "\nhost: " + host;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(KEY_PAIR.getPrivate());
            signer.update(signingString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate the test signing key", e);
        }
    }
}
