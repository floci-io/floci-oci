package io.floci.oci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.StoredTenancy;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.organizations.model.StoredLink;
import io.floci.oci.services.organizations.model.StoredOrganization;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OCI Organizations (Tenant Manager control plane): child tenancies, the organization a parent
 * tenancy forms with them, and the parent-child links. Every record lives in the parent
 * tenancy's partition, the way floci-aws keeps Organizations under the management account. A
 * child tenancy is only a registered tenancy OCID: requests signed with it land in its own empty
 * partitions, which is the isolation.
 */
@ApplicationScoped
public class OrganizationsService {

    private static final Logger LOG = Logger.getLogger(OrganizationsService.class);

    static final String WR_SERVICE = "organizations";
    static final String CORE_FEATURE = "CORE";
    static final String DEFAULT_GOVERNANCE_STATUS = "OPTED_IN";
    private static final Set<String> GOVERNANCE_STATUSES = Set.of("OPTED_IN", "OPTED_OUT");
    static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Pattern POLICY_NAME = Pattern.compile("[A-Za-z_]+");

    private final StorageBackend<String, StoredOrganization> organizations;
    private final StorageBackend<String, StoredOrganizationTenancy> organizationTenancies;
    private final StorageBackend<String, StoredLink> links;
    private final TenancyRegistry tenancies;
    private final IdentityService identity;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public OrganizationsService(StorageFactory storageFactory, TenancyRegistry tenancies,
                                IdentityService identity, WorkRequestService workRequests,
                                OciContext ociContext, EmulatorConfig config,
                                ServiceRegistry serviceRegistry) {
        this.organizations = storageFactory.createGlobal("organizations", "organizations.json",
                new TypeReference<Map<String, StoredOrganization>>() {});
        this.organizationTenancies = storageFactory.createGlobal("organizations",
                "organizations-tenancies.json",
                new TypeReference<Map<String, StoredOrganizationTenancy>>() {});
        this.links = storageFactory.createGlobal("organizations", "organizations-links.json",
                new TypeReference<Map<String, StoredLink>>() {});
        this.tenancies = tenancies;
        this.identity = identity;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
    }

    OrganizationsService(StorageBackend<String, StoredOrganization> organizations,
                         StorageBackend<String, StoredOrganizationTenancy> organizationTenancies,
                         StorageBackend<String, StoredLink> links,
                         TenancyRegistry tenancies, IdentityService identity,
                         WorkRequestService workRequests, OciContext ociContext) {
        this.organizations = organizations;
        this.organizationTenancies = organizationTenancies;
        this.links = links;
        this.tenancies = tenancies;
        this.identity = identity;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.config = null;
        this.serviceRegistry = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("organizations")
                .enabled(config.services().organizations().enabled())
                .storageKey("organizations")
                .resourceClasses(OrganizationsController.class)
                .global(true)
                .build());
    }

    // ── Child tenancies ────────────────────────────────────────────────────────

    /**
     * Creates a child tenancy of the caller and returns the work request id. The child is
     * registered with its own home region, joins the caller's organization (created on the
     * first child), and is linked to the parent for CORE plus every requested feature.
     */
    public synchronized String createChildTenancy(String compartmentId, String tenancyName,
                                                  String homeRegion, String adminEmail,
                                                  String policyName, String governanceStatus,
                                                  String subscriptionId, List<String> features) {
        requireNonBlank(compartmentId, "compartmentId");
        requireNonBlank(tenancyName, "tenancyName");
        requireNonBlank(homeRegion, "homeRegion");
        requireNonBlank(adminEmail, "adminEmail");
        String parentId = ociContext.tenancyId();
        if (!compartmentId.equals(parentId) || !ownerPartition().equals(parentId)) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: tenancy " + compartmentId);
        }
        String region = resolveSubscribedRegion(homeRegion);
        if (policyName != null && !POLICY_NAME.matcher(policyName).matches()) {
            throw OciException.invalidParameter("policyName must contain only letters and underscores");
        }
        String status = governanceStatus != null ? governanceStatus : DEFAULT_GOVERNANCE_STATUS;
        if (!GOVERNANCE_STATUSES.contains(status)) {
            throw OciException.invalidParameter("Invalid governanceStatus: " + governanceStatus);
        }

        StoredTenancy parent = tenancies.get(parentId);
        StoredTenancy child = tenancies.registerChild(parentId, tenancyName, region);
        StoredOrganization organization = organizationOf(parent, subscriptionId);
        String now = Instant.now().toString();
        if (findMembership(parentId, organization.getId(), parentId).isEmpty()) {
            saveMembership(parentId, membership(organization.getId(), parent, "PARENT", status, now));
        }
        saveMembership(parentId, membership(organization.getId(), child, "CHILD", status, now));

        Set<String> linkFeatures = new LinkedHashSet<>();
        linkFeatures.add(CORE_FEATURE);
        if (features != null) {
            linkFeatures.addAll(features);
        }
        for (String feature : linkFeatures) {
            StoredLink link = new StoredLink();
            link.setId(Ocids.generateGlobal("link", ociContext.realmOf(region)));
            link.setParentTenancyId(parentId);
            link.setChildTenancyId(child.getId());
            link.setTimeCreated(now);
            link.setLifecycleState("ACTIVE");
            link.setFeature(feature);
            link.setEtag(Etags.newEtag());
            TenancyAwareStorageBackend.putIn(links, parentId, link.getId(), link);
        }
        LOG.infov("createChildTenancy {0} ({1}) in {2}", tenancyName, child.getId(), region);
        return workRequests.succeeded(WR_SERVICE, "CREATE_CHILD_TENANCY", parentId,
                List.of(WorkRequestService.resource("tenancy", "CREATED", child.getId(), null)));
    }

    // ── Organizations ──────────────────────────────────────────────────────────

    /** The organization the caller belongs to, as parent or child: at most one. */
    public List<StoredOrganization> listOrganizations(String compartmentId) {
        requireNonBlank(compartmentId, "compartmentId");
        if (!compartmentId.equals(ociContext.tenancyId())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: tenancy " + compartmentId);
        }
        return TenancyAwareStorageBackend.scanTenancy(organizations, ownerPartition());
    }

    public StoredOrganization getOrganization(String organizationId) {
        return TenancyAwareStorageBackend.getIn(organizations, ownerPartition(), organizationId)
                .orElseThrow(() -> notFound("organization", organizationId));
    }

    /** Only the parent tenancy can change its organization. Returns the work request id. */
    public synchronized String updateOrganization(String organizationId, String defaultUcmSubscriptionId,
                                                  String ifMatch) {
        String parentId = ociContext.tenancyId();
        StoredOrganization organization = TenancyAwareStorageBackend.getIn(organizations, parentId, organizationId)
                .orElseThrow(() -> notFound("organization", organizationId));
        requireNonBlank(defaultUcmSubscriptionId, "defaultUcmSubscriptionId");
        Etags.checkIfMatch(ifMatch, organization.getEtag());
        organization.setDefaultUcmSubscriptionId(defaultUcmSubscriptionId);
        organization.setTimeUpdated(Instant.now().toString());
        organization.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(organizations, parentId, organization.getId(), organization);
        return workRequests.succeeded(WR_SERVICE, "ASSIGN_DEFAULT_SUBSCRIPTION", parentId,
                List.of(WorkRequestService.resource("organization", "UPDATED", organizationId, null)));
    }

    public List<StoredOrganizationTenancy> listOrganizationTenancies(String organizationId) {
        StoredOrganization organization = getOrganization(organizationId);
        return TenancyAwareStorageBackend.scanTenancy(organizationTenancies, ownerPartition()).stream()
                .filter(t -> organization.getId().equals(t.getOrganizationId()))
                .sorted(Comparator.comparing(StoredOrganizationTenancy::getTimeJoined))
                .toList();
    }

    public StoredOrganizationTenancy getOrganizationTenancy(String organizationId, String tenancyId) {
        getOrganization(organizationId);
        return findMembership(ownerPartition(), organizationId, tenancyId)
                .orElseThrow(() -> notFound("organization tenancy", tenancyId));
    }

    // ── Links ──────────────────────────────────────────────────────────────────

    public List<StoredLink> listLinks(String parentTenancyId, String childTenancyId, String feature,
                                      String lifecycleState, String sortOrder) {
        if (sortOrder != null && !SORT_ORDERS.contains(sortOrder)) {
            throw OciException.invalidParameter("Invalid sortOrder: " + sortOrder);
        }
        Comparator<StoredLink> order = Comparator.comparing(StoredLink::getTimeCreated)
                .thenComparing(StoredLink::getId);
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return TenancyAwareStorageBackend.scanTenancy(links, ownerPartition()).stream()
                .filter(l -> parentTenancyId == null || parentTenancyId.equals(l.getParentTenancyId()))
                .filter(l -> childTenancyId == null || childTenancyId.equals(l.getChildTenancyId()))
                .filter(l -> feature == null || feature.equals(l.getFeature()))
                .filter(l -> lifecycleState == null || lifecycleState.equals(l.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public StoredLink getLink(String linkId) {
        return TenancyAwareStorageBackend.getIn(links, ownerPartition(), linkId).orElseThrow(() -> notFound("link", linkId));
    }

    public String tenancyName(String tenancyId) {
        return tenancies.find(tenancyId).map(StoredTenancy::getName).orElse(null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * The partition holding the caller's organization: its own when it is a parent (or in no
     * organization), its parent's when it is a child.
     */
    private String ownerPartition() {
        String caller = ociContext.tenancyId();
        return tenancies.find(caller)
                .map(StoredTenancy::getParentTenancyId)
                .orElse(caller);
    }

    /**
     * {@code homeRegion} as a region name or key, which must be one the parent is subscribed
     * to (its home region included).
     */
    private String resolveSubscribedRegion(String homeRegion) {
        String name = Regions.byName(homeRegion)
                .or(() -> Regions.all().stream().filter(r -> r.key().equalsIgnoreCase(homeRegion)).findFirst())
                .map(Regions.Region::name)
                .orElse(homeRegion);
        if (!identity.isSubscribed(name)) {
            throw OciException.invalidParameter(
                    "homeRegion " + homeRegion + " is not a region the parent tenancy is subscribed to");
        }
        return name;
    }

    private StoredOrganization organizationOf(StoredTenancy parent, String subscriptionId) {
        List<StoredOrganization> existing = TenancyAwareStorageBackend.scanTenancy(organizations, parent.getId());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        String realm = ociContext.realmOf(parent.getHomeRegion());
        StoredOrganization organization = new StoredOrganization();
        organization.setId(Ocids.generateGlobal("organization", realm));
        organization.setCompartmentId(parent.getId());
        organization.setDefaultUcmSubscriptionId(subscriptionId != null ? subscriptionId
                : Ocids.generate("organizationssubscription", realm, Regions.code(parent.getHomeRegion())));
        organization.setLifecycleState("ACTIVE");
        organization.setTimeCreated(Instant.now().toString());
        organization.setDisplayName(parent.getName());
        organization.setParentName(parent.getName());
        organization.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(organizations, parent.getId(), organization.getId(), organization);
        return organization;
    }

    private static StoredOrganizationTenancy membership(String organizationId, StoredTenancy tenancy,
                                                        String role, String governanceStatus, String now) {
        StoredOrganizationTenancy membership = new StoredOrganizationTenancy();
        membership.setOrganizationId(organizationId);
        membership.setTenancyId(tenancy.getId());
        membership.setName(tenancy.getName());
        membership.setRole(role);
        membership.setGovernanceStatus(governanceStatus);
        membership.setLifecycleState("ACTIVE");
        membership.setTimeJoined(now);
        membership.setIsApprovedForTransfer(false);
        membership.setEtag(Etags.newEtag());
        return membership;
    }

    private Optional<StoredOrganizationTenancy> findMembership(String partition, String organizationId,
                                                               String tenancyId) {
        return TenancyAwareStorageBackend.getIn(organizationTenancies, partition, membershipKey(organizationId, tenancyId));
    }

    private void saveMembership(String partition, StoredOrganizationTenancy membership) {
        TenancyAwareStorageBackend.putIn(organizationTenancies, partition,
                membershipKey(membership.getOrganizationId(), membership.getTenancyId()), membership);
    }

    private static String membershipKey(String organizationId, String tenancyId) {
        return organizationId + ":" + tenancyId;
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
