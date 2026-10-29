package io.floci.oci.services.governancerules;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.OrganizationMembershipListener;
import io.floci.oci.core.common.RegionRestriction;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.governancerules.model.StoredGovernanceRule;
import io.floci.oci.services.governancerules.model.StoredInclusionCriterion;
import io.floci.oci.services.governancerules.model.StoredTenancyAttachment;
import io.floci.oci.services.organizations.OrganizationsService;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * OCI Governance Rules control plane: rules a parent tenancy defines for the children of its
 * organization, the inclusion criteria that pick those children, and the tenancy attachments
 * that apply a rule to each one. Rules of every type are stored; only {@code ALLOWED_REGIONS}
 * is enforced, by refusing region subscriptions outside the allowed list.
 */
@ApplicationScoped
public class GovernanceRulesService implements RegionRestriction, OrganizationMembershipListener {

    private static final Logger LOG = Logger.getLogger(GovernanceRulesService.class);

    static final String WR_SERVICE = "governancerules";
    static final Set<String> RULE_TYPES = Set.of("QUOTA", "TAG", "ALLOWED_REGIONS");
    static final Set<String> RULE_STATES = Set.of("ACTIVE", "DELETED");
    static final Set<String> ATTACHMENT_STATES =
            Set.of("CREATING", "ACTIVE", "UPDATING", "NEEDS_ATTENTION", "DELETING", "DELETED");
    static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");
    static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Set<String> CREATION_OPTIONS = Set.of("TEMPLATE", "CLONE");
    private static final Set<String> CRITERION_TYPES = Set.of("TENANCY", "ALL");

    private final StorageBackend<String, StoredGovernanceRule> rules;
    private final StorageBackend<String, StoredInclusionCriterion> criteria;
    private final StorageBackend<String, StoredTenancyAttachment> attachments;
    private final OrganizationsService organizations;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final BooleanSupplier enabled;

    @Inject
    public GovernanceRulesService(StorageFactory storageFactory, OrganizationsService organizations,
                                  WorkRequestService workRequests, OciContext ociContext,
                                  EmulatorConfig config, ServiceRegistry serviceRegistry) {
        this.rules = storageFactory.createGlobal("governancerules", "governancerules-rules.json",
                new TypeReference<Map<String, StoredGovernanceRule>>() {});
        this.criteria = storageFactory.createGlobal("governancerules", "governancerules-inclusion-criteria.json",
                new TypeReference<Map<String, StoredInclusionCriterion>>() {});
        this.attachments = storageFactory.createGlobal("governancerules", "governancerules-tenancy-attachments.json",
                new TypeReference<Map<String, StoredTenancyAttachment>>() {});
        this.organizations = organizations;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.enabled = () -> config.services().governancerules().enabled();
    }

    GovernanceRulesService(StorageBackend<String, StoredGovernanceRule> rules,
                           StorageBackend<String, StoredInclusionCriterion> criteria,
                           StorageBackend<String, StoredTenancyAttachment> attachments,
                           OrganizationsService organizations, WorkRequestService workRequests,
                           OciContext ociContext, BooleanSupplier enabled) {
        this.rules = rules;
        this.criteria = criteria;
        this.attachments = attachments;
        this.organizations = organizations;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.config = null;
        this.serviceRegistry = null;
        this.enabled = enabled;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("governancerules")
                .enabled(config.services().governancerules().enabled())
                .storageKey("governancerules")
                .resourceClasses(GovernanceRulesController.class)
                .global(true)
                .build());
    }

    /** A created resource and the id of its (already succeeded) work request. */
    public record Created<T>(T resource, String workRequestId) {
    }

    // ── Governance rules ───────────────────────────────────────────────────────

    public synchronized Created<StoredGovernanceRule> createGovernanceRule(
            String compartmentId, String displayName, String description, String type, String creationOption,
            Map<String, Object> template, String relatedResourceId, Map<String, String> freeformTags,
            Map<String, Map<String, Object>> definedTags) {
        String parentId = requireRootCompartment(compartmentId);
        requireNonBlank(displayName, "displayName");
        requireNonBlank(type, "type");
        requireNonBlank(creationOption, "creationOption");
        requireEnum(type, RULE_TYPES, "type");
        requireEnum(creationOption, CREATION_OPTIONS, "creationOption");
        if ("CLONE".equals(creationOption)) {
            requireNonBlank(relatedResourceId, "relatedResourceId");
        }
        Map<String, Object> checkedTemplate = checkTemplate(type, template);

        String now = Instant.now().toString();
        StoredGovernanceRule rule = new StoredGovernanceRule();
        rule.setId(Ocids.generateGlobal("governancerule", realm()));
        rule.setCompartmentId(parentId);
        rule.setDisplayName(displayName);
        rule.setDescription(description);
        rule.setType(type);
        rule.setCreationOption(creationOption);
        rule.setTemplate(checkedTemplate);
        rule.setRelatedResourceId(relatedResourceId);
        rule.setLifecycleState("ACTIVE");
        rule.setTimeCreated(now);
        rule.setTimeUpdated(now);
        rule.setFreeformTags(freeformTags);
        rule.setDefinedTags(definedTags);
        rule.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(rules, parentId, rule.getId(), rule);
        LOG.infov("createGovernanceRule {0} ({1}) in {2}", rule.getId(), type, parentId);
        String workRequestId = workRequests.succeeded(WR_SERVICE, "CREATE_GOVERNANCE_RULE", parentId,
                List.of(WorkRequestService.resource("governanceRule", "CREATED", rule.getId(), null)));
        return new Created<>(rule, workRequestId);
    }

    public StoredGovernanceRule getGovernanceRule(String id) {
        return TenancyAwareStorageBackend.getIn(rules, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("governance rule", id));
    }

    public List<StoredGovernanceRule> listGovernanceRules(String compartmentId, String governanceRuleId,
                                                          String lifecycleState, String displayName,
                                                          String governanceRuleType, String sortBy,
                                                          String sortOrder) {
        requireCompartmentOrRule(compartmentId, governanceRuleId);
        requireEnum(lifecycleState, RULE_STATES, "lifecycleState");
        requireEnum(governanceRuleType, RULE_TYPES, "governanceRuleType");
        return TenancyAwareStorageBackend.scanTenancy(rules, ociContext.tenancyId()).stream()
                .filter(r -> governanceRuleId == null || governanceRuleId.equals(r.getId()))
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.getLifecycleState()))
                .filter(r -> displayName == null || displayName.equals(r.getDisplayName()))
                .filter(r -> governanceRuleType == null || governanceRuleType.equals(r.getType()))
                .sorted(order(sortBy, sortOrder, StoredGovernanceRule::getTimeCreated,
                        StoredGovernanceRule::getDisplayName, StoredGovernanceRule::getId))
                .toList();
    }

    public synchronized String updateGovernanceRule(String id, String displayName, String description,
                                                    Map<String, Object> template, String relatedResourceId,
                                                    Map<String, String> freeformTags,
                                                    Map<String, Map<String, Object>> definedTags, String ifMatch) {
        StoredGovernanceRule rule = activeRule(id);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        if (template != null) {
            rule.setTemplate(checkTemplate(rule.getType(), template));
        }
        if (displayName != null) {
            rule.setDisplayName(displayName);
        }
        if (description != null) {
            rule.setDescription(description);
        }
        if (relatedResourceId != null) {
            rule.setRelatedResourceId(relatedResourceId);
        }
        if (freeformTags != null) {
            rule.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            rule.setDefinedTags(definedTags);
        }
        rule.setTimeUpdated(Instant.now().toString());
        rule.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(rules, rule.getCompartmentId(), rule.getId(), rule);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_GOVERNANCE_RULE", rule.getCompartmentId(),
                List.of(WorkRequestService.resource("governanceRule", "UPDATED", id, null)));
    }

    /** Deletes a rule with its inclusion criteria and tenancy attachments. Returns the work request id. */
    public synchronized String deleteGovernanceRule(String id, String ifMatch) {
        StoredGovernanceRule rule = activeRule(id);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        String parentId = rule.getCompartmentId();
        String now = Instant.now().toString();
        rule.setLifecycleState("DELETED");
        rule.setTimeUpdated(now);
        rule.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(rules, parentId, rule.getId(), rule);
        for (StoredInclusionCriterion criterion : criteriaOf(parentId, id)) {
            markCriterionDeleted(parentId, criterion, now);
        }
        reconcile(parentId);
        LOG.infov("deleteGovernanceRule {0}", id);
        return workRequests.succeeded(WR_SERVICE, "DELETE_GOVERNANCE_RULE", parentId,
                List.of(WorkRequestService.resource("governanceRule", "DELETED", id, null)));
    }

    public synchronized String retryGovernanceRule(String id, String ifMatch) {
        StoredGovernanceRule rule = activeRule(id);
        Etags.checkIfMatch(ifMatch, rule.getEtag());
        reconcile(rule.getCompartmentId());
        return workRequests.succeeded(WR_SERVICE, "RETRY_GOVERNANCE_RULE", rule.getCompartmentId(),
                List.of(WorkRequestService.resource("governanceRule", "UPDATED", id, null)));
    }

    // ── Inclusion criteria ─────────────────────────────────────────────────────

    public synchronized Created<StoredInclusionCriterion> createInclusionCriterion(String governanceRuleId,
                                                                                  String type,
                                                                                  String associationType,
                                                                                  String tenancyId) {
        requireNonBlank(governanceRuleId, "governanceRuleId");
        requireNonBlank(type, "type");
        requireEnum(type, CRITERION_TYPES, "type");
        StoredGovernanceRule rule = activeRule(governanceRuleId);
        if ("TENANCY".equals(type)) {
            if (associationType != null && !"TENANCY".equals(associationType)) {
                throw OciException.invalidParameter("Invalid association type: " + associationType);
            }
            requireNonBlank(tenancyId, "association.tenancyId");
        } else if (tenancyId != null) {
            throw OciException.invalidParameter("An ALL inclusion criterion takes no association");
        }
        String parentId = rule.getCompartmentId();
        String now = Instant.now().toString();
        StoredInclusionCriterion criterion = new StoredInclusionCriterion();
        criterion.setId(Ocids.generateGlobal("inclusioncriterion", realm()));
        criterion.setGovernanceRuleId(governanceRuleId);
        criterion.setType(type);
        criterion.setTenancyId("TENANCY".equals(type) ? tenancyId : null);
        criterion.setLifecycleState("ACTIVE");
        criterion.setTimeCreated(now);
        criterion.setTimeUpdated(now);
        criterion.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(criteria, parentId, criterion.getId(), criterion);
        reconcile(parentId);
        String workRequestId = workRequests.succeeded(WR_SERVICE, "CREATE_INCLUSION_CRITERIA", parentId,
                List.of(WorkRequestService.resource("inclusionCriterion", "CREATED", criterion.getId(), null)));
        return new Created<>(criterion, workRequestId);
    }

    public StoredInclusionCriterion getInclusionCriterion(String id) {
        return TenancyAwareStorageBackend.getIn(criteria, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("inclusion criterion", id));
    }

    public List<StoredInclusionCriterion> listInclusionCriteria(String governanceRuleId, String inclusionCriterionId,
                                                                String lifecycleState, String sortBy,
                                                                String sortOrder) {
        requireNonBlank(governanceRuleId, "governanceRuleId");
        requireEnum(lifecycleState, RULE_STATES, "lifecycleState");
        getGovernanceRule(governanceRuleId);
        return criteriaOf(ociContext.tenancyId(), governanceRuleId).stream()
                .filter(c -> inclusionCriterionId == null || inclusionCriterionId.equals(c.getId()))
                .filter(c -> lifecycleState == null || lifecycleState.equals(c.getLifecycleState()))
                .sorted(order(sortBy, sortOrder, StoredInclusionCriterion::getTimeCreated,
                        StoredInclusionCriterion::getType, StoredInclusionCriterion::getId))
                .toList();
    }

    public synchronized String deleteInclusionCriterion(String id, String ifMatch) {
        StoredInclusionCriterion criterion = getInclusionCriterion(id);
        Etags.checkIfMatch(ifMatch, criterion.getEtag());
        if ("DELETED".equals(criterion.getLifecycleState())) {
            throw conflict("inclusion criterion " + id, "DELETED", "deleted");
        }
        String parentId = ociContext.tenancyId();
        markCriterionDeleted(parentId, criterion, Instant.now().toString());
        reconcile(parentId);
        return workRequests.succeeded(WR_SERVICE, "DELETE_INCLUSION_CRITERIA", parentId,
                List.of(WorkRequestService.resource("inclusionCriterion", "DELETED", id, null)));
    }

    // ── Tenancy attachments ────────────────────────────────────────────────────

    public StoredTenancyAttachment getTenancyAttachment(String id) {
        String parentId = ociContext.tenancyId();
        return TenancyAwareStorageBackend.getIn(attachments, parentId, id)
                .orElseThrow(() -> notFound("tenancy attachment", id));
    }

    public List<StoredTenancyAttachment> listTenancyAttachments(String compartmentId, String tenancyAttachmentId,
                                                                String governanceRuleId, String lifecycleState,
                                                                String childTenancyId, String sortBy,
                                                                String sortOrder) {
        requireCompartmentOrRule(compartmentId, governanceRuleId);
        requireEnum(lifecycleState, ATTACHMENT_STATES, "lifecycleState");
        String parentId = ociContext.tenancyId();
        return TenancyAwareStorageBackend.scanTenancy(attachments, parentId).stream()
                .filter(a -> tenancyAttachmentId == null || tenancyAttachmentId.equals(a.getId()))
                .filter(a -> governanceRuleId == null || governanceRuleId.equals(a.getGovernanceRuleId()))
                .filter(a -> lifecycleState == null || lifecycleState.equals(a.getLifecycleState()))
                .filter(a -> childTenancyId == null || childTenancyId.equals(a.getTenancyId()))
                .sorted(order(sortBy, sortOrder, StoredTenancyAttachment::getTimeCreated,
                        StoredTenancyAttachment::getTenancyId, StoredTenancyAttachment::getId))
                .toList();
    }

    public synchronized String retryTenancyAttachment(String id, String ifMatch) {
        StoredTenancyAttachment attachment = getTenancyAttachment(id);
        Etags.checkIfMatch(ifMatch, attachment.getEtag());
        attachment.setTimeLastAttempted(Instant.now().toString());
        attachment.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(attachments, attachment.getCompartmentId(), attachment.getId(), attachment);
        return workRequests.succeeded(WR_SERVICE, "RETRY_TENANCY_ATTACHMENT", attachment.getCompartmentId(),
                List.of(WorkRequestService.resource("tenancyAttachment", "UPDATED", id, null)));
    }

    // ── Enforced governance rules (the child's view) ──────────────────────────

    /** The rules applied to the caller's tenancy, as {@code EnforcedGovernanceRule} wire maps. */
    public List<Map<String, Object>> listEnforcedGovernanceRules(String compartmentId, String enforcedGovernanceRuleId,
                                                                 String governanceRuleType, String displayName,
                                                                 String sortBy, String sortOrder) {
        String caller = ociContext.tenancyId();
        if (compartmentId != null && !compartmentId.equals(caller)) {
            throw notFound("tenancy", compartmentId);
        }
        requireEnum(governanceRuleType, RULE_TYPES, "governanceRuleType");
        requireEnum(sortBy, SORT_BY, "sortBy");
        requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        Comparator<Map<String, Object>> order = "displayName".equals(sortBy)
                ? Comparator.comparing(m -> (String) m.get("governanceRuleDisplayName"))
                : Comparator.comparing(m -> (String) m.get("timeCreated"));
        order = order.thenComparing(m -> (String) m.get("id"));
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return enforcedOn(caller).stream()
                .map(Enforced::wire)
                .filter(m -> enforcedGovernanceRuleId == null || enforcedGovernanceRuleId.equals(m.get("id")))
                .filter(m -> governanceRuleType == null || governanceRuleType.equals(m.get("type")))
                .filter(m -> displayName == null || displayName.equals(m.get("governanceRuleDisplayName")))
                .sorted(order)
                .toList();
    }

    public Enforced getEnforcedGovernanceRule(String id) {
        return enforcedOn(ociContext.tenancyId()).stream()
                .filter(enforced -> id.equals(enforced.wire().get("id")))
                .findFirst()
                .orElseThrow(() -> notFound("enforced governance rule", id));
    }

    @Override
    public Optional<String> denial(String tenancyId, String regionName) {
        if (!enabled.getAsBoolean()) {
            return Optional.empty();
        }
        Optional<String> parentId = organizations.parentOf(tenancyId);
        if (parentId.isEmpty()) {
            return Optional.empty();
        }
        for (StoredTenancyAttachment attachment : activeAttachmentsOf(parentId.get(), tenancyId)) {
            Optional<StoredGovernanceRule> rule = TenancyAwareStorageBackend.getIn(rules, parentId.get(),
                    attachment.getGovernanceRuleId());
            if (rule.isPresent() && "ALLOWED_REGIONS".equals(rule.get().getType())
                    && !allowedRegions(rule.get()).contains(regionName)) {
                return Optional.of("Region " + regionName + " is not allowed by governance rule "
                        + rule.get().getDisplayName() + " (" + rule.get().getId() + ")");
            }
        }
        return Optional.empty();
    }

    /** Keeps the tenancy attachments in step when a member joins, leaves or changes its governance status. */
    @Override
    public void membershipsChanged(String parentTenancyId) {
        reconcile(parentTenancyId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Brings the tenancy attachments of {@code parentId} in line with its active rules, their
     * inclusion criteria and the children that opted in to governance: a newly governed child
     * gets an ACTIVE attachment, one no longer governed has its attachment DELETED.
     */
    private synchronized void reconcile(String parentId) {
        Map<String, String> governed = new LinkedHashMap<>();
        Set<String> optedIn = new LinkedHashSet<>();
        for (StoredOrganizationTenancy child : organizations.activeChildren(parentId)) {
            if ("OPTED_IN".equals(child.getGovernanceStatus())) {
                optedIn.add(child.getTenancyId());
            }
        }
        for (StoredGovernanceRule rule : TenancyAwareStorageBackend.scanTenancy(rules, parentId)) {
            if (!"ACTIVE".equals(rule.getLifecycleState())) {
                continue;
            }
            for (StoredInclusionCriterion criterion : criteriaOf(parentId, rule.getId())) {
                if (!"ACTIVE".equals(criterion.getLifecycleState())) {
                    continue;
                }
                for (String child : optedIn) {
                    if ("ALL".equals(criterion.getType()) || child.equals(criterion.getTenancyId())) {
                        governed.put(rule.getId() + "/" + child, rule.getId());
                    }
                }
            }
        }
        String now = Instant.now().toString();
        Set<String> seen = new LinkedHashSet<>();
        for (StoredTenancyAttachment attachment : TenancyAwareStorageBackend.scanTenancy(attachments, parentId)) {
            String pair = attachment.getGovernanceRuleId() + "/" + attachment.getTenancyId();
            seen.add(pair);
            String wanted = governed.containsKey(pair) ? "ACTIVE" : "DELETED";
            if (!wanted.equals(attachment.getLifecycleState())) {
                attachment.setLifecycleState(wanted);
                attachment.setTimeUpdated(now);
                attachment.setEtag(Etags.newEtag());
                TenancyAwareStorageBackend.putIn(attachments, parentId, attachment.getId(), attachment);
            }
        }
        for (Map.Entry<String, String> entry : governed.entrySet()) {
            if (seen.contains(entry.getKey())) {
                continue;
            }
            StoredTenancyAttachment attachment = new StoredTenancyAttachment();
            attachment.setId(Ocids.generateGlobal("tenancyattachment", realm()));
            attachment.setEnforcedGovernanceRuleId(Ocids.generateGlobal("enforcedgovernancerule", realm()));
            attachment.setCompartmentId(parentId);
            attachment.setGovernanceRuleId(entry.getValue());
            attachment.setTenancyId(entry.getKey().substring(entry.getKey().indexOf('/') + 1));
            attachment.setLifecycleState("ACTIVE");
            attachment.setTimeCreated(now);
            attachment.setTimeUpdated(now);
            attachment.setEtag(Etags.newEtag());
            TenancyAwareStorageBackend.putIn(attachments, parentId, attachment.getId(), attachment);
        }
    }

    private List<StoredTenancyAttachment> activeAttachmentsOf(String parentId, String tenancyId) {
        return TenancyAwareStorageBackend.scanTenancy(attachments, parentId).stream()
                .filter(a -> tenancyId.equals(a.getTenancyId()) && "ACTIVE".equals(a.getLifecycleState()))
                .toList();
    }

    /** An enforced governance rule's wire shape and its etag (the attachment's). */
    public record Enforced(Map<String, Object> wire, String etag) {
    }

    private List<Enforced> enforcedOn(String tenancyId) {
        Optional<String> parentId = organizations.parentOf(tenancyId);
        if (parentId.isEmpty()) {
            return List.of();
        }
        return activeAttachmentsOf(parentId.get(), tenancyId).stream()
                .flatMap(attachment -> TenancyAwareStorageBackend.getIn(rules, parentId.get(),
                                attachment.getGovernanceRuleId()).stream()
                        .map(rule -> new Enforced(enforcedWire(attachment, rule), attachment.getEtag())))
                .toList();
    }

    private static Map<String, Object> enforcedWire(StoredTenancyAttachment attachment, StoredGovernanceRule rule) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", attachment.getEnforcedGovernanceRuleId());
        wire.put("compartmentId", attachment.getTenancyId());
        wire.put("governanceRuleDisplayName", rule.getDisplayName());
        wire.put("type", rule.getType());
        wire.put("template", rule.getTemplate());
        wire.put("lifecycleState", "ACTIVE");
        wire.put("timeCreated", attachment.getTimeCreated());
        wire.put("timeUpdated", rule.getTimeUpdated().compareTo(attachment.getTimeUpdated()) > 0
                ? rule.getTimeUpdated() : attachment.getTimeUpdated());
        return wire;
    }

    @SuppressWarnings("unchecked")
    private static List<String> allowedRegions(StoredGovernanceRule rule) {
        return (List<String>) rule.getTemplate().get("regions");
    }

    /**
     * The template, checked against the rule type: its {@code type} discriminator must match,
     * and the fields the SDK marks mandatory for that type must be present.
     */
    private static Map<String, Object> checkTemplate(String ruleType, Map<String, Object> template) {
        if (template == null) {
            throw OciException.missingParameter("Missing required parameter: template");
        }
        Object templateType = template.get("type");
        if (templateType != null && !ruleType.equals(templateType)) {
            throw OciException.invalidParameter("Template type " + templateType + " does not match rule type " + ruleType);
        }
        Map<String, Object> checked = new LinkedHashMap<>(template);
        checked.put("type", ruleType);
        switch (ruleType) {
            case "ALLOWED_REGIONS" -> {
                requireTemplateString(checked, "displayName");
                List<String> regions = requireTemplateStrings(checked, "regions");
                for (String region : regions) {
                    if (Regions.byName(region).isEmpty()) {
                        throw OciException.invalidParameter("Unknown region in template: " + region);
                    }
                }
            }
            case "QUOTA" -> {
                requireTemplateString(checked, "displayName");
                requireTemplateStrings(checked, "statements");
            }
            case "TAG" -> requireTemplateString(checked, "name");
            default -> throw OciException.invalidParameter("Invalid type: " + ruleType);
        }
        return checked;
    }

    private static void requireTemplateString(Map<String, Object> template, String key) {
        if (!(template.get(key) instanceof String value) || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: template." + key);
        }
    }

    private static List<String> requireTemplateStrings(Map<String, Object> template, String key) {
        if (template.get(key) instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> item instanceof String)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw OciException.missingParameter("Missing required parameter: template." + key
                + " (a non-empty list of strings)");
    }

    private StoredGovernanceRule activeRule(String id) {
        StoredGovernanceRule rule = getGovernanceRule(id);
        if (!"ACTIVE".equals(rule.getLifecycleState())) {
            throw conflict("governance rule " + id, rule.getLifecycleState(), "changed");
        }
        return rule;
    }

    private List<StoredInclusionCriterion> criteriaOf(String parentId, String governanceRuleId) {
        return TenancyAwareStorageBackend.scanTenancy(criteria, parentId).stream()
                .filter(c -> governanceRuleId.equals(c.getGovernanceRuleId()))
                .toList();
    }

    private void markCriterionDeleted(String parentId, StoredInclusionCriterion criterion, String now) {
        criterion.setLifecycleState("DELETED");
        criterion.setTimeUpdated(now);
        criterion.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(criteria, parentId, criterion.getId(), criterion);
    }

    /** Rules live in the root compartment of a tenancy that is not itself a child. */
    private String requireRootCompartment(String compartmentId) {
        requireNonBlank(compartmentId, "compartmentId");
        String caller = ociContext.tenancyId();
        if (!compartmentId.equals(caller)) {
            if (compartmentId.startsWith("ocid1.compartment.")) {
                throw OciException.invalidParameter("Governance rules can only be created in the root compartment");
            }
            throw notFound("tenancy", compartmentId);
        }
        if (organizations.parentOf(caller).isPresent()) {
            throw notFound("tenancy", compartmentId);
        }
        return caller;
    }

    private void requireCompartmentOrRule(String compartmentId, String governanceRuleId) {
        if (compartmentId == null && governanceRuleId == null) {
            throw OciException.missingParameter("Either compartmentId or governanceRuleId must be supplied");
        }
        if (compartmentId != null && !compartmentId.equals(ociContext.tenancyId())) {
            throw notFound("tenancy", compartmentId);
        }
    }

    private String realm() {
        return ociContext.realm();
    }

    static <T> Comparator<T> order(String sortBy, String sortOrder, Function<T, String> timeCreated,
                                   Function<T, String> displayName, Function<T, String> id) {
        requireEnum(sortBy, SORT_BY, "sortBy");
        requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        Comparator<T> order = "displayName".equals(sortBy)
                ? Comparator.comparing(displayName)
                : Comparator.comparing(timeCreated);
        order = order.thenComparing(id);
        return "DESC".equals(sortOrder) ? order.reversed() : order;
    }

    static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + name);
        }
    }

    private static OciException conflict(String resource, String state, String action) {
        return OciException.conflict("The " + resource + " is " + state + " and cannot be " + action);
    }

    private static OciException notFound(String type, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + type + " " + id);
    }
}
