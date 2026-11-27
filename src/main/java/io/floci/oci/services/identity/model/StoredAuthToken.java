package io.floci.oci.services.identity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An auth token of a user (oci-go-sdk/identity/auth_token.go): the password of OCIR {@code docker login}
 * and of the Streaming Kafka SASL/PLAIN endpoint. The token itself is returned once, by the create call;
 * only its SHA-256 is kept, which is enough to check a password against it.
 */
@RegisterForReflection
public class StoredAuthToken {

    private String id;
    private String userId;
    private String userName;
    private String description;
    private String timeCreated;
    private String lifecycleState;
    private String tokenSha256;
    private String etag;

    public StoredAuthToken() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** The owning user's name, which OCIR and Kafka usernames carry instead of its OCID. */
    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    /** Lowercase hex SHA-256 of the token's UTF-8 bytes. */
    public String getTokenSha256() {
        return tokenSha256;
    }

    public void setTokenSha256(String tokenSha256) {
        this.tokenSha256 = tokenSha256;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
