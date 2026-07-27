package io.floci.oci.services.identity;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;
import io.floci.oci.services.identity.model.StoredUser;
import io.floci.oci.services.identity.model.StoredUserGroupMembership;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
 * OCI Identity (IAM) API — {@code /20160918/…}. Lists return bare JSON arrays with the
 * {@code opc-next-page} header; single resources carry an {@code etag} header.
 */
@Path("/20160918")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IdentityController {

    private final IdentityService service;
    private final WorkRequestService workRequests;

    @Inject
    public IdentityController(IdentityService service, WorkRequestService workRequests) {
        this.service = service;
        this.workRequests = workRequests;
    }

    // ── Compartments ───────────────────────────────────────────────────────────

    @POST
    @Path("/compartments")
    public Response createCompartment(Map<String, Object> body) {
        StoredCompartment c = service.createCompartment(
                str(body, "compartmentId"), str(body, "name"), str(body, "description"),
                freeformTags(body), definedTags(body));
        return withEtag(Response.ok(c), c.getEtag());
    }

    @GET
    @Path("/compartments/{compartmentId}")
    public Response getCompartment(@PathParam("compartmentId") String compartmentId) {
        StoredCompartment c = service.getCompartment(compartmentId);
        return withEtag(Response.ok(c), c.getEtag());
    }

    @GET
    @Path("/compartments")
    public Response listCompartments(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("compartmentIdInSubtree") Boolean subtree,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        return paged(service.listCompartments(compartmentId, Boolean.TRUE.equals(subtree)),
                limit, page);
    }

    @PUT
    @Path("/compartments/{compartmentId}")
    public Response updateCompartment(@PathParam("compartmentId") String compartmentId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        StoredCompartment c = service.updateCompartment(compartmentId,
                str(body, "name"), str(body, "description"),
                freeformTags(body), definedTags(body), ifMatch);
        return withEtag(Response.ok(c), c.getEtag());
    }

    @DELETE
    @Path("/compartments/{compartmentId}")
    public Response deleteCompartment(@PathParam("compartmentId") String compartmentId,
                                      @HeaderParam("if-match") String ifMatch) {
        String workRequestId = service.deleteCompartment(compartmentId, ifMatch);
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    // ── Users ──────────────────────────────────────────────────────────────────

    @POST
    @Path("/users")
    public Response createUser(Map<String, Object> body) {
        StoredUser u = service.createUser(str(body, "name"), str(body, "description"),
                str(body, "email"), freeformTags(body), definedTags(body));
        return withEtag(Response.ok(u), u.getEtag());
    }

    @GET
    @Path("/users/{userId}")
    public Response getUser(@PathParam("userId") String userId) {
        StoredUser u = service.getUser(userId);
        return withEtag(Response.ok(u), u.getEtag());
    }

    @GET
    @Path("/users")
    public Response listUsers(@QueryParam("compartmentId") String compartmentId,
                              @QueryParam("limit") Integer limit,
                              @QueryParam("page") String page) {
        return paged(service.listUsers(), limit, page);
    }

    @PUT
    @Path("/users/{userId}")
    public Response updateUser(@PathParam("userId") String userId,
                               @HeaderParam("if-match") String ifMatch,
                               Map<String, Object> body) {
        StoredUser u = service.updateUser(userId, str(body, "description"), str(body, "email"),
                freeformTags(body), definedTags(body), ifMatch);
        return withEtag(Response.ok(u), u.getEtag());
    }

    @DELETE
    @Path("/users/{userId}")
    public Response deleteUser(@PathParam("userId") String userId,
                               @HeaderParam("if-match") String ifMatch) {
        service.deleteUser(userId, ifMatch);
        return Response.noContent().build();
    }

    // ── Groups ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/groups")
    public Response createGroup(Map<String, Object> body) {
        StoredGroup g = service.createGroup(str(body, "name"), str(body, "description"),
                freeformTags(body), definedTags(body));
        return withEtag(Response.ok(g), g.getEtag());
    }

    @GET
    @Path("/groups/{groupId}")
    public Response getGroup(@PathParam("groupId") String groupId) {
        StoredGroup g = service.getGroup(groupId);
        return withEtag(Response.ok(g), g.getEtag());
    }

    @GET
    @Path("/groups")
    public Response listGroups(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        return paged(service.listGroups(), limit, page);
    }

    @PUT
    @Path("/groups/{groupId}")
    public Response updateGroup(@PathParam("groupId") String groupId,
                                @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        StoredGroup g = service.updateGroup(groupId, str(body, "description"),
                freeformTags(body), definedTags(body), ifMatch);
        return withEtag(Response.ok(g), g.getEtag());
    }

    @DELETE
    @Path("/groups/{groupId}")
    public Response deleteGroup(@PathParam("groupId") String groupId,
                                @HeaderParam("if-match") String ifMatch) {
        service.deleteGroup(groupId, ifMatch);
        return Response.noContent().build();
    }

    // ── User group memberships ─────────────────────────────────────────────────

    @POST
    @Path("/userGroupMemberships")
    public Response addUserToGroup(Map<String, Object> body) {
        String userId = str(body, "userId");
        String groupId = str(body, "groupId");
        if (userId == null || groupId == null) {
            throw OciException.missingParameter("userId and groupId are required");
        }
        StoredUserGroupMembership m = service.addUserToGroup(userId, groupId);
        return withEtag(Response.ok(m), m.getEtag());
    }

    @GET
    @Path("/userGroupMemberships/{membershipId}")
    public Response getMembership(@PathParam("membershipId") String membershipId) {
        StoredUserGroupMembership m = service.getMembership(membershipId);
        return withEtag(Response.ok(m), m.getEtag());
    }

    @GET
    @Path("/userGroupMemberships")
    public Response listMemberships(@QueryParam("compartmentId") String compartmentId,
                                    @QueryParam("userId") String userId,
                                    @QueryParam("groupId") String groupId,
                                    @QueryParam("limit") Integer limit,
                                    @QueryParam("page") String page) {
        return paged(service.listMemberships(userId, groupId), limit, page);
    }

    @DELETE
    @Path("/userGroupMemberships/{membershipId}")
    public Response removeUserFromGroup(@PathParam("membershipId") String membershipId) {
        service.removeUserFromGroup(membershipId);
        return Response.noContent().build();
    }

    // ── Policies ───────────────────────────────────────────────────────────────

    @POST
    @Path("/policies")
    public Response createPolicy(Map<String, Object> body) {
        StoredPolicy p = service.createPolicy(str(body, "compartmentId"), str(body, "name"),
                str(body, "description"), statements(body), str(body, "versionDate"),
                freeformTags(body), definedTags(body));
        return withEtag(Response.ok(p), p.getEtag());
    }

    @GET
    @Path("/policies/{policyId}")
    public Response getPolicy(@PathParam("policyId") String policyId) {
        StoredPolicy p = service.getPolicy(policyId);
        return withEtag(Response.ok(p), p.getEtag());
    }

    @GET
    @Path("/policies")
    public Response listPolicies(@QueryParam("compartmentId") String compartmentId,
                                 @QueryParam("limit") Integer limit,
                                 @QueryParam("page") String page) {
        return paged(service.listPolicies(compartmentId), limit, page);
    }

    @PUT
    @Path("/policies/{policyId}")
    public Response updatePolicy(@PathParam("policyId") String policyId,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        StoredPolicy p = service.updatePolicy(policyId, str(body, "description"),
                statements(body), str(body, "versionDate"),
                freeformTags(body), definedTags(body), ifMatch);
        return withEtag(Response.ok(p), p.getEtag());
    }

    @DELETE
    @Path("/policies/{policyId}")
    public Response deletePolicy(@PathParam("policyId") String policyId,
                                 @HeaderParam("if-match") String ifMatch) {
        service.deletePolicy(policyId, ifMatch);
        return Response.noContent().build();
    }

    // ── Reference data ─────────────────────────────────────────────────────────

    @GET
    @Path("/availabilityDomains")
    public Response listAvailabilityDomains(@QueryParam("compartmentId") String compartmentId) {
        return Response.ok(service.availabilityDomains(compartmentId)).build();
    }

    @GET
    @Path("/regions")
    public Response listRegions() {
        return Response.ok(service.regions()).build();
    }

    @GET
    @Path("/tenancies/{tenancyId}")
    public Response getTenancy(@PathParam("tenancyId") String tenancyId) {
        return Response.ok(service.tenancy(tenancyId)).build();
    }

    @GET
    @Path("/tenancies/{tenancyId}/regionSubscriptions")
    public Response listRegionSubscriptions(@PathParam("tenancyId") String tenancyId) {
        service.tenancy(tenancyId);
        return Response.ok(service.regionSubscriptions()).build();
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get("identity", workRequestId);
        return Response.ok(wr.toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        List<java.util.Map<String, Object>> items = workRequests.list("identity", compartmentId)
                .stream().map(StoredWorkRequest::toWire).toList();
        return paged(items, limit, page);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static <T> Response paged(List<T> all, Integer limit, String page) {
        OciPage.Page<T> result = OciPage.paginate(all, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static Response withEtag(Response.ResponseBuilder builder, String etag) {
        return builder.header("etag", etag).build();
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> statements(Map<String, Object> body) {
        if (body == null || !(body.get("statements") instanceof List<?> list)) {
            return null;
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> freeformTags(Map<String, Object> body) {
        return body != null && body.get("freeformTags") instanceof Map<?, ?> m
                ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }
}
