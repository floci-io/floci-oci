package io.floci.oci.services.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.*;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.queue.model.StoredQueue;
import io.floci.oci.services.queue.model.StoredQueue.StoredMessage;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class QueueService {

    private static final Logger LOG = Logger.getLogger(QueueService.class);

    static final String WR_SERVICE = "queue";
    private static final int DEFAULT_VISIBILITY_SECONDS = 30;
    private static final int DEFAULT_RETENTION_SECONDS = 86400;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** Long-poll cap so an emulator request can never hang a worker excessively. */
    private static final int MAX_LONG_POLL_SECONDS = 10;

    private final StorageBackend<String, StoredQueue> queues;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;

    @Inject
    public QueueService(StorageFactory storageFactory, EmulatorConfig config,
                        ServiceRegistry serviceRegistry, WorkRequestService workRequests) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.queues = storageFactory.create("queue", "queue-queues.json",
                new TypeReference<Map<String, StoredQueue>>() {});
    }

    QueueService(StorageBackend<String, StoredQueue> queues, EmulatorConfig config,
                 WorkRequestService workRequests) {
        this.queues = queues;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("queue")
                .enabled(config.services().queue().enabled())
                .storageKey("queue")
                .resourceClasses(QueueAdminController.class, QueueDataController.class)
                .build());
    }

    // ── Control plane (work-request based) ─────────────────────────────────────

    public record WorkRequestOutcome(String workRequestId, String queueId) {
    }

    public WorkRequestOutcome createQueue(String displayName, String compartmentId,
                                          Integer retentionInSeconds, Integer visibilityInSeconds,
                                          Integer timeoutInSeconds, Integer deadLetterQueueDeliveryCount,
                                          Integer channelConsumptionLimit,
                                          Map<String, String> freeformTags,
                                          Map<String, Map<String, Object>> definedTags) {
        if (displayName == null || displayName.isBlank()) {
            throw OciException.missingParameter("displayName is required");
        }
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        StoredQueue q = new StoredQueue();
        q.setId(Ocids.generate("queue", config.defaultRealm(), regionShort()));
        q.setCompartmentId(compartmentId);
        q.setDisplayName(displayName);
        String now = Instant.now().toString();
        q.setTimeCreated(now);
        q.setTimeUpdated(now);
        q.setLifecycleState("ACTIVE");
        q.setMessagesEndpoint(config.effectiveBaseUrl());
        q.setRetentionInSeconds(retentionInSeconds != null ? retentionInSeconds : DEFAULT_RETENTION_SECONDS);
        q.setVisibilityInSeconds(visibilityInSeconds != null ? visibilityInSeconds : DEFAULT_VISIBILITY_SECONDS);
        q.setTimeoutInSeconds(timeoutInSeconds != null ? timeoutInSeconds : DEFAULT_TIMEOUT_SECONDS);
        q.setDeadLetterQueueDeliveryCount(deadLetterQueueDeliveryCount != null ? deadLetterQueueDeliveryCount : 0);
        q.setChannelConsumptionLimit(channelConsumptionLimit != null ? channelConsumptionLimit : 100);
        q.setFreeformTags(freeformTags);
        q.setDefinedTags(definedTags);
        q.setEtag(Etags.newEtag());
        queues.put(q.getId(), q);
        LOG.infof("createQueue %s (%s)", displayName, q.getId());
        String wr = workRequests.succeeded(WR_SERVICE, "CREATE_QUEUE", compartmentId,
                List.of(WorkRequestService.resource("QUEUE", "CREATED", q.getId(),
                        "/20210201/queues/" + q.getId())));
        return new WorkRequestOutcome(wr, q.getId());
    }

    public StoredQueue getQueue(String queueId) {
        return queues.get(queueId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: queue " + queueId));
    }

    public List<StoredQueue> listQueues(String compartmentId, String displayName, String id) {
        return queues.scan(k -> true).stream()
                .filter(q -> compartmentId == null || compartmentId.equals(q.getCompartmentId()))
                .filter(q -> displayName == null || displayName.equals(q.getDisplayName()))
                .filter(q -> id == null || id.equals(q.getId()))
                .sorted(Comparator.comparing(StoredQueue::getTimeCreated))
                .toList();
    }

    public WorkRequestOutcome updateQueue(String queueId, String displayName,
                                          Integer visibilityInSeconds, Integer timeoutInSeconds,
                                          Integer deadLetterQueueDeliveryCount,
                                          Integer channelConsumptionLimit,
                                          Map<String, String> freeformTags,
                                          Map<String, Map<String, Object>> definedTags,
                                          String ifMatch) {
        StoredQueue q = getQueue(queueId);
        Etags.checkIfMatch(ifMatch, q.getEtag());
        if (displayName != null) {
            q.setDisplayName(displayName);
        }
        if (visibilityInSeconds != null) {
            q.setVisibilityInSeconds(visibilityInSeconds);
        }
        if (timeoutInSeconds != null) {
            q.setTimeoutInSeconds(timeoutInSeconds);
        }
        if (deadLetterQueueDeliveryCount != null) {
            q.setDeadLetterQueueDeliveryCount(deadLetterQueueDeliveryCount);
        }
        if (channelConsumptionLimit != null) {
            q.setChannelConsumptionLimit(channelConsumptionLimit);
        }
        if (freeformTags != null) {
            q.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            q.setDefinedTags(definedTags);
        }
        q.setTimeUpdated(Instant.now().toString());
        q.setEtag(Etags.newEtag());
        queues.put(queueId, q);
        String wr = workRequests.succeeded(WR_SERVICE, "UPDATE_QUEUE", q.getCompartmentId(),
                List.of(WorkRequestService.resource("QUEUE", "UPDATED", queueId,
                        "/20210201/queues/" + queueId)));
        return new WorkRequestOutcome(wr, queueId);
    }

    public WorkRequestOutcome deleteQueue(String queueId, String ifMatch) {
        StoredQueue q = getQueue(queueId);
        Etags.checkIfMatch(ifMatch, q.getEtag());
        queues.delete(queueId);
        String wr = workRequests.succeeded(WR_SERVICE, "DELETE_QUEUE", q.getCompartmentId(),
                List.of(WorkRequestService.resource("QUEUE", "DELETED", queueId,
                        "/20210201/queues/" + queueId)));
        return new WorkRequestOutcome(wr, queueId);
    }

    public WorkRequestOutcome changeCompartment(String queueId, String compartmentId, String ifMatch) {
        StoredQueue q = getQueue(queueId);
        Etags.checkIfMatch(ifMatch, q.getEtag());
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        q.setCompartmentId(compartmentId);
        q.setTimeUpdated(Instant.now().toString());
        q.setEtag(Etags.newEtag());
        queues.put(queueId, q);
        String wr = workRequests.succeeded(WR_SERVICE, "MOVE_QUEUE", compartmentId,
                List.of(WorkRequestService.resource("QUEUE", "UPDATED", queueId,
                        "/20210201/queues/" + queueId)));
        return new WorkRequestOutcome(wr, queueId);
    }

    public WorkRequestOutcome purgeQueue(String queueId, String purgeType, List<String> channelIds) {
        StoredQueue q = getQueue(queueId);
        String type = purgeType != null ? purgeType : "NORMAL";
        boolean purgeNormal = "NORMAL".equals(type) || "BOTH".equals(type);
        boolean purgeDlq = "DLQ".equals(type) || "BOTH".equals(type);
        if (purgeNormal) {
            if (channelIds == null || channelIds.isEmpty()) {
                q.getMessages().clear();
            } else {
                q.getMessages().removeIf(m -> channelIds.contains(m.getChannelId()));
            }
        }
        if (purgeDlq) {
            q.getDlqMessages().clear();
        }
        queues.put(queueId, q);
        String wr = workRequests.succeeded(WR_SERVICE, "PURGE_QUEUE", q.getCompartmentId(),
                List.of(WorkRequestService.resource("QUEUE", "UPDATED", queueId,
                        "/20210201/queues/" + queueId)));
        return new WorkRequestOutcome(wr, queueId);
    }

    // ── Data plane ─────────────────────────────────────────────────────────────

    public record PutResult(long id, String expireAfter) {
    }

    public List<PutResult> putMessages(String queueId, List<Map<String, Object>> entries) {
        StoredQueue q = getQueue(queueId);
        if (entries == null || entries.isEmpty()) {
            throw OciException.invalidParameter("messages must contain at least one entry");
        }
        List<PutResult> results = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, Object> entry : entries) {
            Object content = entry.get("content");
            if (!(content instanceof String s) || s.isEmpty()) {
                throw OciException.invalidParameter("content is required on every message");
            }
            StoredMessage m = new StoredMessage();
            m.setId(q.getNextMessageId());
            q.setNextMessageId(q.getNextMessageId() + 1);
            m.setContent(s);
            m.setCreatedAt(now.toString());
            m.setVisibleAfter(now.toString());
            m.setExpireAfter(now.plusSeconds(q.getRetentionInSeconds()).toString());
            m.setDeliveryCount(0);
            if (entry.get("metadata") instanceof Map<?, ?> metadata) {
                Object channelId = metadata.get("channelId");
                if (channelId instanceof String c && !c.isBlank()) {
                    m.setChannelId(c);
                }
                if (metadata.get("customProperties") instanceof Map<?, ?> props) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> typed = (Map<String, String>) props;
                    m.setCustomProperties(typed);
                }
            }
            q.getMessages().add(m);
            results.add(new PutResult(m.getId(), m.getExpireAfter()));
        }
        queues.put(queueId, q);
        return results;
    }

    /**
     * GetMessages with OCI semantics: {@code visibilityInSeconds = 0} is a peek (visibility
     * unchanged, deliveryCount still incremented); {@code timeoutInSeconds > 0} long-polls
     * until a message is available or the (capped) timeout elapses.
     */
    public List<StoredMessage> getMessages(String queueId, Integer visibilityInSeconds,
                                           Integer timeoutInSeconds, Integer limit,
                                           String channelFilter) {
        int effectiveTimeout = timeoutInSeconds != null ? timeoutInSeconds : 0;
        long deadline = System.currentTimeMillis()
                + Math.min(Math.max(effectiveTimeout, 0), MAX_LONG_POLL_SECONDS) * 1000L;
        while (true) {
            List<StoredMessage> delivered =
                    consumeAvailable(queueId, visibilityInSeconds, limit, channelFilter);
            if (!delivered.isEmpty() || System.currentTimeMillis() >= deadline) {
                return delivered;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }
    }

    private synchronized List<StoredMessage> consumeAvailable(String queueId, Integer visibilityInSeconds,
                                                              Integer limit, String channelFilter) {
        StoredQueue q = getQueue(queueId);
        expireAndDeadLetter(q);
        int max = limit != null && limit > 0 ? Math.min(limit, 32) : 1;
        int visibility = visibilityInSeconds != null ? visibilityInSeconds : q.getVisibilityInSeconds();
        Instant now = Instant.now();
        List<StoredMessage> delivered = new ArrayList<>();
        for (StoredMessage m : q.getMessages()) {
            if (delivered.size() >= max) {
                break;
            }
            if (Instant.parse(m.getVisibleAfter()).isAfter(now)) {
                continue;
            }
            if (channelFilter != null && !channelFilter.isBlank()
                    && !channelFilter.equals(m.getChannelId())) {
                continue;
            }
            m.setDeliveryCount(m.getDeliveryCount() + 1);
            m.setReceipt(UUID.randomUUID().toString().replace("-", ""));
            if (visibility > 0) {
                m.setVisibleAfter(now.plusSeconds(visibility).toString());
            }
            delivered.add(m);
        }
        if (!delivered.isEmpty()) {
            queues.put(queueId, q);
        }
        return delivered;
    }

    public void deleteMessage(String queueId, String receipt) {
        StoredQueue q = getQueue(queueId);
        boolean removed = q.getMessages().removeIf(m -> receipt.equals(m.getReceipt()));
        if (!removed) {
            throw OciException.notAuthorizedOrNotFound("No in-flight message for receipt: " + receipt);
        }
        queues.put(queueId, q);
    }

    public record BatchEntryError(Integer errorCode, String errorMessage) {
    }

    public List<BatchEntryError> deleteMessages(String queueId, List<String> receipts) {
        StoredQueue q = getQueue(queueId);
        List<BatchEntryError> results = new ArrayList<>();
        for (String receipt : receipts) {
            boolean removed = q.getMessages().removeIf(m -> receipt != null && receipt.equals(m.getReceipt()));
            results.add(removed ? new BatchEntryError(null, null)
                    : new BatchEntryError(404, "No in-flight message for receipt"));
        }
        queues.put(queueId, q);
        return results;
    }

    public StoredMessage updateMessage(String queueId, String receipt, int visibilityInSeconds) {
        StoredQueue q = getQueue(queueId);
        StoredMessage m = q.getMessages().stream()
                .filter(msg -> receipt.equals(msg.getReceipt()))
                .findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "No in-flight message for receipt: " + receipt));
        m.setVisibleAfter(Instant.now().plusSeconds(visibilityInSeconds).toString());
        queues.put(queueId, q);
        return m;
    }

    public record QueueStats(long visibleMessages, long inFlightMessages, long sizeInBytes,
                             long dlqVisible, long dlqInFlight, long dlqSizeInBytes) {
    }

    public QueueStats getStats(String queueId) {
        StoredQueue q = getQueue(queueId);
        expireAndDeadLetter(q);
        Instant now = Instant.now();
        long visible = q.getMessages().stream()
                .filter(m -> !Instant.parse(m.getVisibleAfter()).isAfter(now)).count();
        long inFlight = q.getMessages().size() - visible;
        long size = q.getMessages().stream().mapToLong(m -> m.getContent().length()).sum();
        long dlqSize = q.getDlqMessages().stream().mapToLong(m -> m.getContent().length()).sum();
        return new QueueStats(visible, inFlight, size, q.getDlqMessages().size(), 0, dlqSize);
    }

    public List<String> listChannels(String queueId) {
        StoredQueue q = getQueue(queueId);
        Set<String> channels = new LinkedHashSet<>();
        for (StoredMessage m : q.getMessages()) {
            if (m.getChannelId() != null) {
                channels.add(m.getChannelId());
            }
        }
        return List.copyOf(channels);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Drops expired messages; moves over-delivered messages to the DLQ when configured. */
    private void expireAndDeadLetter(StoredQueue q) {
        Instant now = Instant.now();
        q.getMessages().removeIf(m -> Instant.parse(m.getExpireAfter()).isBefore(now));
        int dlqThreshold = q.getDeadLetterQueueDeliveryCount() != null
                ? q.getDeadLetterQueueDeliveryCount() : 0;
        if (dlqThreshold > 0) {
            Iterator<StoredMessage> it = q.getMessages().iterator();
            while (it.hasNext()) {
                StoredMessage m = it.next();
                boolean visible = !Instant.parse(m.getVisibleAfter()).isAfter(now);
                if (visible && m.getDeliveryCount() >= dlqThreshold) {
                    it.remove();
                    q.getDlqMessages().add(m);
                }
            }
        }
    }

    String regionShort() {
        return switch (config.defaultRegion()) {
            case "us-ashburn-1" -> "iad";
            case "us-phoenix-1" -> "phx";
            case "eu-frankfurt-1" -> "fra";
            case "uk-london-1" -> "lhr";
            default -> config.defaultRegion().replaceAll("[^a-z]", "").substring(0, 3);
        };
    }
}
