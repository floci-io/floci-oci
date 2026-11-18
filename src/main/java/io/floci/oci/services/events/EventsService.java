package io.floci.oci.services.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciEvents;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.events.model.StoredRule;
import io.floci.oci.services.functions.FunctionsService;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.streaming.StreamingService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * OCI Events: rules ({@code /20181201/rules}) that match emitted events against a condition and
 * deliver them to Functions (FAAS) and Streaming (OSS) actions. Notifications (ONS) actions are
 * stored and validated but not delivered, because floci-oci does not serve Notifications yet.
 *
 * <p>Matching runs on the request thread that emitted the event, so the rule lookup and the
 * compartment ancestry see the caller's tenancy and region; delivery runs on a virtual thread
 * carrying that same context, so a slow function never holds up the originating request.
 */
@ApplicationScoped
public class EventsService implements OciEvents, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(EventsService.class);

    private static final Set<String> ACTION_TYPES = Set.of("ONS", "OSS", "FAAS");
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final StorageBackend<String, StoredRule> rules;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final IdentityService identity;
    private final FunctionsService functions;
    private final StreamingService streaming;
    private final RequestContextPropagation contextPropagation;
    private final ObjectMapper mapper;

    @Inject
    public EventsService(StorageFactory storageFactory, EmulatorConfig config, ServiceRegistry serviceRegistry,
                         OciContext ociContext, IdentityService identity, FunctionsService functions,
                         StreamingService streaming, RequestContextPropagation contextPropagation,
                         ObjectMapper mapper) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.identity = identity;
        this.functions = functions;
        this.streaming = streaming;
        this.contextPropagation = contextPropagation;
        this.mapper = mapper;
        this.rules = storageFactory.create("events", "events-rules.json",
                new TypeReference<Map<String, StoredRule>>() {});
    }

    EventsService(StorageBackend<String, StoredRule> rules, EmulatorConfig config, ObjectMapper mapper) {
        this(rules, config, mapper, null, null);
    }

    EventsService(StorageBackend<String, StoredRule> rules, EmulatorConfig config, ObjectMapper mapper,
                  FunctionsService functions, StreamingService streaming) {
        this.rules = rules;
        this.config = config;
        this.ociContext = OciContext.fromConfig(config);
        this.serviceRegistry = null;
        this.identity = null;
        this.functions = functions;
        this.streaming = streaming;
        this.contextPropagation = null;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("events")
                .enabled(config.services().events().enabled())
                .storageKey("events")
                .resourceClasses(EventsController.class)
                .build());
    }

    // ── Rules ──────────────────────────────────────────────────────────────────

    public StoredRule createRule(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        StoredRule rule = new StoredRule();
        rule.setId(Ocids.generate("eventrule", ociContext.realm(), ociContext.regionCode()));
        rule.setCompartmentId(requireString(b, "compartmentId"));
        rule.setDisplayName(requireString(b, "displayName"));
        rule.setDescription(str(b, "description"));
        rule.setCondition(validCondition(requireString(b, "condition")));
        if (!(b.get("isEnabled") instanceof Boolean enabled)) {
            throw OciException.missingParameter("isEnabled is required");
        }
        rule.setEnabled(enabled);
        rule.setActions(actions(requireActions(b)));
        rule.setFreeformTags(stringMap(b.get("freeformTags")));
        rule.setDefinedTags(definedTags(b.get("definedTags")));
        rule.setLifecycleState(enabled ? "ACTIVE" : "INACTIVE");
        rule.setTimeCreated(Instant.now().toString());
        rule.setEtag(Etags.newEtag());
        rules.put(rule.getId(), rule);
        LOG.infov("createRule {0} ({1})", rule.getDisplayName(), rule.getId());
        return rule;
    }

    public StoredRule getRule(String ruleId) {
        return rules.get(ruleId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: rule " + ruleId));
    }

    public List<StoredRule> listRules(String compartmentId, String displayName, String lifecycleState,
                                      String sortBy, String sortOrder) {
        Comparator<StoredRule> order = switch (sortBy == null ? "TIME_CREATED" : sortBy) {
            case "ID" -> Comparator.comparing(StoredRule::getId);
            case "DISPLAY_NAME" -> Comparator.comparing(StoredRule::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(StoredRule::getTimeCreated);
        };
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return rules.scan(k -> true).stream()
                .filter(r -> compartmentId.equals(r.getCompartmentId()))
                .filter(r -> displayName == null || displayName.equals(r.getDisplayName()))
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public StoredRule updateRule(String ruleId, Map<String, Object> body, String ifMatch) {
        StoredRule rule = getRule(ruleId);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        // Every supplied field is validated before the rule changes, so a rejected update leaves it as it was.
        String condition = str(b, "condition");
        if (condition != null) {
            validCondition(condition);
        }
        // UpdateRuleDetails replaces the whole action list; replaced actions are dropped.
        List<Map<String, Object>> actions = b.get("actions") instanceof Map<?, ?> ? actions(requireActions(b)) : null;
        Map<String, String> freeformTags = b.get("freeformTags") instanceof Map<?, ?> tags ? stringMap(tags) : null;
        Map<String, Map<String, Object>> definedTags =
                b.get("definedTags") instanceof Map<?, ?> tags ? definedTags(tags) : null;

        String displayName = str(b, "displayName");
        if (displayName != null) {
            rule.setDisplayName(displayName);
        }
        if (b.containsKey("description")) {
            rule.setDescription(str(b, "description"));
        }
        if (condition != null) {
            rule.setCondition(condition);
        }
        if (b.get("isEnabled") instanceof Boolean enabled) {
            rule.setEnabled(enabled);
            rule.setLifecycleState(enabled ? "ACTIVE" : "INACTIVE");
        }
        if (actions != null) {
            rule.setActions(actions);
        }
        if (freeformTags != null) {
            rule.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            rule.setDefinedTags(definedTags);
        }
        rule.setEtag(Etags.newEtag());
        rules.put(ruleId, rule);
        return rule;
    }

    public void deleteRule(String ruleId, String ifMatch) {
        StoredRule rule = getRule(ruleId);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        rules.delete(ruleId);
    }

    public StoredRule changeCompartment(String ruleId, String compartmentId, String ifMatch) {
        StoredRule rule = getRule(ruleId);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        rule.setCompartmentId(compartmentId);
        rule.setEtag(Etags.newEtag());
        rules.put(ruleId, rule);
        return rule;
    }

    // ── Matching and delivery ──────────────────────────────────────────────────

    @Override
    public void emit(Event event) {
        if (!config.services().events().enabled()) {
            return;
        }
        try {
            List<StoredRule> matched = matchingRules(event);
            if (matched.isEmpty()) {
                return;
            }
            Map<String, Object> envelope = envelope(event);
            byte[] payload = mapper.writeValueAsBytes(envelope);
            for (StoredRule rule : matched) {
                for (Map<String, Object> action : rule.getActions()) {
                    if (Boolean.FALSE.equals(action.get("isEnabled"))) {
                        continue;
                    }
                    Runnable delivery = () -> deliver(rule, action, payload);
                    Thread.ofVirtual().start(contextPropagation != null
                            ? contextPropagation.withCurrentContext(delivery) : delivery);
                }
            }
        } catch (RuntimeException | JsonProcessingException e) {
            // An event must never fail the request that produced it.
            LOG.warnv("Could not process event {0} for {1}: {2}", event.eventType(), event.resourceName(),
                    e.getMessage());
        }
    }

    /** The enabled rules whose compartment is the event's compartment or one of its ancestors, and whose condition matches. */
    List<StoredRule> matchingRules(Event event) {
        Set<String> scope = compartmentAndAncestors(event.compartmentId());
        Map<String, Object> envelope = envelope(event);
        List<StoredRule> matched = new ArrayList<>();
        for (StoredRule rule : rules.scan(k -> true)) {
            if (!rule.isEnabled() || !scope.contains(rule.getCompartmentId())) {
                continue;
            }
            try {
                if (EventFilter.matches(parseCondition(rule.getCondition()), envelope)) {
                    matched.add(rule);
                }
            } catch (RuntimeException e) {
                // One unreadable condition must not stop delivery to the other rules.
                LOG.warnv("Rule {0}: condition could not be matched: {1}", rule.getId(), e.getMessage());
            }
        }
        return matched;
    }

    void deliver(StoredRule rule, Map<String, Object> action, byte[] payload) {
        String type = (String) action.get("actionType");
        if (!targetEnabled(type)) {
            LOG.infov("Rule {0}: {1} action {2} not delivered because its target service is disabled",
                    rule.getId(), type, action.get("id"));
            return;
        }
        try {
            switch (type) {
                case "FAAS" -> functions.invoke((String) action.get("functionId"), payload, "application/json");
                case "OSS" -> {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("value", Base64.getEncoder().encodeToString(payload));
                    streaming.putMessages((String) action.get("streamId"), List.of(message));
                }
                default -> LOG.debugv("Rule {0}: {1} actions are not delivered by floci-oci", rule.getId(), type);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Rule {0}: delivery to {1} action {2} failed: {3}", rule.getId(), type, action.get("id"),
                    e.getMessage());
        }
    }

    /** Whether the service an action delivers to is enabled; a disabled service never receives events. */
    private boolean targetEnabled(String actionType) {
        return switch (actionType) {
            case "FAAS" -> config.services().functions().enabled();
            case "OSS" -> config.services().streaming().enabled();
            default -> true;
        };
    }

    /** The CloudEvents 0.1 envelope OCI Events delivers (Events/Reference/eventenvelopereference.htm). */
    Map<String, Object> envelope(Event event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("compartmentId", event.compartmentId());
        data.put("compartmentName", compartmentName(event.compartmentId()));
        data.put("resourceName", event.resourceName());
        data.put("resourceId", event.resourceId());
        data.put("availabilityDomain", "all");
        if (event.freeformTags() != null) {
            data.put("freeFormTags", event.freeformTags());
        }
        if (event.definedTags() != null) {
            data.put("definedTags", event.definedTags());
        }
        data.put("additionalDetails", event.additionalDetails());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("cloudEventsVersion", "0.1");
        envelope.put("eventID", UUID.randomUUID().toString());
        envelope.put("eventType", event.eventType());
        envelope.put("source", event.source());
        envelope.put("eventTypeVersion", "2.0");
        envelope.put("eventTime", Instant.now().toString());
        envelope.put("contentType", "application/json");
        envelope.put("extensions", Map.of("compartmentId", event.compartmentId()));
        envelope.put("data", data);
        return envelope;
    }

    private Set<String> compartmentAndAncestors(String compartmentId) {
        Set<String> scope = new HashSet<>();
        String current = compartmentId;
        while (current != null && scope.add(current)) {
            current = parentOf(current);
        }
        return scope;
    }

    private String parentOf(String compartmentId) {
        if (identity == null || !config.services().identity().enabled()) {
            return null;
        }
        try {
            StoredCompartment c = identity.getCompartment(compartmentId);
            return Objects.equals(c.getId(), c.getCompartmentId()) ? null : c.getCompartmentId();
        } catch (OciException e) {
            LOG.debugv("Compartment {0} not found while matching rules: {1}", compartmentId, e.getMessage());
            return null;
        }
    }

    private String compartmentName(String compartmentId) {
        if (identity == null || !config.services().identity().enabled()) {
            return null;
        }
        try {
            return identity.getCompartment(compartmentId).getName();
        } catch (OciException e) {
            LOG.debugv("Compartment {0} not found for an event envelope: {1}", compartmentId, e.getMessage());
            return null;
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    private String validCondition(String condition) {
        parseCondition(condition);
        return condition;
    }

    private Map<String, Object> parseCondition(String condition) {
        try {
            Map<String, Object> parsed = mapper.readValue(condition, JSON_OBJECT);
            if (parsed == null) {
                throw OciException.invalidParameter("condition must be a JSON object, got " + condition);
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw OciException.invalidParameter("condition must be a JSON object: " + e.getOriginalMessage());
        }
    }

    private List<?> requireActions(Map<String, Object> b) {
        if (!(b.get("actions") instanceof Map<?, ?> list) || !(list.get("actions") instanceof List<?> actions)) {
            throw OciException.missingParameter("actions is required");
        }
        return actions;
    }

    private List<Map<String, Object>> actions(List<?> inputs) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Object raw : inputs) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                throw OciException.invalidParameter("actions entries must be objects");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) rawMap;
            String type = requireString(input, "actionType");
            if (!ACTION_TYPES.contains(type)) {
                throw OciException.invalidParameter("actionType must be one of " + ACTION_TYPES + ", got " + type);
            }
            if (!(input.get("isEnabled") instanceof Boolean enabled)) {
                throw OciException.missingParameter("isEnabled is required on every action");
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("actionType", type);
            action.put("id", Ocids.generate("eventaction", ociContext.realm(), ociContext.regionCode()));
            action.put("lifecycleMessage", "");
            action.put("lifecycleState", "ACTIVE");
            action.put("isEnabled", enabled);
            String description = str(input, "description");
            if (description != null) {
                action.put("description", description);
            }
            switch (type) {
                case "FAAS" -> putReference(action, input, "functionId", false, this::requireFunction);
                case "OSS" -> putReference(action, input, "streamId", true, this::requireStream);
                default -> putReference(action, input, "topicId", false, id -> { });
            }
            actions.add(action);
        }
        return actions;
    }

    private static void putReference(Map<String, Object> action, Map<String, Object> input, String field,
                                     boolean required, Consumer<String> check) {
        String id = str(input, field);
        if (id == null) {
            if (required) {
                throw OciException.missingParameter(field + " is required");
            }
            return;
        }
        check.accept(id);
        action.put(field, id);
    }

    private void requireFunction(String functionId) {
        if (functions == null || !config.services().functions().enabled()) {
            return;
        }
        try {
            functions.getFunction(functionId);
        } catch (OciException e) {
            throw relatedNotFound("function", functionId, e);
        }
    }

    private void requireStream(String streamId) {
        if (streaming == null || !config.services().streaming().enabled()) {
            return;
        }
        try {
            streaming.getStream(streamId);
        } catch (OciException e) {
            throw relatedNotFound("stream", streamId, e);
        }
    }

    private static OciException relatedNotFound(String type, String id, OciException cause) {
        if (cause.getHttpStatus() != 404) {
            return cause;
        }
        return OciException.relatedResourceNotAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + type + " " + id);
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
        return CompartmentUsage.live(rules, tenancyId, compartmentId, StoredRule::getCompartmentId,
                StoredRule::getLifecycleState, r -> "rule " + r.getId()).toList();
    }
}
