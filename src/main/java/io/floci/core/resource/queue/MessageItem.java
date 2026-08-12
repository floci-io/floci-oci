package io.floci.core.resource.queue;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Universal message item representation across SQS (AWS), Service Bus (Azure), and Pub/Sub (GCP).
 */
public class MessageItem {
    private final String messageId;
    private final String receiptHandle;
    private final byte[] body;
    private final Map<String, String> attributes;
    private final Instant timestamp;

    public MessageItem(String messageId, String receiptHandle, byte[] body, Map<String, String> attributes) {
        this.messageId = messageId != null ? messageId : UUID.randomUUID().toString();
        this.receiptHandle = receiptHandle != null ? receiptHandle : UUID.randomUUID().toString();
        this.body = body != null ? body : new byte[0];
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        this.timestamp = Instant.now();
    }

    public static MessageItem of(String bodyText) {
        return new MessageItem(null, null, bodyText != null ? bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0], Map.of());
    }

    public String getMessageId() {
        return messageId;
    }

    public String getReceiptHandle() {
        return receiptHandle;
    }

    public byte[] getBody() {
        return body.clone();
    }

    public String getBodyAsString() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
