package io.floci.oci.services.organizations;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.organizations.model.StoredRecipientInvitation;
import io.floci.oci.services.organizations.model.StoredSenderInvitation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * OCI Organizations sender and recipient invitations at {@code /20230401}. Create, get,
 * update and ignore return the invitation with an {@code etag}; cancel and accept are
 * work-request based ({@code 202}, {@code opc-work-request-id}, no body).
 */
@Path("/20230401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvitationsController {

    private final InvitationsService service;

    @Inject
    public InvitationsController(InvitationsService service) {
        this.service = service;
    }

    // ── Sender invitations ─────────────────────────────────────────────────────

    @POST
    @Path("/senderInvitations")
    public Response createSenderInvitation(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        InvitationsService.Created created = service.createSenderInvitation(str(details, "compartmentId"),
                str(details, "recipientTenancyId"), str(details, "recipientEmailAddress"),
                str(details, "displayName"), stringList(details, "subjects"), stringList(details, "features"),
                freeformTags(details), definedTags(details));
        return Response.ok(created.invitation().toWire())
                .header("etag", created.invitation().getEtag())
                .header("opc-work-request-id", created.workRequestId())
                .build();
    }

    @GET
    @Path("/senderInvitations/{senderInvitationId}")
    public Response getSenderInvitation(@PathParam("senderInvitationId") String id) {
        return withEtag(service.getSenderInvitation(id));
    }

    @GET
    @Path("/senderInvitations")
    public Response listSenderInvitations(@QueryParam("compartmentId") String compartmentId,
                                          @QueryParam("recipientTenancyId") String recipientTenancyId,
                                          @QueryParam("lifecycleState") String lifecycleState,
                                          @QueryParam("status") String status,
                                          @QueryParam("displayName") String displayName,
                                          @QueryParam("sortBy") String sortBy,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        return collection(service.listSenderInvitations(compartmentId, recipientTenancyId, lifecycleState,
                status, displayName, sortBy, sortOrder).stream().map(StoredSenderInvitation::toWire).toList(),
                limit, page);
    }

    @PUT
    @Path("/senderInvitations/{senderInvitationId}")
    public Response updateSenderInvitation(@PathParam("senderInvitationId") String id,
                                           @HeaderParam("if-match") String ifMatch,
                                           Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return withEtag(service.updateSenderInvitation(id, str(details, "displayName"),
                freeformTags(details), definedTags(details), ifMatch));
    }

    @POST
    @Path("/senderInvitations/{senderInvitationId}/actions/cancel")
    @Consumes(MediaType.WILDCARD)
    public Response cancelSenderInvitation(@PathParam("senderInvitationId") String id,
                                           @HeaderParam("if-match") String ifMatch) {
        return accepted(service.cancelSenderInvitation(id, ifMatch));
    }

    // ── Recipient invitations ──────────────────────────────────────────────────

    @GET
    @Path("/recipientInvitations/{recipientInvitationId}")
    public Response getRecipientInvitation(@PathParam("recipientInvitationId") String id) {
        return withEtag(service.getRecipientInvitation(id));
    }

    @GET
    @Path("/recipientInvitations")
    public Response listRecipientInvitations(@QueryParam("compartmentId") String compartmentId,
                                             @QueryParam("senderTenancyId") String senderTenancyId,
                                             @QueryParam("lifecycleState") String lifecycleState,
                                             @QueryParam("status") String status,
                                             @QueryParam("page") String page) {
        return collection(service.listRecipientInvitations(compartmentId, senderTenancyId, lifecycleState, status)
                .stream().map(StoredRecipientInvitation::toWire).toList(), null, page);
    }

    @PUT
    @Path("/recipientInvitations/{recipientInvitationId}")
    public Response updateRecipientInvitation(@PathParam("recipientInvitationId") String id,
                                              @HeaderParam("if-match") String ifMatch,
                                              Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return withEtag(service.updateRecipientInvitation(id, str(details, "displayName"),
                freeformTags(details), definedTags(details), ifMatch));
    }

    @POST
    @Path("/recipientInvitations/{recipientInvitationId}/actions/accept")
    @Consumes(MediaType.WILDCARD)
    public Response acceptRecipientInvitation(@PathParam("recipientInvitationId") String id,
                                              @HeaderParam("if-match") String ifMatch) {
        return accepted(service.acceptRecipientInvitation(id, ifMatch));
    }

    @POST
    @Path("/recipientInvitations/{recipientInvitationId}/actions/ignore")
    @Consumes(MediaType.WILDCARD)
    public Response ignoreRecipientInvitation(@PathParam("recipientInvitationId") String id,
                                              @HeaderParam("if-match") String ifMatch) {
        return withEtag(service.ignoreRecipientInvitation(id, ifMatch));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Response withEtag(StoredSenderInvitation invitation) {
        return Response.ok(invitation.toWire()).header("etag", invitation.getEtag()).build();
    }

    private static Response withEtag(StoredRecipientInvitation invitation) {
        return Response.ok(invitation.toWire()).header("etag", invitation.getEtag()).build();
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    private static Response collection(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a string");
    }

    private static List<String> stringList(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a list of strings");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> freeformTags(Map<String, Object> body) {
        return body.get("freeformTags") instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body.get("definedTags") instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : null;
    }
}
