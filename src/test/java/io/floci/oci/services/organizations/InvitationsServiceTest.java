package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.organizations.model.StoredLink;
import io.floci.oci.services.organizations.model.StoredRecipientInvitation;
import io.floci.oci.services.organizations.model.StoredSenderInvitation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class InvitationsServiceTest {

    private static final String SENDER = "ocid1.tenancy.oc1..invsender";
    private static final String RECIPIENT = "ocid1.tenancy.oc1..invrecipient";

    private final AtomicReference<String> caller = new AtomicReference<>(SENDER);
    private TenancyRegistry tenancies;
    private WorkRequestService workRequests;
    private OrganizationsService organizations;
    private InvitationsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        lenient().when(context.realmOf("us-ashburn-1")).thenReturn("oc1");
        IdentityService identity = mock(IdentityService.class);
        lenient().when(identity.isSubscribed("us-ashburn-1")).thenReturn(true);

        tenancies = new TenancyRegistry(partitioned(new InMemoryStorage<>()), config, context);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        organizations = new OrganizationsService(partitioned(new InMemoryStorage<>()),
                partitioned(new InMemoryStorage<>()), partitioned(new InMemoryStorage<>()),
                tenancies, identity, workRequests, context);
        service = new InvitationsService(partitioned(new InMemoryStorage<>()), partitioned(new InMemoryStorage<>()),
                organizations, tenancies, workRequests, context);
    }

    private <V> TenancyAwareStorageBackend<V> partitioned(StorageBackend<String, V> raw) {
        return new TenancyAwareStorageBackend<>(raw, caller::get);
    }

    private StoredSenderInvitation invite(List<String> features) {
        return service.createSenderInvitation(SENDER, RECIPIENT, "admin@example.com", null, null, features,
                null, null).invitation();
    }

    @Test
    void createSenderInvitationDeliversAPendingRecipientInvitation() {
        InvitationsService.Created created = service.createSenderInvitation(SENDER, RECIPIENT, null, "join us",
                List.of("LINK"), null, null, null);

        StoredSenderInvitation sender = created.invitation();
        assertEquals("PENDING", sender.getStatus());
        assertEquals("ACTIVE", sender.getLifecycleState());
        StoredWorkRequest wr = workRequests.get(created.workRequestId());
        assertEquals("CREATE_SENDER_INVITATION", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());

        caller.set(RECIPIENT);
        StoredRecipientInvitation recipient = service.getRecipientInvitation(sender.getRecipientInvitationId());
        assertNotEquals(sender.getId(), recipient.getId());
        assertEquals(sender.getId(), recipient.getSenderInvitationId());
        assertEquals(SENDER, recipient.getSenderTenancyId());
        assertEquals("PENDING", recipient.getStatus());
        assertEquals("join us", recipient.getDisplayName());
    }

    @Test
    void acceptingJoinsTheSendersOrganizationWithItsFeatureLinks() {
        StoredSenderInvitation sender = invite(List.of("SUBSCRIPTION_SHARING"));
        caller.set(RECIPIENT);

        String workRequestId = service.acceptRecipientInvitation(sender.getRecipientInvitationId(), null);

        assertEquals("ACCEPT_RECIPIENT_INVITATION", workRequests.get(workRequestId).getOperationType());
        assertEquals(SENDER, tenancies.find(RECIPIENT).orElseThrow().getParentTenancyId());
        String organizationId = organizations.listOrganizations(RECIPIENT).getFirst().getId();
        assertEquals("CHILD", organizations.getOrganizationTenancy(organizationId, RECIPIENT).getRole());
        assertEquals(Set.of("CORE", "SUBSCRIPTION_SHARING"), organizations.listLinks(SENDER, RECIPIENT, null, null, null)
                .stream().map(StoredLink::getFeature).collect(Collectors.toSet()));
        assertEquals("ACCEPTED", service.getRecipientInvitation(sender.getRecipientInvitationId()).getStatus());
        caller.set(SENDER);
        assertEquals("ACCEPTED", service.getSenderInvitation(sender.getId()).getStatus());
    }

    @Test
    void cancelingEndsBothSides() {
        StoredSenderInvitation sender = invite(null);

        String workRequestId = service.cancelSenderInvitation(sender.getId(), null);

        assertEquals("CANCEL_SENDER_INVITATION", workRequests.get(workRequestId).getOperationType());
        assertEquals("CANCELED", service.getSenderInvitation(sender.getId()).getStatus());
        caller.set(RECIPIENT);
        assertEquals("CANCELED", service.getRecipientInvitation(sender.getRecipientInvitationId()).getStatus());
        OciException e = assertThrows(OciException.class,
                () -> service.acceptRecipientInvitation(sender.getRecipientInvitationId(), null));
        assertEquals(409, e.getHttpStatus());
        assertEquals("Conflict", e.getCode());
    }

    @Test
    void ignoringLeavesTheSenderPendingUntilCanceled() {
        StoredSenderInvitation sender = invite(null);
        caller.set(RECIPIENT);

        StoredRecipientInvitation ignored = service.ignoreRecipientInvitation(sender.getRecipientInvitationId(), null);

        assertEquals("IGNORED", ignored.getStatus());
        assertTrue(tenancies.find(RECIPIENT).map(t -> t.getParentTenancyId() == null).orElse(true));
        caller.set(SENDER);
        assertEquals("PENDING", service.getSenderInvitation(sender.getId()).getStatus());
    }

    @Test
    void invitationsAreVisibleOnlyToTheirOwnSide() {
        StoredSenderInvitation sender = invite(null);

        assertEquals(404, assertThrows(OciException.class,
                () -> service.getRecipientInvitation(sender.getRecipientInvitationId())).getHttpStatus());
        caller.set(RECIPIENT);
        assertEquals(404, assertThrows(OciException.class,
                () -> service.getSenderInvitation(sender.getId())).getHttpStatus());
        assertEquals(404, assertThrows(OciException.class,
                () -> service.cancelSenderInvitation(sender.getId(), null)).getHttpStatus());
        assertEquals(1, service.listRecipientInvitations(RECIPIENT, null, null, null).size());
    }

    @Test
    void aTenancyAlreadyInAnOrganizationCannotBeInvited() {
        String workRequestId = organizations.createChildTenancy(SENDER, "child", "us-ashburn-1",
                "a@b.c", null, null, null, null);
        String child = workRequests.get(workRequestId).getResources().getFirst().getIdentifier();

        OciException e = assertThrows(OciException.class, () -> service.createSenderInvitation(
                SENDER, child, null, null, null, null, null, null));
        assertEquals(409, e.getHttpStatus());
        assertEquals("Conflict", e.getCode());

        caller.set(child);
        OciException fromChild = assertThrows(OciException.class, () -> service.createSenderInvitation(
                child, RECIPIENT, null, null, null, null, null, null));
        assertEquals(404, fromChild.getHttpStatus());
    }

    @Test
    void createValidatesItsInput() {
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createSenderInvitation(
                SENDER, null, null, null, null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createSenderInvitation(
                SENDER, SENDER, null, null, null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createSenderInvitation(
                SENDER, RECIPIENT, null, null, List.of("EVERYTHING"), null, null, null)).getCode());
        assertEquals(404, assertThrows(OciException.class, () -> service.createSenderInvitation(
                "ocid1.tenancy.oc1..someoneelse", RECIPIENT, null, null, null, null, null, null)).getHttpStatus());
        invite(null);
        assertEquals("Conflict", assertThrows(OciException.class, () -> invite(null)).getCode());
    }

    @Test
    void listSenderInvitationsFiltersSortsAndValidates() {
        service.createSenderInvitation(SENDER, RECIPIENT, null, "b-invite", null, null, null, null);
        service.createSenderInvitation(SENDER, "ocid1.tenancy.oc1..other", null, "a-invite", null, null, null, null);

        assertEquals(List.of("a-invite", "b-invite"), service.listSenderInvitations(SENDER, null, null, null, null,
                "displayName", null).stream().map(StoredSenderInvitation::getDisplayName).toList());
        assertEquals(1, service.listSenderInvitations(SENDER, RECIPIENT, null, "PENDING", null, null, null).size());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.listSenderInvitations(
                SENDER, null, null, "IGNORED", null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.listSenderInvitations(
                SENDER, null, null, null, null, "id", null)).getCode());
    }

    @Test
    void updateChecksTheEtag() {
        StoredSenderInvitation sender = invite(null);
        String etag = sender.getEtag();

        assertEquals(412, assertThrows(OciException.class, () -> service.updateSenderInvitation(
                sender.getId(), "renamed", null, null, "stale")).getHttpStatus());
        StoredSenderInvitation updated = service.updateSenderInvitation(sender.getId(), "renamed", null, null, etag);
        assertEquals("renamed", updated.getDisplayName());
        assertNotEquals(etag, updated.getEtag());
    }

    @Test
    void acceptIsRefusedOnceTheSenderHasBecomeAChild() {
        StoredSenderInvitation sender = invite(null);
        String grandparent = "ocid1.tenancy.oc1..invgrandparent";
        caller.set(grandparent);
        StoredSenderInvitation toSender = service.createSenderInvitation(grandparent, SENDER, null, null, null,
                null, null, null).invitation();
        caller.set(SENDER);
        service.acceptRecipientInvitation(toSender.getRecipientInvitationId(), null);
        caller.set(RECIPIENT);

        OciException e = assertThrows(OciException.class,
                () -> service.acceptRecipientInvitation(sender.getRecipientInvitationId(), null));

        assertEquals(409, e.getHttpStatus());
        assertEquals("Conflict", e.getCode());
        assertTrue(tenancies.find(RECIPIENT).map(t -> t.getParentTenancyId() == null).orElse(true));
    }

    @Test
    void acceptIsRefusedOnceTheRecipientHasJoinedAnotherOrganization() {
        StoredSenderInvitation first = invite(null);
        String other = "ocid1.tenancy.oc1..invother";
        caller.set(other);
        StoredSenderInvitation second = service.createSenderInvitation(other, RECIPIENT, null, null, null, null,
                null, null).invitation();
        caller.set(RECIPIENT);
        service.acceptRecipientInvitation(second.getRecipientInvitationId(), null);

        OciException e = assertThrows(OciException.class,
                () -> service.acceptRecipientInvitation(first.getRecipientInvitationId(), null));

        assertEquals("Conflict", e.getCode());
        assertEquals(other, tenancies.find(RECIPIENT).orElseThrow().getParentTenancyId());
    }
}
