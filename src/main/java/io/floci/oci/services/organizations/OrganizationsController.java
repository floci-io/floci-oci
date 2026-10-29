package io.floci.oci.services.organizations;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.organizations.model.StoredLink;
import io.floci.oci.services.organizations.model.StoredOrganization;
import io.floci.oci.services.organizations.model.StoredOrganizationTenancy;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Organizations (Tenant Manager control plane) at {@code /20230401}. Lists are
 * {@code {"items":[…]}} collections paged with {@code opc-next-page}; mutations are
 * work-request based ({@code 202} plus {@code opc-work-request-id}, no body).
 */
@Path("/20230401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrganizationsController {

    private final OrganizationsService service;
    private final WorkRequestService workRequests;

    @Inject
    public OrganizationsController(OrganizationsService service, WorkRequestService workRequests) {
        this.service = service;
        this.workRequests = workRequests;
    }

    // ── Child tenancies ────────────────────────────────────────────────────────

    @POST
    @Path("/childTenancies")
    public Response createChildTenancy(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        String workRequestId = service.createChildTenancy(str(details, "compartmentId"),
                str(details, "tenancyName"), str(details, "homeRegion"), str(details, "adminEmail"),
                str(details, "policyName"), str(details, "governanceStatus"),
                str(details, "subscriptionId"), stringList(details, "features"));
        return accepted(workRequestId);
    }

    // ── Organizations ──────────────────────────────────────────────────────────

    @GET
    @Path("/organizations")
    public Response listOrganizations(@QueryParam("compartmentId") String compartmentId,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        return collection(service.listOrganizations(compartmentId).stream()
                .map(StoredOrganization::toWire).toList(), limit, page);
    }

    @GET
    @Path("/organizations/{organizationId}")
    public Response getOrganization(@PathParam("organizationId") String organizationId) {
        StoredOrganization organization = service.getOrganization(organizationId);
        return Response.ok(organization.toWire()).header("etag", organization.getEtag()).build();
    }

    @PUT
    @Path("/organizations/{organizationId}")
    public Response updateOrganization(@PathParam("organizationId") String organizationId,
                                       @HeaderParam("if-match") String ifMatch,
                                       Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return accepted(service.updateOrganization(organizationId,
                str(details, "defaultUcmSubscriptionId"), ifMatch));
    }

    @GET
    @Path("/organizations/{organizationId}/tenancies")
    public Response listOrganizationTenancies(@PathParam("organizationId") String organizationId,
                                              @QueryParam("limit") Integer limit,
                                              @QueryParam("page") String page) {
        return collection(service.listOrganizationTenancies(organizationId).stream()
                .map(StoredOrganizationTenancy::toWire).toList(), limit, page);
    }

    @GET
    @Path("/organizations/{organizationId}/tenancies/{tenancyId}")
    public Response getOrganizationTenancy(@PathParam("organizationId") String organizationId,
                                           @PathParam("tenancyId") String tenancyId) {
        StoredOrganizationTenancy membership = service.getOrganizationTenancy(organizationId, tenancyId);
        return Response.ok(membership.toWire()).header("etag", membership.getEtag()).build();
    }

    @DELETE
    @Path("/organizationTenancies/{organizationTenancyId}")
    public Response deleteOrganizationTenancy(@PathParam("organizationTenancyId") String tenancyId,
                                              @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteOrganizationTenancy(tenancyId, ifMatch));
    }

    @POST
    @Path("/organizationTenancies/{organizationTenancyId}/actions/restore")
    @Consumes(MediaType.WILDCARD)
    public Response restoreOrganizationTenancy(@PathParam("organizationTenancyId") String tenancyId,
                                               @HeaderParam("if-match") String ifMatch) {
        return accepted(service.restoreOrganizationTenancy(tenancyId, ifMatch));
    }

    @POST
    @Path("/organizations/{organizationId}/tenancies/{organizationTenancyId}/actions/addGovernance")
    @Consumes(MediaType.WILDCARD)
    public Response addGovernance(@PathParam("organizationId") String organizationId,
                                  @PathParam("organizationTenancyId") String tenancyId,
                                  @HeaderParam("if-match") String ifMatch) {
        return accepted(service.setGovernance(organizationId, tenancyId, true, ifMatch));
    }

    @POST
    @Path("/organizations/{organizationId}/tenancies/{organizationTenancyId}/actions/removeGovernance")
    @Consumes(MediaType.WILDCARD)
    public Response removeGovernance(@PathParam("organizationId") String organizationId,
                                     @PathParam("organizationTenancyId") String tenancyId,
                                     @HeaderParam("if-match") String ifMatch) {
        return accepted(service.setGovernance(organizationId, tenancyId, false, ifMatch));
    }

    @POST
    @Path("/organizationTenancies/{organizationTenancyId}/actions/approveForTransfer")
    @Consumes(MediaType.WILDCARD)
    public Response approveForTransfer(@PathParam("organizationTenancyId") String tenancyId,
                                       @QueryParam("compartmentId") String compartmentId,
                                       @HeaderParam("if-match") String ifMatch) {
        StoredOrganizationTenancy membership = service.setApprovedForTransfer(compartmentId, tenancyId, true, ifMatch);
        return Response.ok(membership.toWire()).header("etag", membership.getEtag()).build();
    }

    @POST
    @Path("/organizationTenancies/{organizationTenancyId}/actions/unapproveForTransfer")
    @Consumes(MediaType.WILDCARD)
    public Response unapproveForTransfer(@PathParam("organizationTenancyId") String tenancyId,
                                         @QueryParam("compartmentId") String compartmentId,
                                         @HeaderParam("if-match") String ifMatch) {
        StoredOrganizationTenancy membership = service.setApprovedForTransfer(compartmentId, tenancyId, false, ifMatch);
        return Response.ok(membership.toWire()).header("etag", membership.getEtag()).build();
    }

    // ── Links ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/linkFeatures")
    public Response listLinkFeatures(@QueryParam("compartmentId") String compartmentId) {
        return collection(service.listLinkFeatures(compartmentId), null, null);
    }

    @GET
    @Path("/links")
    public Response listLinks(@QueryParam("parentTenancyId") String parentTenancyId,
                              @QueryParam("childTenancyId") String childTenancyId,
                              @QueryParam("feature") String feature,
                              @QueryParam("lifecycleState") String lifecycleState,
                              @QueryParam("sortOrder") String sortOrder,
                              @QueryParam("limit") Integer limit,
                              @QueryParam("page") String page) {
        return collection(service.listLinks(parentTenancyId, childTenancyId, feature, lifecycleState,
                sortOrder).stream().map(StoredLink::toWire).toList(), limit, page);
    }

    @GET
    @Path("/links/{linkId}")
    public Response getLink(@PathParam("linkId") String linkId) {
        StoredLink link = service.getLink(linkId);
        return Response.ok(link.toWire()).header("etag", link.getEtag()).build();
    }

    @DELETE
    @Path("/links/{linkId}")
    public Response deleteLink(@PathParam("linkId") String linkId,
                               @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteLink(linkId, ifMatch));
    }

    @GET
    @Path("/links/{linkId}/tenancyNames")
    public Response getLinkWithTenancyNames(@PathParam("linkId") String linkId) {
        StoredLink link = service.getLink(linkId);
        Map<String, Object> wire = new LinkedHashMap<>(link.toWire());
        wire.put("parentTenancyName", service.tenancyName(link.getParentTenancyId()));
        wire.put("childTenancyName", service.tenancyName(link.getChildTenancyId()));
        return Response.ok(wire).header("etag", link.getEtag()).build();
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page,
                                     @QueryParam("sortOrder") String sortOrder) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        requireSortOrder(sortOrder);
        Comparator<StoredWorkRequest> order = Comparator.comparing(StoredWorkRequest::getTimeAccepted)
                .thenComparing(StoredWorkRequest::getId);
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return collection(workRequests.list(OrganizationsService.WR_SERVICE, compartmentId).stream()
                .sorted(order).map(StoredWorkRequest::toWire).toList(), limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequests.get(OrganizationsService.WR_SERVICE, workRequestId).toWire()).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page,
                                          @QueryParam("sortOrder") String sortOrder) {
        requireSortOrder(sortOrder);
        workRequests.get(OrganizationsService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page,
                                        @QueryParam("sortOrder") String sortOrder) {
        requireSortOrder(sortOrder);
        workRequests.get(OrganizationsService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void requireSortOrder(String sortOrder) {
        if (sortOrder != null && !OrganizationsService.SORT_ORDERS.contains(sortOrder)) {
            throw OciException.invalidParameter("Invalid sortOrder: " + sortOrder);
        }
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
}
