package io.floci.oci.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

/** RSA public keys in the forms OCI request signing uses: PEM, JWK and the API key fingerprint. */
public final class PublicKeys {

    private PublicKeys() {
    }

    /**
     * The RSA public key in {@code pem}: a public key ({@code PUBLIC KEY} or {@code RSA PUBLIC KEY}), or an
     * unencrypted private key ({@code PRIVATE KEY} or {@code RSA PRIVATE KEY}), whose public half is used.
     */
    public static PublicKey fromPem(String pem) {
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        } catch (IOException e) {
            throw new IllegalArgumentException("not a PEM key: " + e.getMessage(), e);
        }
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        try {
            PublicKey key = switch (parsed) {
                case SubjectPublicKeyInfo info -> converter.getPublicKey(info);
                case PEMKeyPair pair -> converter.getPublicKey(pair.getPublicKeyInfo());
                case PrivateKeyInfo info -> publicHalf(converter.getPrivateKey(info));
                case null -> throw new IllegalArgumentException("no PEM key found");
                default -> throw new IllegalArgumentException(
                        "unsupported PEM object " + parsed.getClass().getSimpleName() + " (an encrypted key?)");
            };
            if (!"RSA".equals(key.getAlgorithm())) {
                throw new IllegalArgumentException("not an RSA key: " + key.getAlgorithm());
            }
            return key;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("not an RSA key: " + e.getMessage(), e);
        }
    }

    private static PublicKey publicHalf(PrivateKey key) throws GeneralSecurityException {
        if (key instanceof RSAPrivateCrtKey rsa) {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        }
        throw new IllegalArgumentException("not an RSA private key: " + key.getAlgorithm());
    }

    /**
     * The RSA key of a JWK ({@code kty}, {@code n}, {@code e}), as oci-java-sdk reads a security token's
     * {@code jwk} claim ({@code AuthUtils.toPublicKeyFromJwk}): {@code n} and {@code e} are unsigned and
     * base64url, standard base64 also accepted. Empty when it is not an RSA JWK.
     */
    public static Optional<PublicKey> fromJwk(JsonNode jwk) {
        if (jwk == null || !jwk.isObject() || !"RSA".equals(jwk.path("kty").asText())
                || !jwk.path("n").isTextual() || !jwk.path("e").isTextual()) {
            return Optional.empty();
        }
        try {
            BigInteger modulus = new BigInteger(1, base64(jwk.get("n").asText()));
            BigInteger exponent = new BigInteger(1, base64(jwk.get("e").asText()));
            if (modulus.signum() == 0 || exponent.signum() == 0) {
                return Optional.empty();
            }
            return Optional.of(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    private static byte[] base64(String value) {
        return Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    /**
     * The API key fingerprint: the MD5 of the DER-encoded SubjectPublicKeyInfo, as colon-separated
     * lowercase hex pairs, what {@code oci setup} computes (oci-cli {@code cli_setup.py},
     * {@code public_key_to_fingerprint}).
     */
    public static String fingerprint(byte[] subjectPublicKeyInfoDer) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("MD5").digest(subjectPublicKeyInfoDer);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
        String hex = HexFormat.of().formatHex(digest);
        StringJoiner joined = new StringJoiner(":");
        for (int i = 0; i < hex.length(); i += 2) {
            joined.add(hex.substring(i, i + 2));
        }
        return joined.toString();
    }

    public static String fingerprint(PublicKey key) {
        return fingerprint(key.getEncoded());
    }

    /** {@code fingerprint} as {@link #fingerprint(byte[])} formats it, for comparing a keyId's. */
    public static String normalizeFingerprint(String fingerprint) {
        return fingerprint == null ? null : fingerprint.trim().toLowerCase(Locale.ROOT);
    }
}
