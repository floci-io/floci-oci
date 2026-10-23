package io.floci.oci.services.kms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The RSA key pair that callers wrap key material with before ImportKey. */
@RegisterForReflection
public class StoredWrappingKey {

    private String id;
    private String timeCreated;
    /** PEM-encoded RSA public key, as GetWrappingKey returns it. */
    private String publicKeyPem;
    /** Base64 PKCS8 RSA private key, used to unwrap imported material. */
    private String privateKey;
    private String etag;

    public StoredWrappingKey() {
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }
}
