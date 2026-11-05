package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import io.floci.oci.services.organizations.model.StoredSubscriptionMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class SubscriptionsServiceTest {

    private static final String PARENT = "ocid1.tenancy.oc1..subparent";
    private static final String CHILD = "ocid1.tenancy.oc1..subchild";

    private final AtomicReference<String> caller = new AtomicReference<>(PARENT);
    private final List<StoredOrganizationTenancy> children = new ArrayList<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, String> defaults = new HashMap<>();
    private SubscriptionsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        lenient().when(context.realm()).thenReturn("oc1");
        lenient().when(context.realmOf("us-ashburn-1")).thenReturn("oc1");
        OrganizationsService organizations = mock(OrganizationsService.class);
        lenient().when(organizations.activeChildren(PARENT)).thenReturn(children);
        lenient().when(organizations.parentOf(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(parents.get(invocation.<String>getArgument(0))));
        lenient().when(organizations.defaultSubscriptionOf(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(defaults.get(invocation.<String>getArgument(0))));
        TenancyRegistry tenancies = new TenancyRegistry(partitioned(new InMemoryStorage<>()), config, context);
        service = new SubscriptionsService(partitioned(new InMemoryStorage<>()), organizations, tenancies, context);
    }

    private <V> TenancyAwareStorageBackend<V> partitioned(StorageBackend<String, V> raw) {
        return new TenancyAwareStorageBackend<>(raw, caller::get);
    }

    private String ownSubscription(String tenancy) {
        String previous = caller.getAndSet(tenancy);
        String id = (String) service.listSubscriptions(null, null, null, null, null).getFirst().get("id");
        caller.set(previous);
        return id;
    }

    private void joinChild() {
        StoredOrganizationTenancy membership = new StoredOrganizationTenancy();
        membership.setTenancyId(CHILD);
        membership.setRole("CHILD");
        membership.setLifecycleState("ACTIVE");
        children.add(membership);
        parents.put(CHILD, PARENT);
        defaults.put(PARENT, ownSubscription(PARENT));
    }

    @Test
    void everyTenancyOwnsOneStableV2Subscription() {
        List<Map<String, Object>> owned = service.listSubscriptions(PARENT, null, null, null, null);

        assertEquals(1, owned.size());
        Map<String, Object> subscription = owned.getFirst();
        assertEquals("V2", subscription.get("entityVersion"));
        assertEquals(PARENT, subscription.get("compartmentId"));
        assertEquals("ACTIVE", subscription.get("lifecycleState"));
        assertNotNull(subscription.get("subscriptionNumber"));
        assertNotNull(subscription.get("currencyCode"));
        String id = (String) subscription.get("id");
        assertTrue(id.startsWith("ocid1.organizationssubscription.oc1.iad."));
        assertEquals(subscription, service.getSubscription(id));
        assertEquals(id, ownSubscription(PARENT), "derived, not random");
        assertNotEquals(id, ownSubscription(CHILD));
        assertTrue(service.listSubscriptions(null, null, "V1", null, null).isEmpty());
    }

    @Test
    void aChildConsumesItsOrganizationsDefaultUntilAMappingIsMade() {
        joinChild();
        String parentSubscription = ownSubscription(PARENT);
        caller.set(CHILD);

        assertEquals(List.of(parentSubscription), service.listAssignedSubscriptions(CHILD, null, null, null, null)
                .stream().map(s -> s.get("id")).toList());
        assertEquals(CHILD, service.getAssignedSubscription(parentSubscription).get("compartmentId"));
        assertEquals(404, assertThrows(OciException.class,
                () -> service.getSubscription(parentSubscription)).getHttpStatus(), "the child does not own it");
    }

    @Test
    void theOwnerMapsItsSubscriptionToAChild() {
        joinChild();
        String subscription = ownSubscription(PARENT);

        StoredSubscriptionMapping mapping = service.createSubscriptionMapping(CHILD, subscription);

        assertEquals(true, mapping.getIsExplicitlyAssigned());
        assertEquals("ACTIVE", mapping.getLifecycleState());
        assertEquals(List.of(mapping), service.listSubscriptionMappings(subscription, null, null, null, null, null));
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.createSubscriptionMapping(CHILD, subscription)).getCode());
        caller.set(CHILD);
        assertEquals(List.of(subscription), service.listAssignedSubscriptions(CHILD, null, null, null, null)
                .stream().map(s -> s.get("id")).toList());
    }

    @Test
    void onlyTheOwnerMapsAndOnlyToItselfOrAChild() {
        joinChild();
        String subscription = ownSubscription(PARENT);

        assertEquals(404, assertThrows(OciException.class,
                () -> service.createSubscriptionMapping("ocid1.tenancy.oc1..stranger", subscription)).getHttpStatus());
        caller.set(CHILD);
        assertEquals(404, assertThrows(OciException.class,
                () -> service.createSubscriptionMapping(CHILD, subscription)).getHttpStatus());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.createSubscriptionMapping(CHILD, null)).getCode());
    }

    @Test
    void deletingAMappingKeepsATerminatedRecord() {
        joinChild();
        StoredSubscriptionMapping mapping = service.createSubscriptionMapping(CHILD, ownSubscription(PARENT));

        service.deleteSubscriptionMapping(mapping.getId(), null);

        StoredSubscriptionMapping deleted = service.getSubscriptionMapping(mapping.getId());
        assertEquals("DELETED", deleted.getLifecycleState());
        assertNotNull(deleted.getTimeTerminated());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.deleteSubscriptionMapping(mapping.getId(), null)).getCode());
    }

    @Test
    void lineItemsAndRegionsBelongToAnOwnedSubscription() {
        String subscription = ownSubscription(PARENT);

        Map<String, Object> line = service.listSubscriptionLineItems(subscription, null, null).getFirst();
        assertEquals("PAYGO", line.get("billingModel"));
        assertNotNull(line.get("timeStarted"));
        assertNotNull(line.get("timeEnded"));
        assertTrue(service.listAvailableRegions(subscription).stream()
                .anyMatch(r -> "us-ashburn-1".equals(r.get("regionName"))));
        assertEquals(404, assertThrows(OciException.class,
                () -> service.listAvailableRegions("ocid1.organizationssubscription.oc1.iad.nope")).getHttpStatus());
    }

    @Test
    void listsValidateTheirEnums() {
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listSubscriptions(null, null, "V3", null, null)).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.listAssignedSubscriptions(null, null, null, null, null)).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.listSubscriptionMappings(null, null, null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listSubscriptionMappings("s", null, null, "TERMINATED", null, null)).getCode());
    }

    @Test
    void mappingsEndWhenTheChildLeavesTheOrganization() {
        joinChild();
        StoredSubscriptionMapping toChild = service.createSubscriptionMapping(CHILD, ownSubscription(PARENT));
        StoredSubscriptionMapping toSelf = service.createSubscriptionMapping(PARENT, ownSubscription(PARENT));

        children.clear();
        service.membershipsChanged(PARENT);

        StoredSubscriptionMapping ended = service.getSubscriptionMapping(toChild.getId());
        assertEquals("DELETED", ended.getLifecycleState());
        assertNotNull(ended.getTimeTerminated());
        assertEquals("ACTIVE", service.getSubscriptionMapping(toSelf.getId()).getLifecycleState());
    }

    @Test
    void aChildSeesTheParentsDatesOnTheSubscriptionItConsumes() {
        joinChild();
        String parentSubscription = ownSubscription(PARENT);
        Object parentCreated = service.getSubscription(parentSubscription).get("timeCreated");
        Object parentLineStart = service.listSubscriptionLineItems(parentSubscription, null, null)
                .getFirst().get("timeStarted");
        caller.set(CHILD);

        assertEquals(parentCreated, service.getAssignedSubscription(parentSubscription).get("timeCreated"));
        assertEquals(parentLineStart, service.listAssignedSubscriptionLineItems(parentSubscription, null, null)
                .getFirst().get("timeStarted"));
    }
}
