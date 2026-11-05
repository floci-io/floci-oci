package io.floci.oci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.OrganizationMembershipListener;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.StoredTenancy;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import io.floci.oci.services.organizations.model.StoredSubscriptionMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant Manager subscriptions. Billing data lives in Oracle's commerce systems, so floci-oci
 * models the minimum the API needs: every tenancy owns one V2 (cloud) subscription whose OCID is
 * derived from the tenancy OCID, a tenancy consumes the subscriptions mapped to it (a child with
 * no mapping consumes its organization's default subscription), and the owner of a subscription
 * maps it to itself or to a child of its organization.
 */
@ApplicationScoped
public class SubscriptionsService implements OrganizationMembershipListener {

    private static final Logger LOG = Logger.getLogger(SubscriptionsService.class);

    static final Set<String> ENTITY_VERSIONS = Set.of("V1", "V2");
    static final Set<String> MAPPING_STATES =
            Set.of("CREATING", "ACTIVE", "INACTIVE", "UPDATING", "DELETING", "DELETED", "FAILED");
    static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");
    private static final String SERVICE_NAME = "CLOUDCM";
    private static final String CURRENCY = "USD";

    private final StorageBackend<String, StoredSubscriptionMapping> mappings;
    private final OrganizationsService organizations;
    private final TenancyRegistry tenancies;
    private final OciContext ociContext;

    @Inject
    public SubscriptionsService(StorageFactory storageFactory, OrganizationsService organizations,
                                TenancyRegistry tenancies, OciContext ociContext) {
        this(storageFactory.createGlobal("organizations", "organizations-subscription-mappings.json",
                        new TypeReference<Map<String, StoredSubscriptionMapping>>() {}),
                organizations, tenancies, ociContext);
    }

    SubscriptionsService(StorageBackend<String, StoredSubscriptionMapping> mappings,
                         OrganizationsService organizations, TenancyRegistry tenancies, OciContext ociContext) {
        this.mappings = mappings;
        this.organizations = organizations;
        this.tenancies = tenancies;
        this.ociContext = ociContext;
    }

    /**
     * The OCID of the subscription {@code tenancy} owns: stable, derived from the tenancy OCID,
     * so it needs no record. Organizations uses it as the default subscription of an organization.
     */
    static String ownSubscriptionId(String tenancyId, String homeRegion, String realm) {
        return new Ocids.Ocid("organizationssubscription", realm, Regions.code(homeRegion),
                digest(tenancyId).substring(0, 60)).toString();
    }

    // ── Subscriptions (owned) ──────────────────────────────────────────────────

    /** The subscriptions the caller owns: exactly one, as V2 summaries. */
    public List<Map<String, Object>> listSubscriptions(String compartmentId, String subscriptionId,
                                                       String entityVersion, String sortBy, String sortOrder) {
        String caller = callerCompartment(compartmentId, false);
        requireEnum(entityVersion, ENTITY_VERSIONS, "entityVersion");
        requireSort(sortBy, sortOrder);
        Map<String, Object> own = subscriptionWire(ownSubscriptionId(caller), caller, caller);
        if ("V1".equals(entityVersion) || (subscriptionId != null && !subscriptionId.equals(own.get("id")))) {
            return List.of();
        }
        return List.of(own);
    }

    public Map<String, Object> getSubscription(String subscriptionId) {
        String caller = ociContext.tenancyId();
        if (!subscriptionId.equals(ownSubscriptionId(caller))) {
            throw notFound("subscription", subscriptionId);
        }
        return subscriptionWire(subscriptionId, caller, caller);
    }

    public List<Map<String, Object>> listSubscriptionLineItems(String subscriptionId, String sortBy, String sortOrder) {
        requireSort(sortBy, sortOrder);
        getSubscription(subscriptionId);
        return List.of(lineItem(subscriptionId, ociContext.tenancyId()));
    }

    /** The regions of the caller's realm, in the SDK's region table order. */
    public List<Map<String, Object>> listAvailableRegions(String subscriptionId) {
        getSubscription(subscriptionId);
        String realm = ociContext.realmOf(tenancies.homeRegionOrDefault(ociContext.tenancyId()));
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Regions.Region region : Regions.all()) {
            if (region.realm().equals(realm)) {
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("regionName", region.name());
                regions.add(wire);
            }
        }
        return regions;
    }

    // ── Assigned subscriptions (consumed) ──────────────────────────────────────

    /**
     * The subscriptions the caller consumes: those explicitly mapped to it and, for a child of an
     * organization with none mapped, the organization's default subscription.
     */
    public List<Map<String, Object>> listAssignedSubscriptions(String compartmentId, String subscriptionId,
                                                               String entityVersion, String sortBy, String sortOrder) {
        String caller = callerCompartment(compartmentId, true);
        requireEnum(entityVersion, ENTITY_VERSIONS, "entityVersion");
        requireSort(sortBy, sortOrder);
        if ("V1".equals(entityVersion)) {
            return List.of();
        }
        return assignedTo(caller).stream()
                .filter(id -> subscriptionId == null || subscriptionId.equals(id))
                .map(id -> subscriptionWire(id, caller, ownerOf(id, caller)))
                .sorted(assignedOrder(sortBy, sortOrder))
                .toList();
    }

    /**
     * timeCreated, the default, sorts newest first and displayName ascending, as the SDK says. An
     * assigned subscription has no display name, so that sort falls back on its OCID.
     */
    private static Comparator<Map<String, Object>> assignedOrder(String sortBy, String sortOrder) {
        boolean byName = "displayName".equals(sortBy);
        Comparator<Map<String, Object>> byId = Comparator.comparing(s -> (String) s.get("id"));
        Comparator<Map<String, Object>> order = byName ? byId
                : Comparator.<Map<String, Object>, Instant>comparing(s -> Instant.parse((String) s.get("timeCreated")))
                        .thenComparing(byId);
        boolean descending = sortOrder != null ? "DESC".equals(sortOrder) : !byName;
        return descending ? order.reversed() : order;
    }

    public Map<String, Object> getAssignedSubscription(String assignedSubscriptionId) {
        String caller = ociContext.tenancyId();
        if (!assignedTo(caller).contains(assignedSubscriptionId)) {
            throw notFound("assigned subscription", assignedSubscriptionId);
        }
        return subscriptionWire(assignedSubscriptionId, caller, ownerOf(assignedSubscriptionId, caller));
    }

    public List<Map<String, Object>> listAssignedSubscriptionLineItems(String assignedSubscriptionId,
                                                                      String sortBy, String sortOrder) {
        requireSort(sortBy, sortOrder);
        getAssignedSubscription(assignedSubscriptionId);
        String caller = ociContext.tenancyId();
        String owner = ownerOf(assignedSubscriptionId, caller);
        if (!assignedSubscriptionId.equals(ownSubscriptionId(owner))) {
            // A default subscription no tenancy here owns: nothing bills through it.
            return List.of();
        }
        return List.of(lineItem(assignedSubscriptionId, owner));
    }

    // ── Subscription mappings ──────────────────────────────────────────────────

    /**
     * Assigns the caller's own subscription to {@code compartmentId}: the caller itself or an
     * ACTIVE child of its organization.
     */
    public synchronized StoredSubscriptionMapping createSubscriptionMapping(String compartmentId,
                                                                            String subscriptionId) {
        requireNonBlank(compartmentId, "compartmentId");
        requireNonBlank(subscriptionId, "subscriptionId");
        String owner = ociContext.tenancyId();
        if (!subscriptionId.equals(ownSubscriptionId(owner))) {
            throw notFound("subscription", subscriptionId);
        }
        boolean ownChild = organizations.activeChildren(owner).stream()
                .map(StoredOrganizationTenancy::getTenancyId)
                .anyMatch(compartmentId::equals);
        if (!compartmentId.equals(owner) && !ownChild) {
            throw notFound("tenancy", compartmentId);
        }
        boolean mapped = activeMappings(owner).stream()
                .anyMatch(m -> subscriptionId.equals(m.getSubscriptionId())
                        && compartmentId.equals(m.getCompartmentId()));
        if (mapped) {
            throw OciException.conflict("Subscription " + subscriptionId + " is already assigned to " + compartmentId);
        }
        String now = Instant.now().toString();
        StoredSubscriptionMapping mapping = new StoredSubscriptionMapping();
        mapping.setId(Ocids.generateGlobal("subscriptionmapping", ociContext.realm()));
        mapping.setSubscriptionId(subscriptionId);
        mapping.setCompartmentId(compartmentId);
        mapping.setOwnerId(owner);
        mapping.setIsExplicitlyAssigned(true);
        mapping.setLifecycleState("ACTIVE");
        mapping.setTimeCreated(now);
        mapping.setTimeUpdated(now);
        mapping.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(mappings, owner, mapping.getId(), mapping);
        LOG.infov("createSubscriptionMapping {0} -> {1}", subscriptionId, compartmentId);
        return mapping;
    }

    public StoredSubscriptionMapping getSubscriptionMapping(String id) {
        return TenancyAwareStorageBackend.getIn(mappings, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("subscription mapping", id));
    }

    public List<StoredSubscriptionMapping> listSubscriptionMappings(String subscriptionId, String subscriptionMappingId,
                                                                    String compartmentId, String lifecycleState,
                                                                    String sortBy, String sortOrder) {
        requireNonBlank(subscriptionId, "subscriptionId");
        requireEnum(lifecycleState, MAPPING_STATES, "lifecycleState");
        requireSort(sortBy, sortOrder);
        Comparator<StoredSubscriptionMapping> order = Comparator.comparing(StoredSubscriptionMapping::getTimeCreated)
                .thenComparing(StoredSubscriptionMapping::getId);
        if (!"ASC".equals(sortOrder)) {
            order = order.reversed();
        }
        return TenancyAwareStorageBackend.scanTenancy(mappings, ociContext.tenancyId()).stream()
                .filter(m -> subscriptionId.equals(m.getSubscriptionId()))
                .filter(m -> subscriptionMappingId == null || subscriptionMappingId.equals(m.getId()))
                .filter(m -> compartmentId == null || compartmentId.equals(m.getCompartmentId()))
                .filter(m -> lifecycleState == null || lifecycleState.equals(m.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    /** Ends the assignment: the record stays readable as {@code DELETED} with {@code timeTerminated}. */
    public synchronized void deleteSubscriptionMapping(String id, String ifMatch) {
        StoredSubscriptionMapping mapping = getSubscriptionMapping(id);
        Etags.checkIfMatch(ifMatch, mapping.getEtag());
        if (!"ACTIVE".equals(mapping.getLifecycleState())) {
            throw OciException.conflict("The subscription mapping " + id + " is " + mapping.getLifecycleState()
                    + " and cannot be deleted");
        }
        end(mapping, Instant.now().toString());
        LOG.infov("deleteSubscriptionMapping {0}", id);
    }

    /** Ends the mappings to tenancies that are no longer ACTIVE children of {@code parentTenancyId}. */
    @Override
    public synchronized void membershipsChanged(String parentTenancyId) {
        Set<String> members = new HashSet<>();
        organizations.activeChildren(parentTenancyId).forEach(child -> members.add(child.getTenancyId()));
        String now = Instant.now().toString();
        for (StoredSubscriptionMapping mapping : activeMappings(parentTenancyId)) {
            if (!parentTenancyId.equals(mapping.getCompartmentId()) && !members.contains(mapping.getCompartmentId())) {
                end(mapping, now);
                LOG.infov("Ended subscription mapping {0}: {1} left the organization", mapping.getId(),
                        mapping.getCompartmentId());
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void end(StoredSubscriptionMapping mapping, String now) {
        mapping.setLifecycleState("DELETED");
        mapping.setTimeTerminated(now);
        mapping.setTimeUpdated(now);
        mapping.setEtag(Etags.newEtag());
        TenancyAwareStorageBackend.putIn(mappings, mapping.getOwnerId(), mapping.getId(), mapping);
    }

    /** The subscription ids {@code tenancyId} consumes, explicit mappings first. */
    private List<String> assignedTo(String tenancyId) {
        List<String> assigned = new ArrayList<>();
        String parent = organizations.parentOf(tenancyId).orElse(null);
        for (String owner : parent != null ? List.of(tenancyId, parent) : List.of(tenancyId)) {
            for (StoredSubscriptionMapping mapping : activeMappings(owner)) {
                if (tenancyId.equals(mapping.getCompartmentId()) && !assigned.contains(mapping.getSubscriptionId())) {
                    assigned.add(mapping.getSubscriptionId());
                }
            }
        }
        if (assigned.isEmpty()) {
            Optional<String> fallback = parent != null
                    ? organizations.defaultSubscriptionOf(parent)
                    : Optional.of(ownSubscriptionId(tenancyId));
            fallback.ifPresent(assigned::add);
        }
        return assigned;
    }

    private List<StoredSubscriptionMapping> activeMappings(String owner) {
        return TenancyAwareStorageBackend.scanTenancy(mappings, owner).stream()
                .filter(m -> "ACTIVE".equals(m.getLifecycleState()))
                .toList();
    }

    private String ownSubscriptionId(String tenancyId) {
        String homeRegion = tenancies.homeRegionOrDefault(tenancyId);
        return ownSubscriptionId(tenancyId, homeRegion, ociContext.realmOf(homeRegion));
    }

    /**
     * The tenancy that owns {@code subscriptionId}, as {@code consumer} sees it: the consumer or
     * its parent when it is one of theirs; otherwise the consumer (a default subscription that no
     * tenancy here owns).
     */
    private String ownerOf(String subscriptionId, String consumer) {
        Optional<String> parent = organizations.parentOf(consumer);
        if (parent.isPresent() && subscriptionId.equals(ownSubscriptionId(parent.get()))) {
            return parent.get();
        }
        return consumer;
    }

    /** When {@code tenancyId} was created, without registering it. */
    private Instant createdAt(String tenancyId) {
        return tenancies.find(tenancyId).map(StoredTenancy::getTimeCreated).map(Instant::parse).orElse(Instant.EPOCH);
    }

    /** A V2 cloud subscription, as both the subscription and the assigned-subscription shapes. */
    private Map<String, Object> subscriptionWire(String subscriptionId, String compartmentId, String owner) {
        String created = createdAt(owner).toString();
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("entityVersion", "V2");
        wire.put("id", subscriptionId);
        wire.put("compartmentId", compartmentId);
        wire.put("serviceName", SERVICE_NAME);
        wire.put("timeCreated", created);
        wire.put("timeUpdated", created);
        wire.put("freeformTags", Map.of());
        wire.put("definedTags", Map.of());
        wire.put("systemTags", Map.of());
        wire.put("subscriptionNumber", "SUB-" + digest(subscriptionId).substring(0, 10).toUpperCase(Locale.ROOT));
        wire.put("currencyCode", CURRENCY);
        wire.put("lifecycleState", "ACTIVE");
        return wire;
    }

    private Map<String, Object> lineItem(String subscriptionId, String owner) {
        Instant started = createdAt(owner).truncatedTo(ChronoUnit.DAYS);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", "LINE-" + digest(subscriptionId).substring(0, 12).toUpperCase(Locale.ROOT));
        wire.put("productCode", "CLOUD-CREDITS");
        wire.put("quantity", 1.0f);
        wire.put("billingModel", "PAYGO");
        wire.put("timeStarted", started.toString());
        wire.put("timeEnded", started.plus(3650, ChronoUnit.DAYS).toString());
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** {@code compartmentId} as the caller's tenancy; when optional, a missing one means the caller. */
    private String callerCompartment(String compartmentId, boolean mandatory) {
        String caller = ociContext.tenancyId();
        if (compartmentId == null) {
            if (mandatory) {
                throw OciException.missingParameter("Missing required parameter: compartmentId");
            }
            return caller;
        }
        if (!compartmentId.equals(caller)) {
            throw notFound("tenancy", compartmentId);
        }
        return caller;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireSort(String sortBy, String sortOrder) {
        requireEnum(sortBy, SORT_BY, "sortBy");
        requireEnum(sortOrder, OrganizationsService.SORT_ORDERS, "sortOrder");
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
