package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.StoredTenancy;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.organizations.model.StoredLink;
import io.floci.oci.services.organizations.model.StoredOrganization;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class OrganizationsServiceTest {

    private static final String PARENT = "ocid1.tenancy.oc1..orgparent";

    private final AtomicReference<String> caller = new AtomicReference<>(PARENT);
    private TenancyRegistry tenancies;
    private WorkRequestService workRequests;
    private OrganizationsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        lenient().when(context.realmOf("us-ashburn-1")).thenReturn("oc1");
        lenient().when(context.realmOf("us-phoenix-1")).thenReturn("oc1");
        IdentityService identity = mock(IdentityService.class);
        lenient().when(identity.isSubscribed("us-ashburn-1")).thenReturn(true);
        lenient().when(identity.isSubscribed("us-phoenix-1")).thenReturn(true);

        tenancies = new TenancyRegistry(partitioned(new InMemoryStorage<>()), config, context);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new OrganizationsService(partitioned(new InMemoryStorage<>()),
                partitioned(new InMemoryStorage<>()), partitioned(new InMemoryStorage<>()),
                tenancies, identity, workRequests, context);
    }

    private <V> TenancyAwareStorageBackend<V> partitioned(StorageBackend<String, V> raw) {
        return new TenancyAwareStorageBackend<>(raw, caller::get);
    }

    private String createChild(String name, String homeRegion, List<String> features) {
        String workRequestId = service.createChildTenancy(PARENT, name, homeRegion, "admin@example.com",
                null, null, null, features);
        return workRequests.get(workRequestId).getResources().getFirst().getIdentifier();
    }

    @Test
    void createChildTenancyRegistersTheChildWithItsHomeRegion() {
        String child = createChild("child-one", "PHX", null);

        StoredTenancy registered = tenancies.find(child).orElseThrow();
        assertEquals("us-phoenix-1", registered.getHomeRegion());
        assertEquals(PARENT, registered.getParentTenancyId());
        assertEquals("child-one", registered.getName());
    }

    @Test
    void createChildTenancyRecordsASucceededWorkRequest() {
        String workRequestId = service.createChildTenancy(PARENT, "child", "us-phoenix-1",
                "admin@example.com", null, null, null, null);

        StoredWorkRequest wr = workRequests.get(workRequestId);
        assertEquals("CREATE_CHILD_TENANCY", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals(PARENT, wr.getCompartmentId());
    }

    @Test
    void theFirstChildCreatesTheOrganizationWithParentAndChildMembers() {
        String child = createChild("child", "us-ashburn-1", null);
        createChild("second", "us-ashburn-1", null);

        List<StoredOrganization> organizations = service.listOrganizations(PARENT);
        assertEquals(1, organizations.size());
        StoredOrganization organization = organizations.getFirst();
        assertEquals(PARENT, organization.getCompartmentId());
        assertEquals("ACTIVE", organization.getLifecycleState());

        List<StoredOrganizationTenancy> members = service.listOrganizationTenancies(organization.getId());
        assertEquals(3, members.size());
        assertEquals("PARENT", service.getOrganizationTenancy(organization.getId(), PARENT).getRole());
        assertEquals("CHILD", service.getOrganizationTenancy(organization.getId(), child).getRole());
    }

    @Test
    void aCoreLinkIsAlwaysCreatedAlongsideRequestedFeatures() {
        String child = createChild("child", "us-ashburn-1", List.of("CORE", "SUBSCRIPTION_SHARING"));

        List<StoredLink> links = service.listLinks(null, child, null, null, null);
        assertEquals(Set.of("CORE", "SUBSCRIPTION_SHARING"),
                links.stream().map(StoredLink::getFeature).collect(Collectors.toSet()));
        assertEquals(2, links.size(), "CORE is not duplicated when requested explicitly");
        assertEquals(1, service.listLinks(null, null, "CORE", null, null).size());
    }

    @Test
    void aChildSeesItsParentsOrganization() {
        String child = createChild("child", "us-ashburn-1", null);
        String organizationId = service.listOrganizations(PARENT).getFirst().getId();

        caller.set(child);

        assertEquals(organizationId, service.listOrganizations(child).getFirst().getId());
        assertEquals(organizationId, service.getOrganization(organizationId).getId());
        assertEquals(1, service.listLinks(PARENT, child, null, null, null).size());
    }

    @Test
    void aChildCannotUpdateItsParentsOrganization() {
        String child = createChild("child", "us-ashburn-1", null);
        String organizationId = service.listOrganizations(PARENT).getFirst().getId();
        caller.set(child);

        OciException e = assertThrows(OciException.class,
                () -> service.updateOrganization(organizationId, "ocid1.organizationssubscription.oc1.iad.x", null));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void aChildCannotCreateAChildTenancy() {
        String child = createChild("child", "us-ashburn-1", null);
        String organizationId = service.listOrganizations(PARENT).getFirst().getId();
        caller.set(child);

        OciException e = assertThrows(OciException.class, () -> service.createChildTenancy(
                child, "grandchild", "us-ashburn-1", "a@b.c", null, null, null, null));

        assertEquals(404, e.getHttpStatus());
        assertEquals("NotAuthorizedOrNotFound", e.getCode());
        assertEquals(2, service.listOrganizationTenancies(organizationId).size());
    }

    @Test
    void updateOrganizationChecksTheEtagAndSetsTheSubscription() {
        createChild("child", "us-ashburn-1", null);
        StoredOrganization organization = service.listOrganizations(PARENT).getFirst();

        OciException stale = assertThrows(OciException.class,
                () -> service.updateOrganization(organization.getId(), "ocid1.sub", "stale"));
        assertEquals(412, stale.getHttpStatus());

        service.updateOrganization(organization.getId(), "ocid1.sub", organization.getEtag());
        assertEquals("ocid1.sub", service.getOrganization(organization.getId()).getDefaultUcmSubscriptionId());
    }

    @Test
    void anUnsubscribedHomeRegionIsRejected() {
        OciException e = assertThrows(OciException.class,
                () -> createChild("child", "eu-frankfurt-1", null));
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void aForeignParentIsNotAuthorized() {
        OciException e = assertThrows(OciException.class, () -> service.createChildTenancy(
                "ocid1.tenancy.oc1..someoneelse", "child", "us-ashburn-1", "a@b.c", null, null, null, null));
        assertEquals(404, e.getHttpStatus());
        assertEquals("NotAuthorizedOrNotFound", e.getCode());
    }

    @Test
    void requiredFieldsAndEnumsAreValidated() {
        OciException missing = assertThrows(OciException.class,
                () -> service.createChildTenancy(PARENT, "child", "us-ashburn-1", null, null, null, null, null));
        assertEquals("MissingParameter", missing.getCode());

        OciException status = assertThrows(OciException.class, () -> service.createChildTenancy(
                PARENT, "child", "us-ashburn-1", "a@b.c", null, "MAYBE", null, null));
        assertEquals("InvalidParameter", status.getCode());

        OciException policy = assertThrows(OciException.class, () -> service.createChildTenancy(
                PARENT, "child", "us-ashburn-1", "a@b.c", "admins-1", null, null, null));
        assertEquals("InvalidParameter", policy.getCode());
    }

    @Test
    void aTenancyOutsideAnyOrganizationListsNone() {
        assertTrue(service.listOrganizations(PARENT).isEmpty());
    }

    @Test
    void organizationsAreInvisibleToUnrelatedTenancies() {
        createChild("child", "us-ashburn-1", null);
        String organizationId = service.listOrganizations(PARENT).getFirst().getId();
        caller.set("ocid1.tenancy.oc1..unrelated");

        assertTrue(service.listOrganizations("ocid1.tenancy.oc1..unrelated").isEmpty());
        OciException e = assertThrows(OciException.class, () -> service.getOrganization(organizationId));
        assertEquals(404, e.getHttpStatus());
    }
}
