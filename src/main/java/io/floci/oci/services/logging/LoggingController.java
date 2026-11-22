package io.floci.oci.services.logging;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.logging.model.StoredLoggingResource;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Logging management, {@code /20200531} (oci-go-sdk logging/logging_loggingmanagement_client.go).
 * Log group and log mutations answer {@code 202} with {@code opc-work-request-id} and no body; saved
 * searches are synchronous. ListLogGroups, ListLogs and ListWorkRequests return bare arrays;
 * ListLogSavedSearches returns {@code {"items": [...]}}.
 */
@Path("/20200531")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoggingController {

    private final LoggingService service;
    private final CompartmentValidator compartments;

    @Inject
    public LoggingController(LoggingService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    // ── Log groups ─────────────────────────────────────────────────────────────

    @POST
    @Path("/logGroups")
    public Response createLogGroup(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(LoggingService.str(body, "compartmentId"))) {
            return accepted(service.createLogGroup(body));
        }
    }

    @GET
    @Path("/logGroups/{logGroupId}")
    public Response getLogGroup(@PathParam("logGroupId") String logGroupId) {
        StoredLoggingResource group = service.getLogGroup(logGroupId);
        return Response.ok(json(group)).header("etag", group.getEtag()).build();
    }

    @GET
    @Path("/logGroups")
    public Response listLogGroups(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("isCompartmentIdInSubtree") Boolean inSubtree,
                                  @QueryParam("displayName") String displayName,
                                  @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                                  @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        return page(service.listLogGroups(compartmentId, inSubtree, displayName, sortBy, sortOrder).stream()
                .map(LoggingController::json).toList(), limit, page);
    }

    @PUT
    @Path("/logGroups/{logGroupId}")
    public Response updateLogGroup(@PathParam("logGroupId") String logGroupId, @HeaderParam("if-match") String ifMatch,
                                   Map<String, Object> body) {
        return accepted(service.updateLogGroup(logGroupId, body, ifMatch));
    }

    @DELETE
    @Path("/logGroups/{logGroupId}")
    public Response deleteLogGroup(@PathParam("logGroupId") String logGroupId, @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteLogGroup(logGroupId, ifMatch));
    }

    @POST
    @Path("/logGroups/{logGroupId}/actions/changeCompartment")
    public Response changeLogGroupCompartment(@PathParam("logGroupId") String logGroupId,
                                              @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = LoggingService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return accepted(service.changeLogGroupCompartment(logGroupId, compartmentId, ifMatch));
        }
    }

    // ── Logs ───────────────────────────────────────────────────────────────────

    @POST
    @Path("/logGroups/{logGroupId}/logs")
    public Response createLog(@PathParam("logGroupId") String logGroupId, Map<String, Object> body) {
        return accepted(service.createLog(logGroupId, body));
    }

    @GET
    @Path("/logGroups/{logGroupId}/logs/{logId}")
    public Response getLog(@PathParam("logGroupId") String logGroupId, @PathParam("logId") String logId) {
        StoredLoggingResource log = service.getLog(logGroupId, logId);
        return Response.ok(logJson(log, service)).header("etag", log.getEtag()).build();
    }

    @GET
    @Path("/logGroups/{logGroupId}/logs")
    public Response listLogs(@PathParam("logGroupId") String logGroupId, @QueryParam("logType") String logType,
                             @QueryParam("sourceService") String sourceService,
                             @QueryParam("sourceResource") String sourceResource,
                             @QueryParam("displayName") String displayName,
                             @QueryParam("lifecycleState") String lifecycleState,
                             @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                             @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        return page(service.listLogs(logGroupId, logType, sourceService, sourceResource, displayName, lifecycleState,
                sortBy, sortOrder).stream().map(l -> logJson(l, null)).toList(), limit, page);
    }

    @PUT
    @Path("/logGroups/{logGroupId}/logs/{logId}")
    public Response updateLog(@PathParam("logGroupId") String logGroupId, @PathParam("logId") String logId,
                              @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        return accepted(service.updateLog(logGroupId, logId, body, ifMatch));
    }

    @DELETE
    @Path("/logGroups/{logGroupId}/logs/{logId}")
    public Response deleteLog(@PathParam("logGroupId") String logGroupId, @PathParam("logId") String logId,
                              @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteLog(logGroupId, logId, ifMatch));
    }

    @POST
    @Path("/logGroups/{logGroupId}/logs/{logId}/actions/changeLogGroup")
    public Response changeLogLogGroup(@PathParam("logGroupId") String logGroupId, @PathParam("logId") String logId,
                                      @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        return accepted(service.changeLogLogGroup(logGroupId, logId, LoggingService.str(body, "targetLogGroupId"),
                ifMatch));
    }

    // ── Saved searches ─────────────────────────────────────────────────────────

    @POST
    @Path("/logSavedSearches")
    public Response createLogSavedSearch(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(LoggingService.str(body, "compartmentId"))) {
            StoredLoggingResource search = service.createSavedSearch(body);
            return Response.ok(json(search)).header("etag", search.getEtag()).build();
        }
    }

    @GET
    @Path("/logSavedSearches/{logSavedSearchId}")
    public Response getLogSavedSearch(@PathParam("logSavedSearchId") String id) {
        StoredLoggingResource search = service.getSavedSearch(id);
        return Response.ok(json(search)).header("etag", search.getEtag()).build();
    }

    @GET
    @Path("/logSavedSearches")
    public Response listLogSavedSearches(@QueryParam("compartmentId") String compartmentId,
                                         @QueryParam("logSavedSearchId") String id, @QueryParam("name") String name,
                                         @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                                         @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = service.listSavedSearches(compartmentId, id, name, sortBy, sortOrder).stream()
                .map(LoggingController::json).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/logSavedSearches/{logSavedSearchId}")
    public Response updateLogSavedSearch(@PathParam("logSavedSearchId") String id,
                                         @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        StoredLoggingResource search = service.updateSavedSearch(id, body, ifMatch);
        return Response.ok(json(search)).header("etag", search.getEtag()).build();
    }

    @DELETE
    @Path("/logSavedSearches/{logSavedSearchId}")
    public Response deleteLogSavedSearch(@PathParam("logSavedSearchId") String id,
                                         @HeaderParam("if-match") String ifMatch) {
        service.deleteSavedSearch(id, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/logSavedSearches/{logSavedSearchId}/actions/changeCompartment")
    public Response changeLogSavedSearchCompartment(@PathParam("logSavedSearchId") String id,
                                                    @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = LoggingService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeSavedSearchCompartment(id, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String id) {
        return Response.ok(service.getWorkRequest(id).toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("status") String status, @QueryParam("id") String id,
                                     @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        return page(service.listWorkRequests(compartmentId, status, id).stream()
                .map(StoredWorkRequest::toWire).toList(), limit, page);
    }

    @DELETE
    @Path("/workRequests/{workRequestId}")
    public Response deleteWorkRequest(@PathParam("workRequestId") String id) {
        service.cancelWorkRequest(id);
        return Response.accepted().build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String id) {
        service.getWorkRequest(id);
        return Response.ok(List.of()).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String id) {
        service.getWorkRequest(id);
        return Response.ok(List.of()).build();
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    private static Response page(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    static Map<String, Object> json(StoredLoggingResource resource) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", resource.getId());
        json.put("compartmentId", resource.getCompartmentId());
        json.putAll(resource.getSpec());
        json.put("lifecycleState", resource.getLifecycleState());
        json.put("systemTags", Map.of());
        json.put("timeCreated", resource.getTimeCreated());
        json.put("timeLastModified", resource.getTimeLastModified());
        return json;
    }

    /** Log carries {@code tenancyId}; LogSummary does not. */
    static Map<String, Object> logJson(StoredLoggingResource log, LoggingService service) {
        Map<String, Object> json = json(log);
        json.put("logGroupId", log.getLogGroupId());
        if (service != null) {
            json.put("tenancyId", service.tenancyId());
        }
        return json;
    }
}
