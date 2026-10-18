package io.floci.oci.services.psql;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.psql.model.StoredDbSystem;
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
import java.util.Set;

/**
 * OCI Database with PostgreSQL, API version {@code 20220915}. Create returns the DbSystem
 * body; every other mutation returns no body, only {@code opc-work-request-id}. Lists are
 * wrapped in {@code {"items": [...]}} and paged with {@code opc-next-page}.
 */
@Path("/20220915")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PsqlController {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final String EXCLUDED_CONFIGURATION_PARAMS = "dbConfigurationParams";

    private final PsqlService service;
    private final WorkRequestService workRequests;
    private final CompartmentValidator compartments;
    private final OciContext ociContext;

    @Inject
    public PsqlController(PsqlService service, WorkRequestService workRequests,
                          CompartmentValidator compartments, OciContext ociContext) {
        this.service = service;
        this.workRequests = workRequests;
        this.compartments = compartments;
        this.ociContext = ociContext;
    }

    // ── DB systems ─────────────────────────────────────────────────────────────

    @POST
    @Path("/dbSystems")
    public Response createDbSystem(Map<String, Object> body) {
        Map<String, Object> details = body(body);
        String compartmentId = PsqlService.string(details, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            PsqlService.Created created = service.createDbSystem(details);
            return Response.ok(created.system().toWire())
                    .header("etag", created.system().getEtag())
                    .header(WORK_REQUEST_ID, created.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/dbSystems/{dbSystemId}")
    public Response getDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                @QueryParam("excludedFields") List<String> excludedFields) {
        for (String field : excludedFields) {
            // The only value OCI defines; floci-oci returns no configuration parameters anyway.
            if (!EXCLUDED_CONFIGURATION_PARAMS.equals(field)) {
                throw OciException.invalidParameter("Invalid excludedFields: " + field);
            }
        }
        StoredDbSystem system = service.getDbSystem(dbSystemId);
        return Response.ok(system.toWire()).header("etag", system.getEtag()).build();
    }

    @GET
    @Path("/dbSystems")
    public Response listDbSystems(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("lifecycleState") String lifecycleState,
                                  @QueryParam("displayName") String displayName,
                                  @QueryParam("id") String id,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("page") String page,
                                  @QueryParam("sortOrder") String sortOrder,
                                  @QueryParam("sortBy") String sortBy) {
        compartments.requireInQuery(compartmentId);
        return collection(service.listDbSystems(compartmentId, lifecycleState, displayName, id, sortBy, sortOrder)
                .stream().map(StoredDbSystem::toSummaryWire).toList(), limit, page);
    }

    @PUT
    @Path("/dbSystems/{dbSystemId}")
    public Response updateDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                   @HeaderParam("if-match") String ifMatch,
                                   Map<String, Object> body) {
        return accepted(service.updateDbSystem(dbSystemId, body(body), ifMatch));
    }

    @DELETE
    @Path("/dbSystems/{dbSystemId}")
    public Response deleteDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                   @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteDbSystem(dbSystemId, ifMatch));
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/changeCompartment")
    public Response changeDbSystemCompartment(@PathParam("dbSystemId") String dbSystemId,
                                              @HeaderParam("if-match") String ifMatch,
                                              Map<String, Object> body) {
        String compartmentId = PsqlService.requireString(body(body), "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return accepted(service.changeCompartment(dbSystemId, compartmentId, ifMatch));
        }
    }

    // ── Runtime actions ────────────────────────────────────────────────────────

    // StartDbSystemDetails only resizes the system, which floci-oci does not model: no body is read.
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/dbSystems/{dbSystemId}/actions/start")
    public Response startDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                  @HeaderParam("if-match") String ifMatch) {
        return accepted(service.startDbSystem(dbSystemId, ifMatch));
    }

    // StopDbSystem has no body, and clients send it without a content type.
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/dbSystems/{dbSystemId}/actions/stop")
    public Response stopDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                 @HeaderParam("if-match") String ifMatch) {
        return accepted(service.stopDbSystem(dbSystemId, ifMatch));
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/restartDbInstance")
    public Response restartDbInstanceInDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                                @HeaderParam("if-match") String ifMatch,
                                                Map<String, Object> body) {
        return accepted(service.restartDbInstance(dbSystemId, body(body), ifMatch));
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/resetMasterUserPassword")
    public Response resetMasterUserPassword(@PathParam("dbSystemId") String dbSystemId,
                                            @HeaderParam("if-match") String ifMatch,
                                            Map<String, Object> body) {
        return accepted(service.resetMasterUserPassword(dbSystemId, body(body), ifMatch));
    }

    // The path segment is all lowercase in OCI's own client, unlike every other one here.
    @PUT
    @Path("/dbSystems/{dbSystemId}/dbinstances/{dbInstanceId}")
    public Response updateDbSystemDbInstance(@PathParam("dbSystemId") String dbSystemId,
                                             @PathParam("dbInstanceId") String dbInstanceId,
                                             @HeaderParam("if-match") String ifMatch,
                                             Map<String, Object> body) {
        return accepted(service.updateDbInstance(dbSystemId, dbInstanceId, body(body), ifMatch));
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    @GET
    @Path("/dbSystems/{dbSystemId}/connectionDetails")
    public Response getConnectionDetails(@PathParam("dbSystemId") String dbSystemId) {
        StoredDbSystem system = service.getDbSystem(dbSystemId);
        return Response.ok(service.connectionDetails(dbSystemId)).header("etag", system.getEtag()).build();
    }

    @GET
    @Path("/dbSystems/{dbSystemId}/primaryDbInstance")
    public Response getPrimaryDbInstance(@PathParam("dbSystemId") String dbSystemId) {
        StoredDbSystem system = service.getDbSystem(dbSystemId);
        return Response.ok(service.primaryDbInstance(dbSystemId)).header("etag", system.getEtag()).build();
    }

    // ── Catalog ────────────────────────────────────────────────────────────────

    @GET
    @Path("/shapes")
    public Response listShapes(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("id") String id,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        return collection(PsqlCatalog.shapes().stream()
                .filter(shape -> id == null || id.equals(shape.get("shape")))
                .toList(), limit, page);
    }

    @GET
    @Path("/defaultConfigurations")
    public Response listDefaultConfigurations(@QueryParam("dbVersion") String dbVersion,
                                              @QueryParam("displayName") String displayName,
                                              @QueryParam("configurationId") String configurationId,
                                              @QueryParam("lifecycleState") String lifecycleState,
                                              @QueryParam("limit") Integer limit,
                                              @QueryParam("page") String page) {
        return collection(PsqlCatalog.defaultConfigurations(ociContext.realm(), ociContext.regionCode()).stream()
                .filter(c -> dbVersion == null || dbVersion.equals(c.get("dbVersion")))
                .filter(c -> displayName == null || displayName.equals(c.get("displayName")))
                .filter(c -> configurationId == null || configurationId.equals(c.get("id")))
                .filter(c -> lifecycleState == null || lifecycleState.equals(c.get("lifecycleState")))
                .toList(), limit, page);
    }

    @GET
    @Path("/defaultConfigurations/{defaultConfigurationId}")
    public Response getDefaultConfiguration(@PathParam("defaultConfigurationId") String defaultConfigurationId) {
        Map<String, Object> configuration = PsqlCatalog
                .findDefaultConfiguration(defaultConfigurationId, ociContext.realm(), ociContext.regionCode())
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: default configuration "
                                + defaultConfigurationId));
        return Response.ok(configuration).build();
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequestWire(workRequests.get(PsqlService.WR_SERVICE, workRequestId))).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("workRequestId") String workRequestId,
                                     @QueryParam("status") String status,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page,
                                     @QueryParam("sortOrder") String sortOrder) {
        compartments.requireInQuery(compartmentId);
        requireSortOrder(sortOrder);
        Comparator<StoredWorkRequest> order = Comparator.comparing(StoredWorkRequest::getTimeAccepted)
                .thenComparing(StoredWorkRequest::getId);
        if (!"ASC".equals(sortOrder)) {
            order = order.reversed();
        }
        return collection(workRequests.list(PsqlService.WR_SERVICE, compartmentId).stream()
                .filter(wr -> workRequestId == null || workRequestId.equals(wr.getId()))
                .filter(wr -> status == null || status.equals(wr.getStatus()))
                .filter(wr -> resourceId == null || wr.getResources().stream()
                        .anyMatch(resource -> resourceId.equals(resource.getIdentifier())))
                .sorted(order)
                .map(PsqlController::workRequestWire)
                .toList(), limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page,
                                          @QueryParam("sortOrder") String sortOrder) {
        requireSortOrder(sortOrder);
        StoredWorkRequest workRequest = workRequests.get(PsqlService.WR_SERVICE, workRequestId);
        List<Map<String, Object>> errors = workRequest.getErrors().stream().map(error -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("code", error.getCode());
            wire.put("message", error.getMessage());
            wire.put("timestamp", workRequest.getTimeFinished());
            return wire;
        }).toList();
        return collection(errors, limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page,
                                        @QueryParam("sortOrder") String sortOrder) {
        requireSortOrder(sortOrder);
        workRequests.get(PsqlService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The psql WorkRequest has no errors field: they are served by the errors operation. */
    private static Map<String, Object> workRequestWire(StoredWorkRequest workRequest) {
        Map<String, Object> wire = workRequest.toWire();
        wire.remove("errors");
        return wire;
    }

    private static void requireSortOrder(String sortOrder) {
        if (sortOrder != null && !SORT_ORDERS.contains(sortOrder)) {
            throw OciException.invalidParameter("Invalid sortOrder: " + sortOrder);
        }
    }

    private static Map<String, Object> body(Map<String, Object> body) {
        return body != null ? body : Map.of();
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header(WORK_REQUEST_ID, workRequestId).build();
    }

    private static Response collection(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
