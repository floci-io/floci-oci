package io.floci.core.resource.queue;

import java.util.List;

/**
 * Universal Queue contract across SQS, Service Bus, and Pub/Sub queues.
 */
public interface QueueStore {
    MessageItem sendMessage(String queueOrTopicName, byte[] body, java.util.Map<String, String> attributes);

    List<MessageItem> receiveMessages(String queueOrTopicName, int maxMessages, int visibilityTimeoutSeconds);

    boolean deleteMessage(String queueOrTopicName, String receiptHandle);

    void purgeQueue(String queueOrTopicName);
}
