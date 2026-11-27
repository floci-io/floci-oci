package io.floci.oci.core.workrequest;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.ServiceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;

/**
 * GetWorkRequest on {@code /20160918/workRequests/{id}}, the path both Identity's work requests
 * and the core work-request client (oci-go-sdk/workrequests, used for VCN and subnet compartment
 * moves, and every Autonomous Database operation) poll. It belongs to no single service, so no service descriptor registers it and the
 * enabled check runs here against the service that created the work request: disabling one of
 * them never stops the other's work requests from being polled.
 */
@Path("/20160918")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class IaasWorkRequestController {

    private static final Set<String> SERVICES = Set.of("identity", "networking", "database");

    private final WorkRequestService workRequests;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public IaasWorkRequestController(WorkRequestService workRequests, ServiceRegistry serviceRegistry) {
        this.workRequests = workRequests;
        this.serviceRegistry = serviceRegistry;
    }

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get(workRequestId);
        if (!SERVICES.contains(wr.getService())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Work request not found or not authorized: " + workRequestId);
        }
        if (!serviceRegistry.isServiceEnabled(wr.getService())) {
            throw OciException.serviceUnavailable("Service " + wr.getService() + " is not enabled.");
        }
        return Response.ok(wr.toWire()).build();
    }
}
