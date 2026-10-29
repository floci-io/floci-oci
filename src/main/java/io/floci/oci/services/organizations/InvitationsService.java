package io.floci.oci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.TenancyRegistry;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.organizations.model.StoredRecipientInvitation;
import io.floci.oci.services.organizations.model.StoredSenderInvitation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Organizations invitations: a parent tenancy invites an existing tenancy, which accepts and
 * joins the parent's organization as a child, or ignores the invitation. The sender's half
 * lives in the sender tenancy's partition and the recipient's half in the recipient's.
 */
@ApplicationScoped
public class InvitationsService {

    private static final Logger LOG = Logger.getLogger(InvitationsService.class);

    static final Set<String> LIFECYCLE_STATES =
            Set.of("CREATING", "ACTIVE", "INACTIVE", "UPDATING", "FAILED", "TERMINATED");
    static final Set<String> SENDER_STATUSES =
            Set.of("PENDING", "CANCELED", "ACCEPTED", "EXPIRED", "FAILED", "NOT_APPLICABLE");
    static final Set<String> RECIPIENT_STATUSES =
            Set.of("PENDING", "CANCELED", "ACCEPTED", "IGNORED", "EXPIRED", "FAILED");
    private static final Set<String> SUBJECTS = Set.of("LINK", "GOVERNANCE", "EXTENDED_FEATURES");
    private static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");

    private final StorageBackend<String, StoredSenderInvitation> senderInvitations;
    private final StorageBackend<String, StoredRecipientInvitation> recipientInvitations;
    private final OrganizationsService organizations;
    private final TenancyRegistry tenancies;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;

    @Inject
    public InvitationsService(StorageFactory storageFactory, OrganizationsService organizations,
                              TenancyRegistry tenancies, WorkRequestService workRequests,
                              OciContext ociContext) {
        this(storageFactory.createGlobal("organizations", "organizations-sender-invitations.json",
                        new TypeReference<Map<String, StoredSenderInvitation>>() {}),
                storageFactory.createGlobal("organizations", "organizations-recipient-invitations.json",
                        new TypeReference<Map<String, StoredRecipientInvitation>>() {}),
                organizations, tenancies, workRequests, ociContext);
    }

    InvitationsService(StorageBackend<String, StoredSenderInvitation> senderInvitations,
                       StorageBackend<String, StoredRecipientInvitation> recipientInvitations,
                       OrganizationsService organizations, TenancyRegistry tenancies,
                       WorkRequestService workRequests, OciContext ociContext) {
        this.senderInvitations = senderInvitations;
        this.recipientInvitations = recipientInvitations;
        this.organizations = organizations;
        this.tenancies = tenancies;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
    }

    /** The created invitation and the id of its (already succeeded) work request. */
    public record Created(StoredSenderInvitation invitation, String workRequestId) {
    }

    // ── Sender invitations ─────────────────────────────────────────────────────

    /**
     * Invites {@code recipientTenancyId} into the caller's organization and delivers the
     * matching recipient invitation at once.
     */
    public synchronized Created createSenderInvitation(String compartmentId, String recipientTenancyId,
                                                       String recipientEmailAddress, String displayName,
                                                       List<String> subjects, List<String> features,
                                                       Map<String, String> freeformTags,
                                                       Map<String, Map<String, Object>> definedTags) {
        requireNonBlank(compartmentId, "compartmentId");
        requireNonBlank(recipientTenancyId, "recipientTenancyId");
        String senderId = ociContext.tenancyId();
        if (!compartmentId.equals(senderId) || organizations.callerIsAChild()) {
            throw notFound("tenancy", compartmentId);
        }
        if (recipientTenancyId.equals(senderId)) {
            throw OciException.invalidParameter("A tenancy cannot invite itself");
        }
        if (!recipientTenancyId.startsWith("ocid1.tenancy.")) {
            throw OciException.invalidParameter("Invalid recipientTenancyId: " + recipientTenancyId);
        }
        List<String> invitationSubjects = subjects == null || subjects.isEmpty() ? List.of("LINK") : subjects;
        for (String subject : invitationSubjects) {
            if (!SUBJECTS.contains(subject)) {
                throw OciException.invalidParameter("Invalid subject: " + subject);
            }
        }
        if (organizations.belongsToAnOrganization(recipientTenancyId)) {
            throw OciException.conflict("Tenancy " + recipientTenancyId + " already belongs to an organization");
        }
        boolean pending = TenancyAwareStorageBackend.scanTenancy(senderInvitations, senderId).stream()
                .anyMatch(i -> recipientTenancyId.equals(i.getRecipientTenancyId()) && "PENDING".equals(i.getStatus()));
        if (pending) {
            throw OciException.conflict("Tenancy " + recipientTenancyId + " already has a pending invitation");
        }

        String realm = ociContext.realmOf(tenancies.homeRegion(senderId));
        String now = Instant.now().toString();
        StoredSenderInvitation sender = new StoredSenderInvitation();
        sender.setId(Ocids.generateGlobal("senderinvitation", realm));
        StoredRecipientInvitation recipient = new StoredRecipientInvitation();
        recipient.setId(Ocids.generateGlobal("recipientinvitation", realm));

        sender.setCompartmentId(senderId);
        sender.setRecipientInvitationId(recipient.getId());
        sender.setRecipientTenancyId(recipientTenancyId);
        sender.setDisplayName(displayName != null ? displayName : sender.getId());
        sender.setRecipientEmailAddress(recipientEmailAddress);
        sender.setSubjects(invitationSubjects);
        sender.setFeatures(features);
        sender.setLifecycleState("ACTIVE");
        sender.setStatus("PENDING");
        sender.setTimeCreated(now);
        sender.setFreeformTags(freeformTags);
        sender.setDefinedTags(definedTags);
        sender.setEtag(Etags.newEtag());

        recipient.setCompartmentId(recipientTenancyId);
        recipient.setSenderInvitationId(sender.getId());
        recipient.setSenderTenancyId(senderId);
        recipient.setDisplayName(sender.getDisplayName());
        recipient.setRecipientEmailAddress(recipientEmailAddress);
        recipient.setSubjects(invitationSubjects);
        recipient.setFeatures(features);
        recipient.setLifecycleState("ACTIVE");
        recipient.setStatus("PENDING");
        recipient.setTimeCreated(now);
        recipient.setEtag(Etags.newEtag());

        saveSender(sender);
        saveRecipient(recipient);
        LOG.infov("createSenderInvitation {0} from {1} to {2}", sender.getId(), senderId, recipientTenancyId);
        String workRequestId = workRequests.succeeded(OrganizationsService.WR_SERVICE, "CREATE_SENDER_INVITATION",
                senderId, List.of(WorkRequestService.resource("senderInvitation", "CREATED", sender.getId(), null)));
        return new Created(sender, workRequestId);
    }

    public StoredSenderInvitation getSenderInvitation(String id) {
        return TenancyAwareStorageBackend.getIn(senderInvitations, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("sender invitation", id));
    }

    public List<StoredSenderInvitation> listSenderInvitations(String compartmentId, String recipientTenancyId,
                                                              String lifecycleState, String status,
                                                              String displayName, String sortBy,
                                                              String sortOrder) {
        requireCallerCompartment(compartmentId);
        requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        requireEnum(status, SENDER_STATUSES, "status");
        requireEnum(sortBy, SORT_BY, "sortBy");
        requireEnum(sortOrder, OrganizationsService.SORT_ORDERS, "sortOrder");
        boolean byName = "displayName".equals(sortBy);
        Comparator<StoredSenderInvitation> order = byName
                ? Comparator.comparing(StoredSenderInvitation::getDisplayName)
                : Comparator.comparing(StoredSenderInvitation::getTimeCreated);
        order = order.thenComparing(StoredSenderInvitation::getId);
        String direction = sortOrder != null ? sortOrder : (byName ? "ASC" : "DESC");
        if ("DESC".equals(direction)) {
            order = order.reversed();
        }
        return TenancyAwareStorageBackend.scanTenancy(senderInvitations, compartmentId).stream()
                .filter(i -> recipientTenancyId == null || recipientTenancyId.equals(i.getRecipientTenancyId()))
                .filter(i -> lifecycleState == null || lifecycleState.equals(i.getLifecycleState()))
                .filter(i -> status == null || status.equals(i.getStatus()))
                .filter(i -> displayName == null || displayName.equals(i.getDisplayName()))
                .sorted(order)
                .toList();
    }

    public synchronized StoredSenderInvitation updateSenderInvitation(String id, String displayName,
                                                                      Map<String, String> freeformTags,
                                                                      Map<String, Map<String, Object>> definedTags,
                                                                      String ifMatch) {
        StoredSenderInvitation invitation = getSenderInvitation(id);
        Etags.checkIfMatch(ifMatch, invitation.getEtag());
        if (displayName != null) {
            invitation.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            invitation.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            invitation.setDefinedTags(definedTags);
        }
        invitation.setTimeUpdated(Instant.now().toString());
        invitation.setEtag(Etags.newEtag());
        saveSender(invitation);
        return invitation;
    }

    /** Cancels a pending invitation on both sides. Returns the work request id. */
    public synchronized String cancelSenderInvitation(String id, String ifMatch) {
        StoredSenderInvitation sender = getSenderInvitation(id);
        Etags.checkIfMatch(ifMatch, sender.getEtag());
        requirePending(sender.getStatus(), "sender invitation " + id, "canceled");
        String now = Instant.now().toString();
        markSender(sender, "CANCELED", now);
        recipientOf(sender).ifPresent(recipient -> markRecipient(recipient, "CANCELED", now));
        LOG.infov("cancelSenderInvitation {0}", id);
        return workRequests.succeeded(OrganizationsService.WR_SERVICE, "CANCEL_SENDER_INVITATION",
                sender.getCompartmentId(),
                List.of(WorkRequestService.resource("senderInvitation", "UPDATED", id, null)));
    }

    // ── Recipient invitations ──────────────────────────────────────────────────

    public StoredRecipientInvitation getRecipientInvitation(String id) {
        return TenancyAwareStorageBackend.getIn(recipientInvitations, ociContext.tenancyId(), id)
                .orElseThrow(() -> notFound("recipient invitation", id));
    }

    public List<StoredRecipientInvitation> listRecipientInvitations(String compartmentId, String senderTenancyId,
                                                                    String lifecycleState, String status) {
        requireCallerCompartment(compartmentId);
        requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        requireEnum(status, RECIPIENT_STATUSES, "status");
        return TenancyAwareStorageBackend.scanTenancy(recipientInvitations, compartmentId).stream()
                .filter(i -> senderTenancyId == null || senderTenancyId.equals(i.getSenderTenancyId()))
                .filter(i -> lifecycleState == null || lifecycleState.equals(i.getLifecycleState()))
                .filter(i -> status == null || status.equals(i.getStatus()))
                .sorted(Comparator.comparing(StoredRecipientInvitation::getTimeCreated)
                        .thenComparing(StoredRecipientInvitation::getId))
                .toList();
    }

    public synchronized StoredRecipientInvitation updateRecipientInvitation(String id, String displayName,
                                                                            Map<String, String> freeformTags,
                                                                            Map<String, Map<String, Object>> definedTags,
                                                                            String ifMatch) {
        StoredRecipientInvitation invitation = getRecipientInvitation(id);
        Etags.checkIfMatch(ifMatch, invitation.getEtag());
        if (displayName != null) {
            invitation.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            invitation.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            invitation.setDefinedTags(definedTags);
        }
        invitation.setTimeUpdated(Instant.now().toString());
        invitation.setEtag(Etags.newEtag());
        saveRecipient(invitation);
        return invitation;
    }

    /**
     * Accepts a pending invitation: the caller becomes a child of the sender and joins its
     * organization, linked for CORE plus the invitation's features. Returns the work request id.
     */
    public synchronized String acceptRecipientInvitation(String id, String ifMatch) {
        StoredRecipientInvitation recipient = getRecipientInvitation(id);
        Etags.checkIfMatch(ifMatch, recipient.getEtag());
        requirePending(recipient.getStatus(), "recipient invitation " + id, "accepted");
        String recipientId = recipient.getCompartmentId();
        String senderId = recipient.getSenderTenancyId();
        organizations.admitInvitedChild(senderId, recipientId, recipient.getFeatures());

        String now = Instant.now().toString();
        markRecipient(recipient, "ACCEPTED", now);
        TenancyAwareStorageBackend.getIn(senderInvitations, senderId, recipient.getSenderInvitationId())
                .ifPresent(sender -> markSender(sender, "ACCEPTED", now));
        LOG.infov("acceptRecipientInvitation {0}: {1} joined {2}", id, recipientId, senderId);
        return workRequests.succeeded(OrganizationsService.WR_SERVICE, "ACCEPT_RECIPIENT_INVITATION", recipientId,
                List.of(WorkRequestService.resource("recipientInvitation", "UPDATED", id, null)));
    }

    /** Ignores a pending invitation. The sender's invitation stays pending until it is canceled. */
    public synchronized StoredRecipientInvitation ignoreRecipientInvitation(String id, String ifMatch) {
        StoredRecipientInvitation recipient = getRecipientInvitation(id);
        Etags.checkIfMatch(ifMatch, recipient.getEtag());
        requirePending(recipient.getStatus(), "recipient invitation " + id, "ignored");
        markRecipient(recipient, "IGNORED", Instant.now().toString());
        LOG.infov("ignoreRecipientInvitation {0}", id);
        return recipient;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Optional<StoredRecipientInvitation> recipientOf(StoredSenderInvitation sender) {
        return TenancyAwareStorageBackend.getIn(recipientInvitations, sender.getRecipientTenancyId(),
                sender.getRecipientInvitationId());
    }

    private void markSender(StoredSenderInvitation sender, String status, String now) {
        sender.setStatus(status);
        sender.setTimeUpdated(now);
        sender.setEtag(Etags.newEtag());
        saveSender(sender);
    }

    private void markRecipient(StoredRecipientInvitation recipient, String status, String now) {
        recipient.setStatus(status);
        recipient.setTimeUpdated(now);
        recipient.setEtag(Etags.newEtag());
        saveRecipient(recipient);
    }

    private void saveSender(StoredSenderInvitation invitation) {
        TenancyAwareStorageBackend.putIn(senderInvitations, invitation.getCompartmentId(), invitation.getId(), invitation);
    }

    private void saveRecipient(StoredRecipientInvitation invitation) {
        TenancyAwareStorageBackend.putIn(recipientInvitations, invitation.getCompartmentId(), invitation.getId(),
                invitation);
    }

    private void requireCallerCompartment(String compartmentId) {
        requireNonBlank(compartmentId, "compartmentId");
        if (!compartmentId.equals(ociContext.tenancyId())) {
            throw notFound("tenancy", compartmentId);
        }
    }

    private static void requireEnum(String value, Set<String> allowed, String name) {
        if (value != null && !allowed.contains(value)) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    private static void requirePending(String status, String resource, String action) {
        if (!"PENDING".equals(status)) {
            throw OciException.conflict("The " + resource + " is " + status + " and cannot be " + action);
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
