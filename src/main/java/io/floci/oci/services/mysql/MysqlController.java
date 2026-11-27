package io.floci.oci.services.mysql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.mysql.model.StoredMysqlDbSystem;
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

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * MySQL HeatWave DB systems and their work requests, API version {@code 20190415}
 * (oci-go-sdk/mysql DbSystemClient and WorkRequestsClient). Create returns the DbSystem body;
 * every other mutation returns no body, only {@code opc-work-request-id}. Lists are bare JSON
 * arrays, paged with {@code opc-next-page}.
 */
@Path(MysqlService.API_PREFIX)
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MysqlController {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    /** ListWorkRequestsSortByEnum in oci-go-sdk/mysql. */
    private static final Set<String> WORK_REQUEST_SORT_FIELDS = Set.of("ID", "OPERATION_TYPE", "STATUS",
            "TIME_ACCEPTED", "TIME_STARTED", "TIME_FINISHED");

    private final MysqlService service;
    private final WorkRequestService workRequests;
    private final CompartmentValidator compartments;

    @Inject
    public MysqlController(MysqlService service, WorkRequestService workRequests,
                           CompartmentValidator compartments) {
        this.service = service;
        this.workRequests = workRequests;
        this.compartments = compartments;
    }

    // ── DB systems ─────────────────────────────────────────────────────────────

    @POST
    @Path("/dbSystems")
    public Response createDbSystem(Map<String, Object> body) {
        Map<String, Object> details = body(body);
        String compartmentId = MysqlRequests.string(details, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            MysqlService.Created created = service.createDbSystem(details);
            return Response.ok(created.system().toWire())
                    .header("etag", created.system().getEtag())
                    .header("location", MysqlService.API_PREFIX + "/dbSystems/" + created.system().getId())
                    .header(WORK_REQUEST_ID, created.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/dbSystems/{dbSystemId}")
    public Response getDbSystem(@PathParam("dbSystemId") String dbSystemId) {
        StoredMysqlDbSystem system = service.getDbSystem(dbSystemId);
        return Response.ok(system.toWire()).header("etag", system.getEtag()).build();
    }

    @GET
    @Path("/dbSystems")
    public Response listDbSystems(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("isHeatWaveClusterAttached") Boolean isHeatWaveClusterAttached,
                                  @QueryParam("dbSystemId") String dbSystemId,
                                  @QueryParam("displayName") String displayName,
                                  @QueryParam("lifecycleState") String lifecycleState,
                                  @QueryParam("configurationId") String configurationId,
                                  @QueryParam("isUpToDate") Boolean isUpToDate,
                                  @QueryParam("databaseManagement") List<String> databaseManagement,
                                  @QueryParam("sortBy") String sortBy,
                                  @QueryParam("sortOrder") String sortOrder,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        return MysqlRequests.list(service.listDbSystems(compartmentId, isHeatWaveClusterAttached, dbSystemId,
                        displayName, lifecycleState, configurationId, isUpToDate, databaseManagement, sortBy, sortOrder)
                .stream().map(StoredMysqlDbSystem::toSummaryWire).toList(), limit, page);
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

    // StartDbSystem has no body, and clients send it without a content type.
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/dbSystems/{dbSystemId}/actions/start")
    public Response startDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                  @HeaderParam("if-match") String ifMatch) {
        return accepted(service.startDbSystem(dbSystemId, ifMatch));
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/stop")
    public Response stopDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        return accepted(service.stopDbSystem(dbSystemId, body(body), ifMatch));
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/restart")
    public Response restartDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                    @HeaderParam("if-match") String ifMatch,
                                    Map<String, Object> body) {
        return accepted(service.restartDbSystem(dbSystemId, body(body), ifMatch));
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequestWire(workRequests.get(MysqlService.WR_SERVICE, workRequestId))).build();
    }

    /**
     * CancelWorkRequest. floci-oci finishes or abandons its operations itself and cannot cancel
     * one from outside, so a known work request answers {@code 409 Conflict}: a retry cannot
     * succeed, and OCI SDKs retry {@code IncorrectState}.
     */
    @DELETE
    @Path("/workRequests/{workRequestId}")
    public Response cancelWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest workRequest = workRequests.get(MysqlService.WR_SERVICE, workRequestId);
        throw OciException.conflict("Work request " + workRequestId + " is "
                + workRequest.getStatus() + " and cannot be canceled");
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        MysqlRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        MysqlRequests.requireEnum(sortBy, WORK_REQUEST_SORT_FIELDS, "sortBy");
        return MysqlRequests.list(workRequests.list(MysqlService.WR_SERVICE, compartmentId).stream()
                .sorted(workRequestOrder(sortBy, sortOrder))
                .map(MysqlController::workRequestSummaryWire)
                .toList(), limit, page);
    }

    /**
     * The order for ListWorkRequests: by {@code sortBy} (TIME_ACCEPTED when absent), then by id.
     * Without {@code sortOrder} time fields run newest first and the others ascending, as the other
     * MySQL lists document. Work requests that lack the field (not started, not finished) come last
     * in either direction.
     */
    static Comparator<StoredWorkRequest> workRequestOrder(String sortBy, String sortOrder) {
        String field = sortBy != null ? sortBy : "TIME_ACCEPTED";
        boolean descending = sortOrder != null ? "DESC".equals(sortOrder) : field.startsWith("TIME_");
        Comparator<StoredWorkRequest> order = switch (field) {
            case "ID" -> keyed(StoredWorkRequest::getId, descending);
            case "OPERATION_TYPE" -> keyed(StoredWorkRequest::getOperationType, descending);
            case "STATUS" -> keyed(StoredWorkRequest::getStatus, descending);
            case "TIME_STARTED" -> keyed(workRequest -> instant(workRequest.getTimeStarted()), descending);
            case "TIME_FINISHED" -> keyed(workRequest -> instant(workRequest.getTimeFinished()), descending);
            default -> keyed(workRequest -> instant(workRequest.getTimeAccepted()), descending);
        };
        return order.thenComparing(keyed(StoredWorkRequest::getId, descending));
    }

    private static <T extends Comparable<? super T>> Comparator<StoredWorkRequest> keyed(
            Function<StoredWorkRequest, T> key, boolean descending) {
        Comparator<T> direction = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(key, Comparator.nullsLast(direction));
    }

    private static Instant instant(String time) {
        return time != null ? Instant.parse(time) : null;
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        StoredWorkRequest workRequest = workRequests.get(MysqlService.WR_SERVICE, workRequestId);
        List<Map<String, Object>> errors = workRequest.getErrors().stream().map(error -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("code", error.getCode());
            wire.put("message", error.getMessage());
            wire.put("timestamp", workRequest.getTimeFinished());
            return wire;
        }).toList();
        return MysqlRequests.list(errors, limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        workRequests.get(MysqlService.WR_SERVICE, workRequestId);
        return MysqlRequests.list(List.of(), limit, page);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The MySQL WorkRequest has no errors field: they are served by the errors operation. */
    private static Map<String, Object> workRequestWire(StoredWorkRequest workRequest) {
        Map<String, Object> wire = workRequest.toWire();
        wire.remove("errors");
        return wire;
    }

    /** WorkRequestSummary: the work request without its resources. */
    private static Map<String, Object> workRequestSummaryWire(StoredWorkRequest workRequest) {
        Map<String, Object> wire = workRequestWire(workRequest);
        wire.remove("resources");
        return wire;
    }

    private static Map<String, Object> body(Map<String, Object> body) {
        return body != null ? body : Map.of();
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header(WORK_REQUEST_ID, workRequestId).build();
    }

    /** Every MySQL list takes a mandatory compartmentId, which must exist. */
    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        compartments.requireInQuery(compartmentId);
    }
}
