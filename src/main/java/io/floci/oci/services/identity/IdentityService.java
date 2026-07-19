package io.floci.oci.services.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import io.floci.oci.services.identity.model.StoredUser;
import io.floci.oci.services.identity.model.StoredUserGroupMembership;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IdentityService {

    private static final Logger LOG = Logger.getLogger(IdentityService.class);

    private final StorageBackend<String, StoredCompartment> compartments;
    private final StorageBackend<String, StoredUser> users;
    private final StorageBackend<String, StoredGroup> groups;
    private final StorageBackend<String, StoredUserGroupMembership> memberships;
    private final StorageBackend<String, StoredPolicy> policies;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;

    @Inject
    public IdentityService(StorageFactory storageFactory, EmulatorConfig config,
                           ServiceRegistry serviceRegistry, WorkRequestService workRequests) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.compartments = storageFactory.create("identity", "identity-compartments.json",
                new TypeReference<Map<String, StoredCompartment>>() {});
        this.users = storageFactory.create("identity", "identity-users.json",
                new TypeReference<Map<String, StoredUser>>() {});
        this.groups = storageFactory.create("identity", "identity-groups.json",
                new TypeReference<Map<String, StoredGroup>>() {});
        this.memberships = storageFactory.create("identity", "identity-memberships.json",
                new TypeReference<Map<String, StoredUserGroupMembership>>() {});
        this.policies = storageFactory.create("identity", "identity-policies.json",
                new TypeReference<Map<String, StoredPolicy>>() {});
    }

    IdentityService(StorageBackend<String, StoredCompartment> compartments,
                    StorageBackend<String, StoredUser> users,
                    StorageBackend<String, StoredGroup> groups,
                    StorageBackend<String, StoredUserGroupMembership> memberships,
                    StorageBackend<String, StoredPolicy> policies,
                    EmulatorConfig config,
                    WorkRequestService workRequests) {
        this.compartments = compartments;
        this.users = users;
        this.groups = groups;
        this.memberships = memberships;
        this.policies = policies;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("identity")
                .enabled(config.services().identity().enabled())
                .storageKey("identity")
                .resourceClasses(IdentityController.class)
                .build());
    }

    // ── Compartments ───────────────────────────────────────────────────────────

    public StoredCompartment createCompartment(String parentId, String name, String description,
                                               Map<String, String> freeformTags,
                                               Map<String, Map<String, Object>> definedTags) {
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        String parent = parentId != null ? parentId : tenancyId();
        boolean duplicate = compartments.scan(k -> true).stream()
                .anyMatch(c -> parent.equals(c.getCompartmentId()) && name.equals(c.getName())
                        && "ACTIVE".equals(c.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("Compartment " + name + " already exists in " + parent);
        }
        StoredCompartment c = new StoredCompartment();
        c.setId(Ocids.generateGlobal("compartment", config.defaultRealm()));
        c.setCompartmentId(parent);
        c.setName(name);
        c.setDescription(description);
        c.setTimeCreated(Instant.now().toString());
        c.setLifecycleState("ACTIVE");
        c.setIsAccessible(true);
        c.setFreeformTags(freeformTags);
        c.setDefinedTags(definedTags);
        c.setEtag(Etags.newEtag());
        compartments.put(c.getId(), c);
        LOG.infof("createCompartment %s (%s)", name, c.getId());
        return c;
    }

    public StoredCompartment getCompartment(String compartmentId) {
        if (compartmentId.equals(tenancyId())) {
            return rootCompartment();
        }
        return compartments.get(compartmentId)
                .orElseThrow(() -> notFound("compartment", compartmentId));
    }

    public List<StoredCompartment> listCompartments(String parentId, boolean subtree) {
        String parent = parentId != null ? parentId : tenancyId();
        List<StoredCompartment> all = compartments.scan(k -> true).stream()
                .sorted(Comparator.comparing(StoredCompartment::getTimeCreated))
                .toList();
        if (!subtree) {
            return all.stream().filter(c -> parent.equals(c.getCompartmentId())).toList();
        }
        return all.stream().filter(c -> isInSubtree(c, parent)).toList();
    }

    public StoredCompartment updateCompartment(String compartmentId, String name, String description,
                                               Map<String, String> freeformTags,
                                               Map<String, Map<String, Object>> definedTags,
                                               String ifMatch) {
        StoredCompartment c = getCompartment(compartmentId);
        Etags.checkIfMatch(ifMatch, c.getEtag());
        if (name != null) {
            c.setName(name);
        }
        if (description != null) {
            c.setDescription(description);
        }
        if (freeformTags != null) {
            c.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            c.setDefinedTags(definedTags);
        }
        c.setEtag(Etags.newEtag());
        compartments.put(c.getId(), c);
        return c;
    }

    /** Deletion is async on real OCI: returns the work-request OCID for the 202 response. */
    public String deleteCompartment(String compartmentId, String ifMatch) {
        StoredCompartment c = compartments.get(compartmentId)
                .orElseThrow(() -> notFound("compartment", compartmentId));
        Etags.checkIfMatch(ifMatch, c.getEtag());
        boolean hasChildren = compartments.scan(k -> true).stream()
                .anyMatch(child -> compartmentId.equals(child.getCompartmentId())
                        && "ACTIVE".equals(child.getLifecycleState()));
        if (hasChildren) {
            throw OciException.conflict(
                    "Compartment " + compartmentId + " has active child compartments.");
        }
        c.setLifecycleState("DELETED");
        c.setEtag(Etags.newEtag());
        compartments.put(c.getId(), c);
        return workRequests.succeeded("DELETE_COMPARTMENT", c.getCompartmentId(),
                List.of(WorkRequestService.resource("COMPARTMENT", "DELETED", compartmentId,
                        "/20160918/compartments/" + compartmentId)));
    }

    // ── Users ──────────────────────────────────────────────────────────────────

    public StoredUser createUser(String name, String description, String email,
                                 Map<String, String> freeformTags,
                                 Map<String, Map<String, Object>> definedTags) {
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        boolean duplicate = users.scan(k -> true).stream()
                .anyMatch(u -> name.equals(u.getName()) && "ACTIVE".equals(u.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("User " + name + " already exists.");
        }
        StoredUser u = new StoredUser();
        u.setId(Ocids.generateGlobal("user", config.defaultRealm()));
        u.setCompartmentId(tenancyId());
        u.setName(name);
        u.setDescription(description);
        u.setEmail(email);
        u.setEmailVerified(false);
        u.setTimeCreated(Instant.now().toString());
        u.setLifecycleState("ACTIVE");
        u.setIsMfaActivated(false);
        u.setFreeformTags(freeformTags);
        u.setDefinedTags(definedTags);
        u.setEtag(Etags.newEtag());
        users.put(u.getId(), u);
        LOG.infof("createUser %s (%s)", name, u.getId());
        return u;
    }

    public StoredUser getUser(String userId) {
        return users.get(userId).orElseThrow(() -> notFound("user", userId));
    }

    public List<StoredUser> listUsers() {
        return users.scan(k -> true).stream()
                .sorted(Comparator.comparing(StoredUser::getTimeCreated))
                .toList();
    }

    public StoredUser updateUser(String userId, String description, String email,
                                 Map<String, String> freeformTags,
                                 Map<String, Map<String, Object>> definedTags,
                                 String ifMatch) {
        StoredUser u = getUser(userId);
        Etags.checkIfMatch(ifMatch, u.getEtag());
        if (description != null) {
            u.setDescription(description);
        }
        if (email != null) {
            u.setEmail(email);
        }
        if (freeformTags != null) {
            u.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            u.setDefinedTags(definedTags);
        }
        u.setEtag(Etags.newEtag());
        users.put(u.getId(), u);
        return u;
    }

    public void deleteUser(String userId, String ifMatch) {
        StoredUser u = getUser(userId);
        Etags.checkIfMatch(ifMatch, u.getEtag());
        memberships.scan(k -> true).stream()
                .filter(m -> userId.equals(m.getUserId()))
                .forEach(m -> memberships.delete(m.getId()));
        users.delete(userId);
    }

    // ── Groups ─────────────────────────────────────────────────────────────────

    public StoredGroup createGroup(String name, String description,
                                   Map<String, String> freeformTags,
                                   Map<String, Map<String, Object>> definedTags) {
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        boolean duplicate = groups.scan(k -> true).stream()
                .anyMatch(g -> name.equals(g.getName()) && "ACTIVE".equals(g.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("Group " + name + " already exists.");
        }
        StoredGroup g = new StoredGroup();
        g.setId(Ocids.generateGlobal("group", config.defaultRealm()));
        g.setCompartmentId(tenancyId());
        g.setName(name);
        g.setDescription(description);
        g.setTimeCreated(Instant.now().toString());
        g.setLifecycleState("ACTIVE");
        g.setFreeformTags(freeformTags);
        g.setDefinedTags(definedTags);
        g.setEtag(Etags.newEtag());
        groups.put(g.getId(), g);
        return g;
    }

    public StoredGroup getGroup(String groupId) {
        return groups.get(groupId).orElseThrow(() -> notFound("group", groupId));
    }

    public List<StoredGroup> listGroups() {
        return groups.scan(k -> true).stream()
                .sorted(Comparator.comparing(StoredGroup::getTimeCreated))
                .toList();
    }

    public StoredGroup updateGroup(String groupId, String description,
                                   Map<String, String> freeformTags,
                                   Map<String, Map<String, Object>> definedTags,
                                   String ifMatch) {
        StoredGroup g = getGroup(groupId);
        Etags.checkIfMatch(ifMatch, g.getEtag());
        if (description != null) {
            g.setDescription(description);
        }
        if (freeformTags != null) {
            g.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            g.setDefinedTags(definedTags);
        }
        g.setEtag(Etags.newEtag());
        groups.put(g.getId(), g);
        return g;
    }

    public void deleteGroup(String groupId, String ifMatch) {
        StoredGroup g = getGroup(groupId);
        Etags.checkIfMatch(ifMatch, g.getEtag());
        memberships.scan(k -> true).stream()
                .filter(m -> groupId.equals(m.getGroupId()))
                .forEach(m -> memberships.delete(m.getId()));
        groups.delete(groupId);
    }

    // ── User group memberships ─────────────────────────────────────────────────

    public StoredUserGroupMembership addUserToGroup(String userId, String groupId) {
        getUser(userId);
        getGroup(groupId);
        boolean duplicate = memberships.scan(k -> true).stream()
                .anyMatch(m -> userId.equals(m.getUserId()) && groupId.equals(m.getGroupId()));
        if (duplicate) {
            throw OciException.conflict("User " + userId + " is already in group " + groupId);
        }
        StoredUserGroupMembership m = new StoredUserGroupMembership();
        m.setId(Ocids.generateGlobal("groupmembership", config.defaultRealm()));
        m.setCompartmentId(tenancyId());
        m.setUserId(userId);
        m.setGroupId(groupId);
        m.setTimeCreated(Instant.now().toString());
        m.setLifecycleState("ACTIVE");
        m.setEtag(Etags.newEtag());
        memberships.put(m.getId(), m);
        return m;
    }

    public StoredUserGroupMembership getMembership(String membershipId) {
        return memberships.get(membershipId)
                .orElseThrow(() -> notFound("userGroupMembership", membershipId));
    }

    public List<StoredUserGroupMembership> listMemberships(String userId, String groupId) {
        return memberships.scan(k -> true).stream()
                .filter(m -> userId == null || userId.equals(m.getUserId()))
                .filter(m -> groupId == null || groupId.equals(m.getGroupId()))
                .sorted(Comparator.comparing(StoredUserGroupMembership::getTimeCreated))
                .toList();
    }

    public void removeUserFromGroup(String membershipId) {
        getMembership(membershipId);
        memberships.delete(membershipId);
    }

    // ── Policies ───────────────────────────────────────────────────────────────

    public StoredPolicy createPolicy(String compartmentId, String name, String description,
                                     List<String> statements, String versionDate,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags) {
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        if (statements == null || statements.isEmpty()) {
            throw OciException.invalidParameter("statements must contain at least one statement");
        }
        String compartment = compartmentId != null ? compartmentId : tenancyId();
        boolean duplicate = policies.scan(k -> true).stream()
                .anyMatch(p -> name.equals(p.getName()) && "ACTIVE".equals(p.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("Policy " + name + " already exists.");
        }
        StoredPolicy p = new StoredPolicy();
        p.setId(Ocids.generateGlobal("policy", config.defaultRealm()));
        p.setCompartmentId(compartment);
        p.setName(name);
        p.setDescription(description);
        p.setStatements(List.copyOf(statements));
        p.setVersionDate(versionDate);
        p.setTimeCreated(Instant.now().toString());
        p.setLifecycleState("ACTIVE");
        p.setFreeformTags(freeformTags);
        p.setDefinedTags(definedTags);
        p.setEtag(Etags.newEtag());
        policies.put(p.getId(), p);
        return p;
    }

    public StoredPolicy getPolicy(String policyId) {
        return policies.get(policyId).orElseThrow(() -> notFound("policy", policyId));
    }

    public List<StoredPolicy> listPolicies(String compartmentId) {
        String compartment = compartmentId != null ? compartmentId : tenancyId();
        return policies.scan(k -> true).stream()
                .filter(p -> compartment.equals(p.getCompartmentId()))
                .sorted(Comparator.comparing(StoredPolicy::getTimeCreated))
                .toList();
    }

    public StoredPolicy updatePolicy(String policyId, String description, List<String> statements,
                                     String versionDate,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags,
                                     String ifMatch) {
        StoredPolicy p = getPolicy(policyId);
        Etags.checkIfMatch(ifMatch, p.getEtag());
        if (description != null) {
            p.setDescription(description);
        }
        if (statements != null) {
            p.setStatements(List.copyOf(statements));
        }
        if (versionDate != null) {
            p.setVersionDate(versionDate);
        }
        if (freeformTags != null) {
            p.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            p.setDefinedTags(definedTags);
        }
        p.setEtag(Etags.newEtag());
        policies.put(p.getId(), p);
        return p;
    }

    public void deletePolicy(String policyId, String ifMatch) {
        StoredPolicy p = getPolicy(policyId);
        Etags.checkIfMatch(ifMatch, p.getEtag());
        policies.delete(policyId);
    }

    // ── Read-only reference data ───────────────────────────────────────────────

    /** The root compartment is the tenancy itself. */
    public StoredCompartment rootCompartment() {
        StoredCompartment root = new StoredCompartment();
        root.setId(tenancyId());
        root.setCompartmentId(tenancyId());
        root.setName("root");
        root.setDescription("Root compartment (tenancy)");
        root.setTimeCreated(Instant.EPOCH.toString());
        root.setLifecycleState("ACTIVE");
        root.setIsAccessible(true);
        root.setEtag("root");
        return root;
    }

    public List<Map<String, Object>> availabilityDomains(String compartmentId) {
        String compartment = compartmentId != null ? compartmentId : tenancyId();
        String regionUpper = config.defaultRegion().toUpperCase().replace("-", "-");
        return List.of(
                ad(compartment, regionUpper, 1),
                ad(compartment, regionUpper, 2),
                ad(compartment, regionUpper, 3));
    }

    private Map<String, Object> ad(String compartmentId, String regionUpper, int index) {
        return Map.of(
                "name", "Floc:" + regionUpper + "-AD-" + index,
                "id", "ocid1.availabilitydomain." + config.defaultRealm() + ".." + "floci" + index,
                "compartmentId", compartmentId);
    }

    public List<Map<String, String>> regions() {
        return List.of(Map.of("key", regionKey(), "name", config.defaultRegion()));
    }

    public List<Map<String, Object>> regionSubscriptions() {
        return List.of(Map.of(
                "regionKey", regionKey(),
                "regionName", config.defaultRegion(),
                "status", "READY",
                "isHomeRegion", true));
    }

    public Map<String, Object> tenancy(String tenancyOcid) {
        if (!tenancyOcid.equals(tenancyId())) {
            throw notFound("tenancy", tenancyOcid);
        }
        return Map.of(
                "id", tenancyId(),
                "name", "floci-local",
                "description", "floci-oci emulated tenancy",
                "homeRegionKey", regionKey());
    }

    /** Region key: last segment initials, e.g. us-ashburn-1 → IAD is not derivable; use uppercase short form. */
    String regionKey() {
        // Common well-known mappings; fall back to the uppercased first three letters.
        return switch (config.defaultRegion()) {
            case "us-ashburn-1" -> "IAD";
            case "us-phoenix-1" -> "PHX";
            case "eu-frankfurt-1" -> "FRA";
            case "uk-london-1" -> "LHR";
            default -> config.defaultRegion().replaceAll("[^a-zA-Z]", "")
                    .toUpperCase().substring(0, 3);
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String tenancyId() {
        return config.defaultTenancyId();
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
    }

    private boolean isInSubtree(StoredCompartment c, String rootId) {
        String current = c.getCompartmentId();
        int depth = 0;
        while (current != null && depth++ < 50) {
            if (rootId.equals(current)) {
                return true;
            }
            if (current.equals(tenancyId())) {
                return false;
            }
            current = compartments.get(current)
                    .map(StoredCompartment::getCompartmentId)
                    .orElse(null);
        }
        return false;
    }

    private static OciException notFound(String kind, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + kind + " " + id);
    }
}
