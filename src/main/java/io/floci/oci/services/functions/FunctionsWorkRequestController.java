package io.floci.oci.services.functions;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Functions work requests, API version {@code 20260325} (the SDK's
 * {@code WorkRequestManagementClient}). Every Functions mutation finishes before it returns, so
 * each work request is SUCCEEDED, with no errors or logs, and cannot be canceled.
 */
@Path("/20260325/workRequests")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class FunctionsWorkRequestController {

    private static final Set<String> STATUSES = Set.of("ACCEPTED", "IN_PROGRESS", "WAITING", "NEEDS_ATTENTION",
            "FAILED", "SUCCEEDED", "CANCELING", "CANCELED");
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");

    private final WorkRequestService workRequests;
    private final CompartmentValidator compartments;

    @Inject
    public FunctionsWorkRequestController(WorkRequestService workRequests, CompartmentValidator compartments) {
        this.workRequests = workRequests;
        this.compartments = compartments;
    }

    @GET
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("workRequestId") String workRequestId,
                                     @QueryParam("status") String status,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        FunctionsRequests.requireEnum(status, STATUSES, "status");
        FunctionsRequests.requireEnum(sortBy, Set.of("timeAccepted"), "sortBy");
        FunctionsRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        Comparator<StoredWorkRequest> order = Comparator.comparing(StoredWorkRequest::getTimeAccepted)
                .thenComparing(StoredWorkRequest::getId);
        List<Map<String, Object>> items = workRequests.list(FunctionsService.WR_SERVICE, compartmentId).stream()
                .filter(wr -> workRequestId == null || workRequestId.equals(wr.getId()))
                .filter(wr -> status == null || status.equals(wr.getStatus()))
                .filter(wr -> resourceId == null || wr.getResources().stream()
                        .anyMatch(resource -> resourceId.equals(resource.getIdentifier())))
                .sorted("ASC".equals(sortOrder) ? order : order.reversed())
                .map(StoredWorkRequest::toWire)
                .toList();
        return FunctionsRequests.paged(items, limit, page, true);
    }

    @GET
    @Path("/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequests.get(FunctionsService.WR_SERVICE, workRequestId).toWire()).build();
    }

    @GET
    @Path("/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        workRequests.get(FunctionsService.WR_SERVICE, workRequestId);
        return FunctionsRequests.paged(List.of(), limit, page, true);
    }

    @GET
    @Path("/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        workRequests.get(FunctionsService.WR_SERVICE, workRequestId);
        return FunctionsRequests.paged(List.of(), limit, page, true);
    }

    /** Every Functions work request has already finished, so there is nothing to cancel. */
    @DELETE
    @Path("/{workRequestId}")
    public Response cancelWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest workRequest = workRequests.get(FunctionsService.WR_SERVICE, workRequestId);
        throw OciException.conflict("Work request " + workRequestId + " is " + workRequest.getStatus()
                + " and cannot be canceled");
    }
}
