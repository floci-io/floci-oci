package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import io.floci.oci.services.identity.model.StoredUser;
import io.floci.oci.services.identity.model.StoredUserGroupMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class IdentityServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";

    private static final String OTHER_TENANCY = "ocid1.tenancy.oc1..othertenancy";

    private EmulatorConfig config;
    private IdentityService service;
    private WorkRequestService workRequests;
    private StorageBackend<String, StoredWorkRequest> workRequestStore;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        workRequestStore = new InMemoryStorage<>();
        workRequests = new WorkRequestService(workRequestStore, config);
        service = new IdentityService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                config, workRequests);
    }

    @Test
    void createAndGetCompartment() {
        StoredCompartment c = service.createCompartment(null, "dev", "Development", null, null);
        assertTrue(c.getId().startsWith("ocid1.compartment.oc1.."));
        assertEquals(TENANCY, c.getCompartmentId());
        assertEquals("ACTIVE", c.getLifecycleState());
        assertNotNull(c.getEtag());
        assertEquals("dev", service.getCompartment(c.getId()).getName());
    }

    @Test
    void duplicateCompartmentNameIsConflict() {
        service.createCompartment(null, "dev", "Development", null, null);
        OciException e = assertThrows(OciException.class,
                () -> service.createCompartment(null, "dev", "Again", null, null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void tenancyIdResolvesToRootCompartment() {
        StoredCompartment root = service.getCompartment(TENANCY);
        assertEquals(TENANCY, root.getId());
        assertEquals("root", root.getName());
    }

    @Test
    void listCompartmentsSubtree() {
        StoredCompartment parent = service.createCompartment(null, "parent", "p", null, null);
        StoredCompartment child = service.createCompartment(parent.getId(), "child", "c", null, null);

        List<StoredCompartment> direct = service.listCompartments(null, false);
        assertEquals(1, direct.size());

        List<StoredCompartment> subtree = service.listCompartments(null, true);
        assertEquals(2, subtree.size());

        List<StoredCompartment> childSubtree = service.listCompartments(parent.getId(), true);
        assertEquals(1, childSubtree.size());
        assertEquals(child.getId(), childSubtree.get(0).getId());
    }

    @Test
    void deleteCompartmentRecordsSucceededWorkRequest() {
        StoredCompartment c = service.createCompartment(null, "gone", "d", null, null);
        String workRequestId = service.deleteCompartment(c.getId(), null);
        assertEquals("DELETED", service.getCompartment(c.getId()).getLifecycleState());
        StoredWorkRequest wr = workRequests.get(workRequestId);
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals("DELETE_COMPARTMENT", wr.getOperationType());
    }

    @Test
    void deleteCompartmentWithActiveChildIsConflict() {
        StoredCompartment parent = service.createCompartment(null, "parent", "p", null, null);
        service.createCompartment(parent.getId(), "child", "c", null, null);
        assertThrows(OciException.class, () -> service.deleteCompartment(parent.getId(), null));
    }

    @Test
    void userLifecycle() {
        StoredUser u = service.createUser("alice", "Alice", "alice@example.com", null, null);
        assertTrue(u.getId().startsWith("ocid1.user.oc1.."));
        assertEquals(Boolean.FALSE, u.getIsMfaActivated());

        StoredUser updated = service.updateUser(u.getId(), "Alice Updated", null, null, null, null);
        assertEquals("Alice Updated", updated.getDescription());

        service.deleteUser(u.getId(), null);
        assertThrows(OciException.class, () -> service.getUser(u.getId()));
    }

    @Test
    void staleIfMatchOnUpdateIs412() {
        StoredUser u = service.createUser("bob", "Bob", null, null, null);
        OciException e = assertThrows(OciException.class,
                () -> service.updateUser(u.getId(), "x", null, null, null, "wrong-etag"));
        assertEquals(412, e.getHttpStatus());
    }

    @Test
    void membershipLifecycleAndCleanupOnUserDelete() {
        StoredUser u = service.createUser("carol", "Carol", null, null, null);
        StoredGroup g = service.createGroup("admins", "Admins", null, null);
        StoredUserGroupMembership m = service.addUserToGroup(u.getId(), g.getId());
        assertEquals(1, service.listMemberships(u.getId(), null).size());
        assertEquals(1, service.listMemberships(null, g.getId()).size());

        assertThrows(OciException.class, () -> service.addUserToGroup(u.getId(), g.getId()));

        service.deleteUser(u.getId(), null);
        assertThrows(OciException.class, () -> service.getMembership(m.getId()));
    }

    @Test
    void policyRequiresStatements() {
        OciException e = assertThrows(OciException.class,
                () -> service.createPolicy(null, "p", "d", List.of(), null, null, null));
        assertEquals(400, e.getHttpStatus());

        StoredPolicy p = service.createPolicy(null, "p", "d",
                List.of("Allow group admins to manage all-resources in tenancy"), null, null, null);
        assertEquals(1, service.listPolicies(null).size());
        service.deletePolicy(p.getId(), null);
        assertTrue(service.listPolicies(null).isEmpty());
    }

    @Test
    void referenceDataShapes() {
        assertEquals(3, service.availabilityDomains(null).size());
        assertEquals("IAD", service.regionKey());
        assertEquals("us-ashburn-1", service.regions().get(0).get("name"));
        assertEquals(Boolean.TRUE, service.regionSubscriptions().get(0).get("isHomeRegion"));
        assertEquals(TENANCY, service.tenancy(TENANCY).get("id"));
        assertThrows(OciException.class, () -> service.tenancy("ocid1.tenancy.oc1..other"));
    }

    @Test
    void notFoundUsesNotAuthorizedOrNotFoundCode() {
        OciException e = assertThrows(OciException.class,
                () -> service.getUser("ocid1.user.oc1..missing"));
        assertEquals("NotAuthorizedOrNotFound", e.getCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void missingRequiredFieldIs400() {
        OciException e = assertThrows(OciException.class,
                () -> service.createUser(null, "desc", null, null, null));
        assertEquals("MissingParameter", e.getCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createWithoutTags() {
        StoredCompartment c = service.createCompartment(null, "tagless", "d",
                Map.of("team", "dev"), null);
        assertEquals("dev", c.getFreeformTags().get("team"));
    }

    @Test
    void regionKeyUsesTheSdkRegionCode() {
        lenient().when(config.defaultRegion()).thenReturn("sa-saopaulo-1");
        assertEquals("GRU", service.regionKey());
        assertEquals("GRU", service.tenancy(TENANCY).get("homeRegionKey"));
    }
  
    @Test
    void requestTenancyScopesRootDefaultsAndTenancyLookup() {
        IdentityService scoped = new IdentityService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), config, workRequests, () -> OTHER_TENANCY);

        assertEquals(OTHER_TENANCY, scoped.getCompartment(OTHER_TENANCY).getId());
        assertEquals(OTHER_TENANCY,
                scoped.createCompartment(null, "dev", "d", null, null).getCompartmentId());
        assertEquals(OTHER_TENANCY,
                scoped.createUser("alice", "d", null, null, null).getCompartmentId());
        assertEquals(OTHER_TENANCY, scoped.tenancy(OTHER_TENANCY).get("id"));
        assertThrows(OciException.class, () -> scoped.tenancy(TENANCY));
    }

    @Test
    void legacyDefaultTenancyRecordsAreAdoptedBySignedTenancy() {
        TenancyAwareStorageBackend<StoredCompartment> compartments = tenancyAware(OTHER_TENANCY);
        TenancyAwareStorageBackend<StoredUser> users = tenancyAware(OTHER_TENANCY);
        TenancyAwareStorageBackend<StoredPolicy> policies = tenancyAware(OTHER_TENANCY);

        StoredCompartment legacy = new StoredCompartment();
        legacy.setId("ocid1.compartment.oc1..legacy");
        legacy.setCompartmentId(TENANCY);
        legacy.setName("legacy");
        legacy.setTimeCreated("2026-01-01T00:00:00Z");
        compartments.putForTenancy(OTHER_TENANCY, legacy.getId(), legacy);
        StoredCompartment own = new StoredCompartment();
        own.setId("ocid1.compartment.oc1..own");
        own.setCompartmentId(TENANCY);
        own.setName("own");
        own.setTimeCreated("2026-01-01T00:00:00Z");
        compartments.putForTenancy(TENANCY, own.getId(), own);
        StoredUser user = new StoredUser();
        user.setId("ocid1.user.oc1..legacy");
        user.setCompartmentId(TENANCY);
        users.putForTenancy(OTHER_TENANCY, user.getId(), user);
        StoredPolicy policy = new StoredPolicy();
        policy.setId("ocid1.policy.oc1..legacy");
        policy.setCompartmentId(TENANCY);
        policy.setTimeCreated("2026-01-01T00:00:00Z");
        policies.putForTenancy(OTHER_TENANCY, policy.getId(), policy);

        IdentityService scoped = new IdentityService(compartments, users,
                tenancyAware(OTHER_TENANCY), tenancyAware(OTHER_TENANCY), policies, config,
                workRequests, () -> OTHER_TENANCY);
        scoped.adoptLegacyRootRecords();
        scoped.adoptLegacyRootRecords();

        assertEquals(List.of(legacy.getId()), scoped.listCompartments(null, false).stream()
                .map(StoredCompartment::getId).toList());
        assertEquals(List.of(legacy.getId()), scoped.listCompartments(null, true).stream()
                .map(StoredCompartment::getId).toList());
        assertEquals(OTHER_TENANCY, scoped.getUser(user.getId()).getCompartmentId());
        assertEquals(List.of(policy.getId()), scoped.listPolicies(null).stream()
                .map(StoredPolicy::getId).toList());
        assertEquals(TENANCY, compartments.getForTenancy(TENANCY, own.getId())
                .orElseThrow().getCompartmentId());
    }

    // Reads without a request context fall back to readsAs, standing in for the signed tenancy.
    private static <V> TenancyAwareStorageBackend<V> tenancyAware(String readsAs) {
        return new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), null, readsAs);
    }
}
