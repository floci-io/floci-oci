package io.floci.oci.services.notifications;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.notifications.model.DeliveryRecord;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers Notifications messages to subscription endpoints, off the request thread.
 *
 * <p>CUSTOM_HTTPS and HTTPS subscriptions get a real POST with the standard {@code X-OCI-NS-*}
 * header metadata (Notification/Concepts/notificationoverview.htm, "Standard header metadata"),
 * with the fixed 5 second connect and read timeouts of create-subscription-https.htm.
 * ORACLE_FUNCTIONS subscriptions invoke the function in-process. EMAIL, SLACK, PAGERDUTY and SMS
 * are never sent anywhere: the message is only recorded. Every delivery is recorded, so
 * {@code GET /_floci-oci/notifications/messages} shows what each endpoint received.
 *
 * <p>A failed delivery follows the documented retry schedule: one immediate retry, then
 * exponential backoff of 1, 2, 4, 8, 16 and 32 minutes, until the subscription's
 * {@code maxRetryDuration} is spent, after which the message is discarded.
 */
@ApplicationScoped
public class NotificationDispatcher implements Resettable {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class);

    static final int MAX_RECORDS = 1000;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(4), Duration.ofMinutes(8), Duration.ofMinutes(16), Duration.ofMinutes(32));

    public static final String NOTIFICATION = "Notification";
    public static final String SUBSCRIPTION_CONFIRMATION = "SubscriptionConfirmation";

    /** What is being delivered. {@code messageType} is the {@code X-OCI-NS-MessageType} header value. */
    public record Message(String messageId, String messageType, String topicId, String topicName,
                          String title, String body, String contentType) {
    }

    /** Where it goes, and for how long a failed delivery is retried. */
    public record Target(String subscriptionId, String protocol, String endpoint, long maxRetryMillis,
                         String confirmationUrl, String unsubscribeUrl) {
    }

    private final EmulatorConfig config;
    private final FunctionsService functions;
    private final RequestContextPropagation contextPropagation;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Deque<DeliveryRecord> records = new ConcurrentLinkedDeque<>();
    private final Set<ScheduledFuture<?>> pendingRetries = ConcurrentHashMap.newKeySet();
    /** Bumped by a state reset; an attempt started before it neither sends nor retries again. */
    private final AtomicLong generation = new AtomicLong();
    /** Deliveries still sending or waiting to retry; each leaves the set once it is done. */
    private final Set<Attempt> live = ConcurrentHashMap.newKeySet();

    @Inject
    public NotificationDispatcher(EmulatorConfig config, FunctionsService functions,
                                  RequestContextPropagation contextPropagation) {
        this(config, functions, contextPropagation, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build());
    }

    NotificationDispatcher(EmulatorConfig config, FunctionsService functions,
                           RequestContextPropagation contextPropagation, HttpClient httpClient) {
        this.config = config;
        this.functions = functions;
        this.contextPropagation = contextPropagation;
        this.httpClient = httpClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("notifications-retry").daemon(true).factory());
    }

    /**
     * Records the delivery and starts it on a virtual thread carrying the caller's tenancy and
     * region, so a function subscription resolves the function in the caller's partition.
     */
    public DeliveryRecord deliver(Message message, Target target) {
        DeliveryRecord record = record(message, target);
        Attempt attempt = new Attempt(message, target, record, System.currentTimeMillis(), generation.get());
        attempt.self = contextPropagation != null ? contextPropagation.withCurrentContext(attempt) : attempt;
        live.add(attempt);
        Thread.ofVirtual().name("notifications-delivery").start(attempt.self);
        return record;
    }

    public List<DeliveryRecord> records(String topicId, String subscriptionId, String protocol) {
        List<DeliveryRecord> result = new ArrayList<>();
        for (DeliveryRecord r : records) {
            if ((topicId == null || topicId.equals(r.getTopicId()))
                    && (subscriptionId == null || subscriptionId.equals(r.getSubscriptionId()))
                    && (protocol == null || protocol.equals(r.getProtocol()))) {
                result.add(r);
            }
        }
        return result;
    }

    /** Stops every pending delivery to a subscription that was deleted or unsubscribed. */
    public void cancel(String subscriptionId) {
        for (Attempt attempt : live) {
            if (subscriptionId.equals(attempt.target.subscriptionId())) {
                attempt.removed = true;
                live.remove(attempt);
            }
        }
    }

    int liveAttempts() {
        return live.size();
    }

    public void clearRecords() {
        records.clear();
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
        live.clear();
        for (ScheduledFuture<?> retry : pendingRetries) {
            retry.cancel(false);
        }
        pendingRetries.clear();
        records.clear();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private DeliveryRecord record(Message message, Target target) {
        DeliveryRecord record = new DeliveryRecord();
        record.setId(UUID.randomUUID().toString());
        record.setMessageId(message.messageId());
        record.setMessageType(message.messageType());
        record.setTopicId(message.topicId());
        record.setTopicName(message.topicName());
        record.setSubscriptionId(target.subscriptionId());
        record.setProtocol(target.protocol());
        record.setEndpoint(target.endpoint());
        record.setTitle(message.title());
        record.setBody(message.body());
        record.setContentType(message.contentType());
        record.setConfirmationUrl(target.confirmationUrl());
        record.setUnsubscribeUrl(target.unsubscribeUrl());
        record.setTimestamp(Instant.now().toString());
        record.setStatus("PENDING");
        records.addLast(record);
        while (records.size() > MAX_RECORDS) {
            records.pollFirst();
        }
        return record;
    }

    /** One message to one endpoint; reschedules itself while the retry duration lasts. */
    private final class Attempt implements Runnable {

        private final Message message;
        private final Target target;
        private final DeliveryRecord record;
        private final long startedAt;
        private final long startedIn;
        private Runnable self;
        private volatile boolean removed;

        private Attempt(Message message, Target target, DeliveryRecord record, long startedAt, long startedIn) {
            this.message = message;
            this.target = target;
            this.record = record;
            this.startedAt = startedAt;
            this.startedIn = startedIn;
        }

        private boolean cancelled() {
            return removed || startedIn != generation.get();
        }

        @Override
        public void run() {
            if (cancelled()) {
                live.remove(this);
                return;
            }
            record.setAttempts(record.getAttempts() + 1);
            try {
                record.setStatus(send(message, target));
                record.setLastError(null);
                live.remove(this);
            } catch (DeliveryException | RuntimeException e) {
                record.setLastError(e.getMessage());
                retryOrDiscard();
            }
        }

        private void retryOrDiscard() {
            if (cancelled()) {
                live.remove(this);
                return;
            }
            int attempts = record.getAttempts();
            Duration delay = attempts == 1 ? Duration.ZERO : BACKOFF.get(Math.min(attempts - 2, BACKOFF.size() - 1));
            long elapsed = System.currentTimeMillis() - startedAt;
            if (elapsed + delay.toMillis() > target.maxRetryMillis()) {
                record.setStatus("FAILED");
                live.remove(this);
                LOG.warnv("Notifications: gave up delivering message {0} to subscription {1} after {2} attempts: {3}",
                        message.messageId(), target.subscriptionId(), attempts, record.getLastError());
                return;
            }
            record.setStatus("RETRYING");
            if (delay.isZero()) {
                Thread.ofVirtual().name("notifications-delivery").start(self);
                return;
            }
            ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
            holder[0] = scheduler.schedule(() -> {
                pendingRetries.remove(holder[0]);
                Thread.ofVirtual().name("notifications-delivery").start(self);
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
            pendingRetries.add(holder[0]);
        }
    }

    /** Sends one attempt and returns the record status on success; throws to ask for a retry. */
    String send(Message message, Target target) throws DeliveryException {
        return switch (target.protocol()) {
            case "CUSTOM_HTTPS", "HTTPS" -> {
                post(message, target);
                yield "DELIVERED";
            }
            case "ORACLE_FUNCTIONS" -> {
                if (SUBSCRIPTION_CONFIRMATION.equals(message.messageType())) {
                    yield "DELIVERED";
                }
                if (!config.services().functions().enabled()) {
                    LOG.infov("Notifications: message {0} not delivered to function {1} because Functions is disabled",
                            message.messageId(), target.endpoint());
                    yield "SKIPPED";
                }
                functions.invoke(target.endpoint(), message.body().getBytes(StandardCharsets.UTF_8),
                        message.contentType());
                yield "DELIVERED";
            }
            default -> "CAPTURED";
        };
    }

    private void post(Message message, Target target) throws DeliveryException {
        URI uri;
        String authorization = null;
        try {
            URI given = new URI(target.endpoint());
            uri = withoutUserInfo(given);
            if (given.getRawUserInfo() != null) {
                // URLDecoder applies form rules; a literal '+' in the credentials is not a space.
                String userInfo = URLDecoder.decode(given.getRawUserInfo().replace("+", "%2B"), StandardCharsets.UTF_8);
                authorization = "Basic " + Base64.getEncoder()
                        .encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
            }
        } catch (URISyntaxException e) {
            throw new DeliveryException("endpoint is not a valid URL: " + e.getMessage());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", message.contentType())
                .header("X-OCI-NS-MessageId", message.messageId())
                .header("X-OCI-NS-TopicOcid", message.topicId())
                .header("X-OCI-NS-TopicName", message.topicName())
                .header("X-OCI-NS-MessageType", message.messageType())
                .POST(HttpRequest.BodyPublishers.ofString(message.body(), StandardCharsets.UTF_8));
        if (target.unsubscribeUrl() != null) {
            request.header("X-OCI-NS-UnsubscribeURL", target.unsubscribeUrl());
        }
        if (target.confirmationUrl() != null) {
            request.header("X-OCI-NS-ConfirmationURL", target.confirmationUrl());
            request.header("X-OCI-NS-SubscriptionId", target.subscriptionId());
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DeliveryException("endpoint answered HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("interrupted");
        } catch (IOException e) {
            throw new DeliveryException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The endpoint with its credentials and fragment removed, keeping the raw (still encoded) path. */
    static URI withoutUserInfo(URI given) throws URISyntaxException {
        String authority = given.getHost() + (given.getPort() == -1 ? "" : ":" + given.getPort());
        String path = given.getRawPath() == null ? "" : given.getRawPath();
        return new URI(given.getScheme() + "://" + authority + path);
    }

    static final class DeliveryException extends Exception {
        DeliveryException(String message) {
            super(message);
        }
    }
}
