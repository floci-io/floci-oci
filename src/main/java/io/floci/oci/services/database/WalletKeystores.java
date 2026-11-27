package io.floci.oci.services.database;

import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.pkcs.PKCS12PfxPdu;
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBag;
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder;
import org.bouncycastle.pkcs.PKCS12SafeBagFactory;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfoBuilder;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves the keystores of an Autonomous Database Free wallet from the container's wallet password
 * to the password a GenerateAutonomousDatabaseWallet request names.
 *
 * <p>{@code ewallet.p12} is rebuilt bag by bag rather than through {@link KeyStore}: it carries an
 * Oracle secret bag (bag type {@code 0.22.72.134.247.13.1.10}, spike row 5) that a JDK keystore
 * drops. Key bags are decrypted and encrypted again; every other bag, the secret bag among them,
 * is copied as it was. The new encryption follows what the container writes: PBES2 with PBKDF2,
 * an HMAC-SHA256 PRF and AES-256-CBC, and a SHA-256 MAC.
 */
final class WalletKeystores {

    private static final int ITERATIONS = 10000;
    private static final int MAC_ITERATIONS = 2048;
    private static final byte[] JKS_MAGIC = {(byte) 0xFE, (byte) 0xED, (byte) 0xFE, (byte) 0xED};
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----\\r?\\n.*?-----END \\1-----\\r?\\n?", Pattern.DOTALL);

    private WalletKeystores() {
    }

    /**
     * A private instance rather than the registered "BC" provider: under Quarkus the registered one
     * can come from another class loader, and its ciphers then fail to recognise this class loader's
     * PBE keys ("Error finalising cipher").
     */
    private static final Provider PROVIDER = new BouncyCastleProvider();

    private static Provider provider() {
        return PROVIDER;
    }

    private static InputDecryptorProvider decryptor(char[] password) throws GeneralSecurityException {
        try {
            return new JcePKCSPBEInputDecryptorProviderBuilder().setProvider(provider()).build(password);
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("Could not set up the wallet decryption: " + e.getMessage(), e);
        }
    }

    private static OutputEncryptor encryptor(char[] password) throws GeneralSecurityException {
        try {
            return new JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC)
                    .setProvider(provider())
                    .setPRF(new AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE))
                    .setIterationCount(ITERATIONS)
                    .build(password);
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException("Could not set up the wallet encryption: " + e.getMessage(), e);
        }
    }

    /** A PKCS12 file re-encrypted to {@code newPassword}, with every bag and bag attribute kept. */
    static byte[] reencryptPkcs12(byte[] pkcs12, char[] oldPassword, char[] newPassword)
            throws GeneralSecurityException {
        try {
            PKCS12PfxPdu pfx = new PKCS12PfxPdu(pkcs12);
            InputDecryptorProvider decryptor = decryptor(oldPassword);
            OutputEncryptor encryptor = encryptor(newPassword);
            PKCS12PfxPduBuilder builder = new PKCS12PfxPduBuilder();
            for (ContentInfo content : pfx.getContentInfos()) {
                boolean encrypted = PKCSObjectIdentifiers.encryptedData.equals(content.getContentType());
                PKCS12SafeBagFactory factory = encrypted
                        ? new PKCS12SafeBagFactory(content, decryptor) : new PKCS12SafeBagFactory(content);
                List<PKCS12SafeBag> bags = new ArrayList<>();
                for (PKCS12SafeBag bag : factory.getSafeBags()) {
                    bags.add(reencrypt(bag, decryptor, encryptor));
                }
                if (encrypted) {
                    builder.addEncryptedData(encryptor, bags.toArray(PKCS12SafeBag[]::new));
                } else {
                    // BouncyCastle puts each unencrypted bag in a content of its own; readers
                    // treat that the same as one content holding them all.
                    for (PKCS12SafeBag bag : bags) {
                        builder.addData(bag);
                    }
                }
            }
            BcPKCS12MacCalculatorBuilder mac = new BcPKCS12MacCalculatorBuilder(new SHA256Digest(),
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE));
            mac.setIterationCount(MAC_ITERATIONS);
            return builder.build(mac, newPassword).getEncoded();
        } catch (IOException | PKCSException | RuntimeException e) {
            throw new GeneralSecurityException("Could not re-encrypt the PKCS12 wallet: " + e.getMessage(), e);
        }
    }

    /** A shrouded key bag decrypted and encrypted again; any other bag as it was. */
    private static PKCS12SafeBag reencrypt(PKCS12SafeBag bag, InputDecryptorProvider decryptor,
                                           OutputEncryptor encryptor) throws PKCSException {
        if (!PKCSObjectIdentifiers.pkcs8ShroudedKeyBag.equals(bag.getType())) {
            return bag;
        }
        PrivateKeyInfo key = ((PKCS8EncryptedPrivateKeyInfo) bag.getBagValue()).decryptPrivateKeyInfo(decryptor);
        PKCS12SafeBagBuilder builder = new PKCS12SafeBagBuilder(key, encryptor);
        Attribute[] attributes = bag.getAttributes();
        if (attributes != null) {
            for (Attribute attribute : attributes) {
                for (int i = 0; i < attribute.getAttrValues().size(); i++) {
                    builder.addBagAttribute(attribute.getAttrType(), attribute.getAttrValues().getObjectAt(i));
                }
            }
        }
        return builder.build();
    }

    /**
     * A PEM file with each encrypted private key re-encrypted to {@code newPassword}: a PKCS8
     * {@code ENCRYPTED PRIVATE KEY} stays PKCS8, a legacy OpenSSL-encrypted key stays legacy.
     * Certificates, unencrypted blocks and any text between blocks are kept as they are.
     */
    static byte[] reencryptPem(byte[] pem, char[] oldPassword, char[] newPassword) throws GeneralSecurityException {
        String text = new String(pem, StandardCharsets.US_ASCII);
        Matcher block = PEM_BLOCK.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        try {
            while (block.find()) {
                out.append(text, last, block.start());
                out.append(reencryptPemBlock(block.group(1), block.group(), oldPassword, newPassword));
                last = block.end();
            }
        } catch (IOException | PKCSException | RuntimeException e) {
            throw new GeneralSecurityException("Could not re-encrypt the PEM wallet: " + e.getMessage(), e);
        }
        out.append(text, last, text.length());
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String reencryptPemBlock(String type, String block, char[] oldPassword, char[] newPassword)
            throws IOException, PKCSException, GeneralSecurityException {
        if ("ENCRYPTED PRIVATE KEY".equals(type)) {
            PKCS8EncryptedPrivateKeyInfo encrypted = new PKCS8EncryptedPrivateKeyInfo(pemBody(block));
            PrivateKeyInfo key = encrypted.decryptPrivateKeyInfo(decryptor(oldPassword));
            byte[] reencrypted = new PKCS8EncryptedPrivateKeyInfoBuilder(key).build(encryptor(newPassword))
                    .getEncoded();
            return pem(new PemObject(type, reencrypted));
        }
        if (block.contains("Proc-Type: 4,ENCRYPTED")) {
            try (PEMParser parser = new PEMParser(new StringReader(block))) {
                Object parsed = parser.readObject();
                if (parsed instanceof PEMEncryptedKeyPair encryptedPair) {
                    PEMKeyPair pair = encryptedPair.decryptKeyPair(
                            new JcePEMDecryptorProviderBuilder().setProvider(provider()).build(oldPassword));
                    JcaMiscPEMGenerator generator = new JcaMiscPEMGenerator(
                            new JcaPEMKeyConverter().setProvider(provider()).getPrivateKey(pair.getPrivateKeyInfo()),
                            new JcePEMEncryptorBuilder("AES-256-CBC").setProvider(provider()).build(newPassword));
                    return pem(generator.generate());
                }
            }
        }
        return block;
    }

    private static byte[] pemBody(String block) {
        String body = block.replaceAll("-----(BEGIN|END) [A-Z0-9 ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String pem(PemObject object) throws IOException {
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }

    /**
     * A Java keystore re-encrypted to {@code newPassword}. A file that is PKCS12 inside goes
     * through {@link #reencryptPkcs12}. A JKS store whose integrity the old password does not
     * check, and that holds only trusted certificates, is opened without a password: certificates
     * are not encrypted in a JKS store.
     */
    static byte[] reencryptJks(byte[] store, char[] oldPassword, char[] newPassword) throws GeneralSecurityException {
        if (store.length < JKS_MAGIC.length || !startsWith(store, JKS_MAGIC)) {
            return reencryptPkcs12(store, oldPassword, newPassword);
        }
        try {
            KeyStore in = KeyStore.getInstance("JKS");
            try {
                in.load(new ByteArrayInputStream(store), oldPassword);
            } catch (IOException e) {
                in = KeyStore.getInstance("JKS");
                in.load(new ByteArrayInputStream(store), null);
            }
            KeyStore out = KeyStore.getInstance("JKS");
            out.load(null, null);
            for (String alias : Collections.list(in.aliases())) {
                if (in.isKeyEntry(alias)) {
                    Key key = in.getKey(alias, oldPassword);
                    Certificate[] chain = in.getCertificateChain(alias);
                    out.setKeyEntry(alias, key, newPassword, chain);
                } else {
                    out.setCertificateEntry(alias, in.getCertificate(alias));
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.store(bytes, newPassword);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new GeneralSecurityException("Could not re-encrypt the Java keystore: " + e.getMessage(), e);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
