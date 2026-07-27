package io.floci.oci.services.queue.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A queue plus its message state. Never serialized to clients directly — the wire
 * shapes ({@code Queue}, {@code QueueSummary}, {@code GetMessage}) are projected in
 * the service/controller layer.
 */
@RegisterForReflection
public class StoredQueue {

    private String id;
    private String compartmentId;
    private String displayName;
    private String timeCreated;
    private String timeUpdated;
    private String lifecycleState;
    private String messagesEndpoint;
    private Integer retentionInSeconds;
    private Integer visibilityInSeconds;
    private Integer timeoutInSeconds;
    private Integer deadLetterQueueDeliveryCount;
    private Integer channelConsumptionLimit;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    private long nextMessageId = 1;
    private List<StoredMessage> messages = new ArrayList<>();
    private List<StoredMessage> dlqMessages = new ArrayList<>();

    public StoredQueue() {
    }

    @RegisterForReflection
    public static class StoredMessage {

        private long id;
        private String content;
        private String receipt;
        private int deliveryCount;
        private String visibleAfter;
        private String expireAfter;
        private String createdAt;
        private String channelId;
        private Map<String, String> customProperties;

        public StoredMessage() {
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getReceipt() {
            return receipt;
        }

        public void setReceipt(String receipt) {
            this.receipt = receipt;
        }

        public int getDeliveryCount() {
            return deliveryCount;
        }

        public void setDeliveryCount(int deliveryCount) {
            this.deliveryCount = deliveryCount;
        }

        public String getVisibleAfter() {
            return visibleAfter;
        }

        public void setVisibleAfter(String visibleAfter) {
            this.visibleAfter = visibleAfter;
        }

        public String getExpireAfter() {
            return expireAfter;
        }

        public void setExpireAfter(String expireAfter) {
            this.expireAfter = expireAfter;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public Map<String, String> getCustomProperties() {
            return customProperties;
        }

        public void setCustomProperties(Map<String, String> customProperties) {
            this.customProperties = customProperties;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getMessagesEndpoint() {
        return messagesEndpoint;
    }

    public void setMessagesEndpoint(String messagesEndpoint) {
        this.messagesEndpoint = messagesEndpoint;
    }

    public Integer getRetentionInSeconds() {
        return retentionInSeconds;
    }

    public void setRetentionInSeconds(Integer retentionInSeconds) {
        this.retentionInSeconds = retentionInSeconds;
    }

    public Integer getVisibilityInSeconds() {
        return visibilityInSeconds;
    }

    public void setVisibilityInSeconds(Integer visibilityInSeconds) {
        this.visibilityInSeconds = visibilityInSeconds;
    }

    public Integer getTimeoutInSeconds() {
        return timeoutInSeconds;
    }

    public void setTimeoutInSeconds(Integer timeoutInSeconds) {
        this.timeoutInSeconds = timeoutInSeconds;
    }

    public Integer getDeadLetterQueueDeliveryCount() {
        return deadLetterQueueDeliveryCount;
    }

    public void setDeadLetterQueueDeliveryCount(Integer deadLetterQueueDeliveryCount) {
        this.deadLetterQueueDeliveryCount = deadLetterQueueDeliveryCount;
    }

    public Integer getChannelConsumptionLimit() {
        return channelConsumptionLimit;
    }

    public void setChannelConsumptionLimit(Integer channelConsumptionLimit) {
        this.channelConsumptionLimit = channelConsumptionLimit;
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public long getNextMessageId() {
        return nextMessageId;
    }

    public void setNextMessageId(long nextMessageId) {
        this.nextMessageId = nextMessageId;
    }

    public List<StoredMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<StoredMessage> messages) {
        this.messages = messages;
    }

    public List<StoredMessage> getDlqMessages() {
        return dlqMessages;
    }

    public void setDlqMessages(List<StoredMessage> dlqMessages) {
        this.dlqMessages = dlqMessages;
    }
}
