package io.floci.oci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Notifications subscription (oci-go-sdk ons/subscription.go). {@code confirmationToken} is the
 * token carried by the confirmation and unsubscribe URLs; it is never returned by the API.
 */
@RegisterForReflection
public class StoredSubscription {

    private String id;
    private String topicId;
    private String compartmentId;
    private String protocol;
    private String endpoint;
    private String metadata;
    private String lifecycleState;
    private long createdTime;
    private Map<String, Object> deliveryPolicy = new LinkedHashMap<>();
    private String confirmationToken;
    private String etag;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();

    public StoredSubscription() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public Map<String, Object> getDeliveryPolicy() {
        return deliveryPolicy;
    }

    public void setDeliveryPolicy(Map<String, Object> deliveryPolicy) {
        this.deliveryPolicy = deliveryPolicy;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }
}
