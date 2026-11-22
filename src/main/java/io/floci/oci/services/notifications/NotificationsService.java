package io.floci.oci.services.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.retrytoken.RetryTokenStore;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.notifications.model.StoredSubscription;
import io.floci.oci.services.notifications.model.StoredTopic;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * OCI Notifications (ONS), API version {@code 20181201}: topics, subscriptions and PublishMessage
 * (oci-go-sdk ons/ons_notificationcontrolplane_client.go and ons_notificationdataplane_client.go).
 *
 * <p>Every operation is synchronous. Topics are created ACTIVE. A function subscription is ACTIVE
 * at once, because "Confirmation isn't required for function subscriptions"; every other protocol
 * is PENDING until its confirmation URL is opened (Notification/Tasks/confirm-subscription.htm).
 * Messages are delivered only to ACTIVE subscriptions.
 *
 * <p>Every write is {@code synchronized}, so an {@code if-match} check, a name-uniqueness check
 * or a topic lookup and the write that depends on it happen as one step. Publishing takes the same
 * lock, so a subscription deleted or unsubscribed never gets a delivery its deletion cannot cancel.
 */
@ApplicationScoped
public class NotificationsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(NotificationsService.class);

    static final Set<String> PROTOCOLS = Set.of("CUSTOM_HTTPS", "EMAIL", "HTTPS", "ORACLE_FUNCTIONS",
            "PAGERDUTY", "SLACK", "SMS");
    private static final Set<String> URL_PROTOCOLS = Set.of("CUSTOM_HTTPS", "HTTPS", "PAGERDUTY", "SLACK");
    /** The default retry duration, two hours (oci-go-sdk ons/backoff_retry_policy.go). */
    static final long DEFAULT_MAX_RETRY_DURATION = 7_200_000L;
    /** "Message size per request: 64 KB" (Notification/Concepts/notificationoverview.htm). */
    static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{1,14}");
    private static final Set<String> METADATA_HOSTS = Set.of("169.254.169.254", "[fd00:ec2::254]");
    private static final String SHORT_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    static final String STORAGE_KEY = "notifications";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final StorageBackend<String, StoredTopic> topics;
    private final StorageBackend<String, StoredSubscription> subscriptions;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final FunctionsService functions;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper mapper;
    /** Invalidates the retry tokens that created a subscription removed without a DELETE on its own path. */
    private final BiConsumer<String, String> subscriptionRemoved;

    @Inject
    public NotificationsService(StorageFactory storageFactory, EmulatorConfig config, OciContext ociContext,
                                ServiceRegistry serviceRegistry, FunctionsService functions,
                                NotificationDispatcher dispatcher, ObjectMapper mapper,
                                RetryTokenStore retryTokens) {
        this.topics = storageFactory.create("notifications", "notifications-topics.json",
                new TypeReference<Map<String, StoredTopic>>() {});
        this.subscriptions = storageFactory.create("notifications", "notifications-subscriptions.json",
                new TypeReference<Map<String, StoredSubscription>>() {});
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.functions = functions;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.subscriptionRemoved = (tenancyId, subscriptionId) ->
                retryTokens.deletedResourceIn(STORAGE_KEY, tenancyId, subscriptionId);
    }

    NotificationsService(StorageBackend<String, StoredTopic> topics,
                         StorageBackend<String, StoredSubscription> subscriptions, EmulatorConfig config,
                         FunctionsService functions, NotificationDispatcher dispatcher, ObjectMapper mapper) {
        this.topics = topics;
        this.subscriptions = subscriptions;
        this.config = config;
        this.ociContext = OciContext.fromConfig(config);
        this.serviceRegistry = null;
        this.functions = functions;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.subscriptionRemoved = (tenancyId, subscriptionId) -> { };
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("notifications")
                .enabled(config.services().notifications().enabled())
                .storageKey(STORAGE_KEY)
                .resourceClasses(NotificationsController.class, NotificationsInspectionController.class)
                .build());
    }

    // ── Topics ─────────────────────────────────────────────────────────────────

    public synchronized StoredTopic createTopic(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = requireString(b, "name");
        String compartmentId = requireString(b, "compartmentId");
        // "The topic name must be unique across the tenancy" (ons/create_topic_details.go).
        if (topics.scan(k -> true).stream().anyMatch(t -> name.equals(t.getName()))) {
            throw OciException.conflict("A topic named " + name + " already exists");
        }
        StoredTopic topic = new StoredTopic();
        topic.setTopicId(Ocids.generate("onstopic", ociContext.realm(), ociContext.regionCode()));
        topic.setName(name);
        topic.setCompartmentId(compartmentId);
        topic.setDescription(str(b, "description"));
        topic.setLifecycleState("ACTIVE");
        topic.setTimeCreated(Instant.now().toString());
        topic.setApiEndpoint(ociContext.baseUrl());
        topic.setShortTopicId(shortTopicId());
        topic.setFreeformTags(stringMap(b.get("freeformTags")));
        topic.setDefinedTags(definedTags(b.get("definedTags")));
        topic.setEtag(Etags.newEtag());
        topics.put(topic.getTopicId(), topic);
        LOG.infov("createTopic {0} ({1})", name, topic.getTopicId());
        return topic;
    }

    public StoredTopic getTopic(String topicId) {
        return topics.get(topicId).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: topic " + topicId));
    }

    public List<StoredTopic> listTopics(String compartmentId, String id, String name, String lifecycleState,
                                        String sortBy, String sortOrder) {
        Comparator<StoredTopic> order = "LIFECYCLESTATE".equals(sortBy)
                ? Comparator.comparing(StoredTopic::getLifecycleState)
                : Comparator.comparing(StoredTopic::getTimeCreated);
        if (!"ASC".equals(sortOrder)) {
            order = order.reversed();
        }
        return topics.scan(k -> true).stream()
                .filter(t -> compartmentId.equals(t.getCompartmentId()))
                .filter(t -> id == null || id.equals(t.getTopicId()))
                .filter(t -> name == null || name.equals(t.getName()))
                .filter(t -> lifecycleState == null || lifecycleState.equals(t.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public synchronized StoredTopic updateTopic(String topicId, Map<String, Object> body, String ifMatch) {
        StoredTopic topic = getTopic(topicId);
        Etags.checkIfMatch(ifMatch, topic.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        if (b.containsKey("description")) {
            topic.setDescription(str(b, "description"));
        }
        if (b.get("freeformTags") instanceof Map<?, ?> tags) {
            topic.setFreeformTags(stringMap(tags));
        }
        if (b.get("definedTags") instanceof Map<?, ?> tags) {
            topic.setDefinedTags(definedTags(tags));
        }
        topic.setEtag(Etags.newEtag());
        topics.put(topicId, topic);
        return topic;
    }

    /** Deletes the topic and its subscriptions; a deleted topic answers 404, which Terraform waits for. */
    public synchronized void deleteTopic(String topicId, String ifMatch) {
        StoredTopic topic = getTopic(topicId);
        Etags.checkIfMatch(ifMatch, topic.getEtag());
        for (StoredSubscription subscription : subscriptionsOf(topicId)) {
            subscriptions.delete(subscription.getId());
            dispatcher.cancel(subscription.getId());
            subscriptionRemoved.accept(ociContext.tenancyId(), subscription.getId());
        }
        topics.delete(topicId);
    }

    public synchronized StoredTopic changeTopicCompartment(String topicId, String compartmentId, String ifMatch) {
        StoredTopic topic = getTopic(topicId);
        Etags.checkIfMatch(ifMatch, topic.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        topic.setCompartmentId(compartmentId);
        topic.setEtag(Etags.newEtag());
        topics.put(topicId, topic);
        return topic;
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    public synchronized StoredSubscription createSubscription(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String topicId = requireString(b, "topicId");
        String compartmentId = requireString(b, "compartmentId");
        String protocol = requireString(b, "protocol");
        String endpoint = requireString(b, "endpoint");
        StoredTopic topic = topics.get(topicId).orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: topic " + topicId));
        // "a new subscription must be in the same compartment as its parent topic" (create-subscription-*.htm).
        if (!compartmentId.equals(topic.getCompartmentId())) {
            throw OciException.invalidParameter("A subscription must be created in the compartment of its topic, "
                    + topic.getCompartmentId());
        }
        if (!PROTOCOLS.contains(protocol)) {
            throw OciException.invalidParameter("protocol must be one of " + PROTOCOLS + ", got " + protocol);
        }
        validateEndpoint(protocol, endpoint);

        StoredSubscription subscription = new StoredSubscription();
        subscription.setId(Ocids.generate("onssubscription", ociContext.realm(), ociContext.regionCode()));
        subscription.setTopicId(topicId);
        subscription.setCompartmentId(compartmentId);
        subscription.setProtocol(protocol);
        subscription.setEndpoint(endpoint);
        subscription.setMetadata(str(b, "metadata"));
        subscription.setCreatedTime(System.currentTimeMillis());
        subscription.setDeliveryPolicy(defaultDeliveryPolicy());
        subscription.setConfirmationToken(newToken());
        subscription.setFreeformTags(stringMap(b.get("freeformTags")));
        subscription.setDefinedTags(definedTags(b.get("definedTags")));
        boolean confirmationRequired = !"ORACLE_FUNCTIONS".equals(protocol);
        subscription.setLifecycleState(confirmationRequired ? "PENDING" : "ACTIVE");
        subscription.setEtag(Etags.newEtag());
        subscriptions.put(subscription.getId(), subscription);
        LOG.infov("createSubscription {0} {1} on topic {2} ({3})", protocol, endpoint, topicId,
                subscription.getId());
        if (confirmationRequired) {
            sendConfirmation(topic, subscription);
        }
        return subscription;
    }

    public StoredSubscription getSubscription(String subscriptionId) {
        return subscriptions.get(subscriptionId).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: subscription " + subscriptionId));
    }

    public List<StoredSubscription> listSubscriptions(String compartmentId, String topicId) {
        return subscriptions.scan(k -> true).stream()
                .filter(s -> compartmentId.equals(s.getCompartmentId()))
                .filter(s -> topicId == null || topicId.equals(s.getTopicId()))
                .sorted(Comparator.comparingLong(StoredSubscription::getCreatedTime))
                .toList();
    }

    public synchronized StoredSubscription updateSubscription(String subscriptionId, Map<String, Object> body, String ifMatch) {
        StoredSubscription subscription = getSubscription(subscriptionId);
        Etags.checkIfMatch(ifMatch, subscription.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, Object> deliveryPolicy = b.get("deliveryPolicy") instanceof Map<?, ?> policy
                ? validDeliveryPolicy(policy) : null;
        if (deliveryPolicy != null) {
            subscription.setDeliveryPolicy(deliveryPolicy);
        }
        if (b.get("freeformTags") instanceof Map<?, ?> tags) {
            subscription.setFreeformTags(stringMap(tags));
        }
        if (b.get("definedTags") instanceof Map<?, ?> tags) {
            subscription.setDefinedTags(definedTags(tags));
        }
        subscription.setEtag(Etags.newEtag());
        subscriptions.put(subscriptionId, subscription);
        return subscription;
    }

    public synchronized void deleteSubscription(String subscriptionId, String ifMatch) {
        StoredSubscription subscription = getSubscription(subscriptionId);
        Etags.checkIfMatch(ifMatch, subscription.getEtag());
        subscriptions.delete(subscriptionId);
        dispatcher.cancel(subscriptionId);
    }

    public synchronized StoredSubscription changeSubscriptionCompartment(String subscriptionId, String compartmentId,
                                                            String ifMatch) {
        StoredSubscription subscription = getSubscription(subscriptionId);
        Etags.checkIfMatch(ifMatch, subscription.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        subscription.setCompartmentId(compartmentId);
        subscription.setEtag(Etags.newEtag());
        subscriptions.put(subscriptionId, subscription);
        return subscription;
    }

    /** The outcome of opening a subscription's confirmation URL. */
    public record Confirmation(StoredSubscription subscription, StoredTopic topic, String unsubscribeUrl) {
    }

    /**
     * Opens a confirmation URL. The URL is followed from an email client, a browser or an HTTPS
     * endpoint, unsigned, so the subscription is found through the partition its token names rather
     * than the caller's tenancy.
     */
    public synchronized Confirmation confirmSubscription(String id, String token, String protocol) {
        String partition = partitionOf(id, token);
        StoredSubscription subscription = byToken(partition, id, token, protocol);
        subscription.setLifecycleState("ACTIVE");
        subscription.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(subscriptions, partition, id, subscription);
        StoredTopic topic = TenancyAwareStorageBackend.getIn(topics, partition, subscription.getTopicId())
                .orElseThrow(() -> notFound(id));
        LOG.infov("Subscription {0} confirmed", id);
        return new Confirmation(subscription, topic, unsubscribeUrl(subscription));
    }

    /** Opens an unsubscribe URL: the subscription is deleted. */
    public synchronized String unsubscribe(String id, String token, String protocol) {
        String partition = partitionOf(id, token);
        StoredSubscription subscription = byToken(partition, id, token, protocol);
        if (subscriptions instanceof TenancyAwareStorageBackend<StoredSubscription> tenancyAware) {
            tenancyAware.deleteAllForTenancy(partition, List.of(subscription.getId()));
        } else {
            subscriptions.delete(subscription.getId());
        }
        dispatcher.cancel(subscription.getId());
        subscriptionRemoved.accept(TenancyAwareStorageBackend.tenancyOf(partition), subscription.getId());
        LOG.infov("Subscription {0} unsubscribed", id);
        return "You have been unsubscribed from the topic.";
    }

    public StoredSubscription resendConfirmation(String subscriptionId) {
        StoredSubscription subscription = getSubscription(subscriptionId);
        if ("PENDING".equals(subscription.getLifecycleState())) {
            sendConfirmation(getTopic(subscription.getTopicId()), subscription);
        }
        return subscription;
    }

    // ── Publishing ─────────────────────────────────────────────────────────────

    public record PublishResult(String messageId, String timeStamp) {
    }

    /**
     * PublishMessage. With {@code messageType} {@code JSON} the body is an object whose
     * {@code default} key is required, and each protocol receives its own key when present
     * ({@code Email} is the documented one), else {@code default}.
     */
    public synchronized PublishResult publish(String topicId, Map<String, Object> body, String messageType) {
        StoredTopic topic = getTopic(topicId);
        Map<String, Object> b = body == null ? Map.of() : body;
        if (!(b.get("body") instanceof String text)) {
            throw OciException.missingParameter("body is required");
        }
        String title = b.get("title") instanceof String t ? t : null;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw OciException.invalidParameter("The message exceeds the " + MAX_MESSAGE_BYTES + " byte limit");
        }
        Map<String, Object> perProtocol = null;
        if (messageType != null && !"RAW_TEXT".equals(messageType)) {
            if (!"JSON".equals(messageType)) {
                throw OciException.invalidParameter("messageType must be JSON or RAW_TEXT, got " + messageType);
            }
            perProtocol = jsonMessage(text);
        }
        String messageId = UUID.randomUUID().toString();
        for (StoredSubscription subscription : activeSubscriptionsOf(topicId)) {
            String delivered = perProtocol == null ? text : protocolBody(perProtocol, subscription.getProtocol());
            dispatch(topic, subscription, messageId, title, delivered, "text/plain; charset=UTF-8");
        }
        return new PublishResult(messageId, Instant.now().toString());
    }

    /** Publishes an Events envelope (an ONS rule action) to a topic; the JSON is delivered as is. */
    public synchronized void publishEvent(String topicId, byte[] envelope) {
        StoredTopic topic = getTopic(topicId);
        String messageId = UUID.randomUUID().toString();
        String text = new String(envelope, StandardCharsets.UTF_8);
        for (StoredSubscription subscription : activeSubscriptionsOf(topicId)) {
            dispatch(topic, subscription, messageId, null, text, "application/json");
        }
    }

    private void dispatch(StoredTopic topic, StoredSubscription subscription, String messageId, String title,
                          String body, String contentType) {
        dispatcher.deliver(
                new NotificationDispatcher.Message(messageId, NotificationDispatcher.NOTIFICATION, topic.getTopicId(),
                        topic.getName(), title, body, contentType),
                new NotificationDispatcher.Target(subscription.getId(), subscription.getProtocol(),
                        subscription.getEndpoint(), maxRetryDuration(subscription), null,
                        unsubscribeUrl(subscription)));
    }

    private void sendConfirmation(StoredTopic topic, StoredSubscription subscription) {
        String confirmationUrl = link(subscription, "confirmation");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ConfirmationURL", confirmationUrl);
        String body;
        try {
            body = mapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw OciException.internalServerError("Could not build the confirmation message: " + e.getMessage());
        }
        dispatcher.deliver(
                new NotificationDispatcher.Message(UUID.randomUUID().toString(),
                        NotificationDispatcher.SUBSCRIPTION_CONFIRMATION, topic.getTopicId(), topic.getName(),
                        null, body, "application/json"),
                new NotificationDispatcher.Target(subscription.getId(), subscription.getProtocol(),
                        subscription.getEndpoint(), 0, confirmationUrl, unsubscribeUrl(subscription)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<StoredSubscription> subscriptionsOf(String topicId) {
        return subscriptions.scan(k -> true).stream().filter(s -> topicId.equals(s.getTopicId())).toList();
    }

    private List<StoredSubscription> activeSubscriptionsOf(String topicId) {
        return subscriptionsOf(topicId).stream().filter(s -> "ACTIVE".equals(s.getLifecycleState())).toList();
    }

    private void validateEndpoint(String protocol, String endpoint) {
        if (URL_PROTOCOLS.contains(protocol)) {
            URI uri;
            try {
                uri = new URI(endpoint);
            } catch (URISyntaxException e) {
                throw OciException.invalidParameter("endpoint must be a URL: " + e.getMessage());
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
                throw OciException.invalidParameter("endpoint must be an http or https URL, got " + endpoint);
            }
            // "Query parameters aren't allowed in URLs" (create-subscription-https.htm).
            if (uri.getRawQuery() != null) {
                throw OciException.invalidParameter("Query parameters aren't allowed in the endpoint URL");
            }
            if (METADATA_HOSTS.contains(uri.getHost())) {
                throw OciException.invalidParameter("endpoint must not address the instance metadata service");
            }
            return;
        }
        switch (protocol) {
            case "EMAIL" -> {
                if (!endpoint.contains("@")) {
                    throw OciException.invalidParameter("endpoint must be an email address, got " + endpoint);
                }
            }
            case "SMS" -> {
                if (!E164.matcher(endpoint).matches()) {
                    throw OciException.invalidParameter("endpoint must be a phone number in E.164 format, got "
                            + endpoint);
                }
            }
            case "ORACLE_FUNCTIONS" -> requireFunction(endpoint);
            default -> { }
        }
    }

    private void requireFunction(String functionId) {
        if (functions == null || !config.services().functions().enabled()) {
            return;
        }
        try {
            functions.getFunction(functionId);
        } catch (OciException e) {
            if (e.getHttpStatus() != 404) {
                throw e;
            }
            throw OciException.relatedResourceNotAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: function " + functionId);
        }
    }

    static Map<String, Object> defaultDeliveryPolicy() {
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("maxRetryDuration", DEFAULT_MAX_RETRY_DURATION);
        retry.put("policyType", "EXPONENTIAL");
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("backoffRetryPolicy", retry);
        return policy;
    }

    private static Map<String, Object> validDeliveryPolicy(Map<?, ?> policy) {
        if (!(policy.get("backoffRetryPolicy") instanceof Map<?, ?> retry)) {
            return defaultDeliveryPolicy();
        }
        if (!(retry.get("maxRetryDuration") instanceof Number duration) || duration.longValue() <= 0) {
            throw OciException.invalidParameter("backoffRetryPolicy.maxRetryDuration must be a positive number");
        }
        if (!"EXPONENTIAL".equals(retry.get("policyType"))) {
            throw OciException.invalidParameter("backoffRetryPolicy.policyType must be EXPONENTIAL");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("maxRetryDuration", duration.longValue());
        copy.put("policyType", "EXPONENTIAL");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backoffRetryPolicy", copy);
        return result;
    }

    private static long maxRetryDuration(StoredSubscription subscription) {
        if (subscription.getDeliveryPolicy() != null
                && subscription.getDeliveryPolicy().get("backoffRetryPolicy") instanceof Map<?, ?> retry
                && retry.get("maxRetryDuration") instanceof Number duration) {
            return duration.longValue();
        }
        return DEFAULT_MAX_RETRY_DURATION;
    }

    private Map<String, Object> jsonMessage(String text) {
        Map<String, Object> parsed;
        try {
            parsed = mapper.readValue(text, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            throw OciException.invalidParameter("A JSON message body must be a JSON object: " + e.getOriginalMessage());
        }
        if (parsed == null || !(parsed.get("default") instanceof String)) {
            throw OciException.invalidParameter("A JSON message body requires a \"default\" key");
        }
        return parsed;
    }

    private static String protocolBody(Map<String, Object> message, String protocol) {
        String key = "EMAIL".equals(protocol) ? "Email" : null;
        if (key != null && message.get(key) instanceof String value) {
            return value;
        }
        return (String) message.get("default");
    }

    String unsubscribeUrl(StoredSubscription subscription) {
        return link(subscription, "unsubscription");
    }

    private String link(StoredSubscription subscription, String action) {
        return ociContext.baseUrl() + "/20181201/subscriptions/" + subscription.getId() + "/" + action
                + "?token=" + URLEncoder.encode(subscription.getConfirmationToken(), StandardCharsets.UTF_8)
                + "&protocol=" + subscription.getProtocol();
    }

    /** A token naming the partition the subscription lives in, so unsigned confirmation calls can find it. */
    private String newToken() {
        String partition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(), ociContext.region());
        byte[] secret = new byte[18];
        RANDOM.nextBytes(secret);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(partition.getBytes(StandardCharsets.UTF_8)) + "." + encoder.encodeToString(secret);
    }

    private static String partitionOf(String id, String token) {
        if (token == null || token.indexOf('.') <= 0) {
            throw notFound(id);
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw notFound(id);
        }
    }

    private StoredSubscription byToken(String partition, String id, String token, String protocol) {
        Optional<StoredSubscription> found = TenancyAwareStorageBackend.getIn(subscriptions, partition, id);
        if (found.isEmpty() || !token.equals(found.get().getConfirmationToken())
                || (protocol != null && !protocol.equals(found.get().getProtocol()))) {
            throw notFound(id);
        }
        return found.get();
    }

    private static OciException notFound(String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: subscription " + id);
    }

    private static String shortTopicId() {
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            id.append(SHORT_ID_CHARS.charAt(RANDOM.nextInt(SHORT_ID_CHARS.length())));
        }
        return id.toString();
    }

    private static String requireString(Map<String, Object> b, String key) {
        String value = str(b, key);
        if (value == null) {
            throw OciException.missingParameter(key + " is required");
        }
        return value;
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Map<String, Object>>) map) : new LinkedHashMap<>();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return Stream.concat(
                CompartmentUsage.live(topics, tenancyId, compartmentId, StoredTopic::getCompartmentId,
                        StoredTopic::getLifecycleState, t -> "topic " + t.getTopicId()),
                CompartmentUsage.live(subscriptions, tenancyId, compartmentId, StoredSubscription::getCompartmentId,
                        StoredSubscription::getLifecycleState, s -> "subscription " + s.getId())).toList();
    }
}
