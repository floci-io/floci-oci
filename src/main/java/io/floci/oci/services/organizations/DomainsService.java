package io.floci.oci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.organizations.model.StoredDomain;
import io.floci.oci.services.organizations.model.StoredDomainGovernance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Tenant Manager domains: a tenancy claims a DNS domain name, which no other tenancy can claim
 * until it is released, and can add governance to a claimed domain. floci-oci cannot see DNS,
 * so a claim is verified at once. Records live in the claiming tenancy's partition.
 */
@ApplicationScoped
public class DomainsService {

    private static final Logger LOG = Logger.getLogger(DomainsService.class);

    static final Set<String> LIST_LIFECYCLE_STATES =
            Set.of("CREATING", "ACTIVE", "INACTIVE", "UPDATING", "FAILED", "TERMINATED");
    static final Set<String> DOMAIN_STATUSES =
            Set.of("PENDING", "RELEASING", "RELEASED", "EXPIRING", "REVOKING", "REVOKED", "ACTIVE", "FAILED");
    static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");
    /** Lower-case labels joined by dots, ending in a letters-only or punycode ({@code xn--}) TLD. */
    private static final Pattern DOMAIN_NAME = Pattern.compile(
            "(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,63}|xn--[a-z0-9-]{1,59})");

    private final StorageBackend<String, StoredDomain> domains;
    private final StorageBackend<String, StoredDomainGovernance> governances;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;

    @Inject
    public DomainsService(StorageFactory storageFactory, WorkRequestService workRequests, OciContext ociContext) {
        this(storageFactory.createGlobal("organizations", "organizations-domains.json",
                        new TypeReference<Map<String, StoredDomain>>() {}),
                storageFactory.createGlobal("organizations", "organizations-domain-governances.json",
                        new TypeReference<Map<String, StoredDomainGovernance>>() {}),
                workRequests, ociContext);
    }

    DomainsService(StorageBackend<String, StoredDomain> domains,
                   StorageBackend<String, StoredDomainGovernance> governances,
                   WorkRequestService workRequests, OciContext ociContext) {
        this.domains = domains;
        this.governances = governances;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
    }

    /** The created domain and the id of its (already succeeded) work request. */
    public record Created(StoredDomain domain, String workRequestId) {
    }

    // ── Domains ────────────────────────────────────────────────────────────────

    /** Claims {@code domainName} for the caller. Returns the domain and its work request id. */
    public synchronized Created createDomain(String compartmentId, String domainName, String subscriptionEmail,
                                             Boolean isGovernanceEnabled, Map<String, String> freeformTags,
                                             Map<String, Map<String, Object>> definedTags) {
        String owner = requireCallerCompartment(compartmentId);
        requireNonBlank(domainName, "domainName");
        String name = domainName.toLowerCase(Locale.ROOT);
        if (!DOMAIN_NAME.matcher(name).matches()) {
            throw OciException.invalidParameter("Invalid domainName: " + domainName);
        }
        boolean claimed = TenancyAwareStorageBackend.scanAll(domains).stream()
                .anyMatch(d -> name.equals(d.getDomainName()) && "ACTIVE".equals(d.getLifecycleState()));
        if (claimed) {
            throw OciException.conflict("Domain " + name + " is already claimed");
        }
        String now = Instant.now().toString();
        StoredDomain domain = new StoredDomain();
        domain.setId(Ocids.generateGlobal("domain", ociContext.realm()));
        domain.setCompartmentId(owner);
        domain.setOwnerId(owner);
        domain.setDomainName(name);
        domain.setStatus("ACTIVE");
        domain.setLifecycleState("ACTIVE");
        domain.setTxtRecord("oci-domain-verification=" + UUID.randomUUID().toString().replace("-", ""));
        domain.setSubscriptionEmail(subscriptionEmail);
        domain.setIsGovernanceEnabled(Boolean.TRUE.equals(isGovernanceEnabled));
        domain.setTimeCreated(now);
        domain.setTimeUpdated(now);
        domain.setFreeformTags(freeformTags);
        domain.setDefinedTags(definedTags);
        domain.setEtag(Etags.newEtag());
        saveDomain(domain);
        LOG.infov("createDomain {0} ({1}) for {2}", name, domain.getId(), owner);
        String workRequestId = workRequests.succeeded(OrganizationsService.WR_SERVICE, "REGISTER_DOMAIN", owner,
                List.of(WorkRequestService.resource("domain", "CREATED", domain.getId(), null)));
        return new Created(domain, workRequestId);
    }

    public StoredDomain getDomain(String id) {
        return TenancyAwareStorageBackend.getIn(domains, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("domain", id));
    }

    public List<StoredDomain> listDomains(String compartmentId, String domainId, String lifecycleState,
                                          String status, String name, String sortBy, String sortOrder) {
        requireCallerCompartment(compartmentId);
        requireEnum(lifecycleState, LIST_LIFECYCLE_STATES, "lifecycleState");
        requireEnum(status, DOMAIN_STATUSES, "status");
        return TenancyAwareStorageBackend.scanTenancy(domains, compartmentId).stream()
                .filter(d -> domainId == null || domainId.equals(d.getId()))
                .filter(d -> lifecycleState == null || lifecycleState.equals(d.getLifecycleState()))
                .filter(d -> status == null || status.equals(d.getStatus()))
                .filter(d -> name == null || name.equalsIgnoreCase(d.getDomainName()))
                .sorted(order(sortBy, sortOrder, StoredDomain::getTimeCreated, StoredDomain::getDomainName,
                        StoredDomain::getId))
                .toList();
    }

    /** Only the tags can change. */
    public synchronized StoredDomain updateDomain(String id, Map<String, String> freeformTags,
                                                  Map<String, Map<String, Object>> definedTags, String ifMatch) {
        StoredDomain domain = activeDomain(id, "updated");
        Etags.checkIfMatch(ifMatch, domain.getEtag());
        if (freeformTags != null) {
            domain.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            domain.setDefinedTags(definedTags);
        }
        domain.setTimeUpdated(Instant.now().toString());
        domain.setEtag(Etags.newEtag());
        saveDomain(domain);
        return domain;
    }

    /**
     * Releases the domain so another tenancy can claim it. The record stays readable as
     * {@code DELETED} / {@code RELEASED}, and its governance becomes {@code INACTIVE}.
     */
    public synchronized void deleteDomain(String id, String ifMatch) {
        StoredDomain domain = activeDomain(id, "released");
        Etags.checkIfMatch(ifMatch, domain.getEtag());
        String now = Instant.now().toString();
        domain.setStatus("RELEASED");
        domain.setLifecycleState("DELETED");
        domain.setTimeUpdated(now);
        domain.setEtag(Etags.newEtag());
        saveDomain(domain);
        for (StoredDomainGovernance governance : governancesOf(domain.getCompartmentId(), id)) {
            if ("ACTIVE".equals(governance.getLifecycleState())) {
                deactivate(governance, now);
            }
        }
        LOG.infov("deleteDomain {0} ({1})", domain.getDomainName(), id);
    }

    // ── Domain governance ──────────────────────────────────────────────────────

    public synchronized StoredDomainGovernance createDomainGovernance(String compartmentId, String domainId,
                                                                      String subscriptionEmail, String onsTopicId,
                                                                      String onsSubscriptionId,
                                                                      Map<String, String> freeformTags,
                                                                      Map<String, Map<String, Object>> definedTags) {
        String owner = requireCallerCompartment(compartmentId);
        requireNonBlank(domainId, "domainId");
        requireNonBlank(subscriptionEmail, "subscriptionEmail");
        requireNonBlank(onsTopicId, "onsTopicId");
        requireNonBlank(onsSubscriptionId, "onsSubscriptionId");
        StoredDomain domain = activeDomain(domainId, "governed");
        boolean governed = governancesOf(owner, domainId).stream()
                .anyMatch(g -> "ACTIVE".equals(g.getLifecycleState()));
        if (governed) {
            throw OciException.conflict("Domain " + domain.getDomainName() + " already has domain governance");
        }
        String now = Instant.now().toString();
        StoredDomainGovernance governance = new StoredDomainGovernance();
        governance.setId(Ocids.generateGlobal("domaingovernance", ociContext.realm()));
        governance.setCompartmentId(owner);
        governance.setOwnerId(owner);
        governance.setDomainId(domainId);
        governance.setDomainName(domain.getDomainName());
        governance.setLifecycleState("ACTIVE");
        governance.setOnsTopicId(onsTopicId);
        governance.setOnsSubscriptionId(onsSubscriptionId);
        governance.setSubscriptionEmail(subscriptionEmail);
        governance.setIsGovernanceEnabled(true);
        governance.setTimeCreated(now);
        governance.setTimeUpdated(now);
        governance.setFreeformTags(freeformTags);
        governance.setDefinedTags(definedTags);
        governance.setEtag(Etags.newEtag());
        saveGovernance(governance);
        LOG.infov("createDomainGovernance {0} on {1}", governance.getId(), domain.getDomainName());
        return governance;
    }

    public StoredDomainGovernance getDomainGovernance(String id) {
        return TenancyAwareStorageBackend.getIn(governances, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("domain governance", id));
    }

    public List<StoredDomainGovernance> listDomainGovernances(String compartmentId, String domainId,
                                                              String domainGovernanceId, String lifecycleState,
                                                              String name, String sortBy, String sortOrder) {
        requireCallerCompartment(compartmentId);
        requireEnum(lifecycleState, LIST_LIFECYCLE_STATES, "lifecycleState");
        return TenancyAwareStorageBackend.scanTenancy(governances, compartmentId).stream()
                .filter(g -> domainId == null || domainId.equals(g.getDomainId()))
                .filter(g -> domainGovernanceId == null || domainGovernanceId.equals(g.getId()))
                .filter(g -> lifecycleState == null || lifecycleState.equals(g.getLifecycleState()))
                .filter(g -> name == null || name.equalsIgnoreCase(g.getDomainName()))
                .sorted(order(sortBy, sortOrder, StoredDomainGovernance::getTimeCreated,
                        StoredDomainGovernance::getDomainName, StoredDomainGovernance::getId))
                .toList();
    }

    public synchronized StoredDomainGovernance updateDomainGovernance(String id, String subscriptionEmail,
                                                                      Boolean isGovernanceEnabled,
                                                                      Map<String, String> freeformTags,
                                                                      Map<String, Map<String, Object>> definedTags,
                                                                      String ifMatch) {
        StoredDomainGovernance governance = activeGovernance(id, "updated");
        Etags.checkIfMatch(ifMatch, governance.getEtag());
        if (subscriptionEmail != null) {
            governance.setSubscriptionEmail(subscriptionEmail);
        }
        if (isGovernanceEnabled != null) {
            governance.setIsGovernanceEnabled(isGovernanceEnabled);
        }
        if (freeformTags != null) {
            governance.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            governance.setDefinedTags(definedTags);
        }
        governance.setTimeUpdated(Instant.now().toString());
        governance.setEtag(Etags.newEtag());
        saveGovernance(governance);
        return governance;
    }

    /** Removes governance from the domain: the record stays readable as {@code INACTIVE}. */
    public synchronized void deleteDomainGovernance(String id, String ifMatch) {
        StoredDomainGovernance governance = activeGovernance(id, "removed");
        Etags.checkIfMatch(ifMatch, governance.getEtag());
        deactivate(governance, Instant.now().toString());
        LOG.infov("deleteDomainGovernance {0}", id);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private StoredDomain activeDomain(String id, String action) {
        StoredDomain domain = getDomain(id);
        if (!"ACTIVE".equals(domain.getLifecycleState())) {
            throw OciException.conflict("The domain " + id + " is " + domain.getLifecycleState()
                    + " and cannot be " + action);
        }
        return domain;
    }

    private StoredDomainGovernance activeGovernance(String id, String action) {
        StoredDomainGovernance governance = getDomainGovernance(id);
        if (!"ACTIVE".equals(governance.getLifecycleState())) {
            throw OciException.conflict("The domain governance " + id + " is " + governance.getLifecycleState()
                    + " and cannot be " + action);
        }
        return governance;
    }

    private List<StoredDomainGovernance> governancesOf(String owner, String domainId) {
        return TenancyAwareStorageBackend.scanTenancy(governances, owner).stream()
                .filter(g -> domainId.equals(g.getDomainId()))
                .toList();
    }

    private void deactivate(StoredDomainGovernance governance, String now) {
        governance.setLifecycleState("INACTIVE");
        governance.setTimeUpdated(now);
        governance.setEtag(Etags.newEtag());
        saveGovernance(governance);
    }

    private void saveDomain(StoredDomain domain) {
        TenancyAwareStorageBackend.putIn(domains, domain.getCompartmentId(), domain.getId(), domain);
    }

    private void saveGovernance(StoredDomainGovernance governance) {
        TenancyAwareStorageBackend.putIn(governances, governance.getCompartmentId(), governance.getId(), governance);
    }

    /** {@code compartmentId} must be the caller's own tenancy. Returns it. */
    private String requireCallerCompartment(String compartmentId) {
        requireNonBlank(compartmentId, "compartmentId");
        if (!compartmentId.equals(ociContext.tenancyId())) {
            throw notFound("tenancy", compartmentId);
        }
        return compartmentId;
    }

    private static <T> Comparator<T> order(String sortBy, String sortOrder, Function<T, String> timeCreated,
                                           Function<T, String> displayName, Function<T, String> id) {
        requireEnum(sortBy, SORT_BY, "sortBy");
        requireEnum(sortOrder, OrganizationsService.SORT_ORDERS, "sortOrder");
        boolean byName = "displayName".equals(sortBy);
        Comparator<T> order = byName ? Comparator.comparing(displayName) : Comparator.comparing(timeCreated);
        order = order.thenComparing(id);
        String direction = sortOrder != null ? sortOrder : (byName ? "ASC" : "DESC");
        return "DESC".equals(direction) ? order.reversed() : order;
    }

    private static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + name);
        }
    }

    private static OciException notFound(String type, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + type + " " + id);
    }
}
