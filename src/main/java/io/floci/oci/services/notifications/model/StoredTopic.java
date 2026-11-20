package io.floci.oci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Notifications topic (oci-go-sdk ons/notification_topic.go). Topics are created ACTIVE and
 * removed on delete, so a deleted topic answers 404.
 */
@RegisterForReflection
public class StoredTopic {

    private String topicId;
    private String name;
    private String compartmentId;
    private String description;
    private String lifecycleState;
    private String timeCreated;
    private String apiEndpoint;
    private String shortTopicId;
    private String etag;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();

    public StoredTopic() {
    }

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getShortTopicId() {
        return shortTopicId;
    }

    public void setShortTopicId(String shortTopicId) {
        this.shortTopicId = shortTopicId;
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
