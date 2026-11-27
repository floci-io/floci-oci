package io.floci.oci.services.database;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import io.floci.oci.services.identity.CompartmentValidator;
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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Autonomous Databases on the Database service (oci-go-sdk/database DatabaseClient), under the
 * {@code /20160918} root it shares with Identity and Networking. Create, Get, Update, Start, Stop
 * and Restart return the AutonomousDatabase body with its {@code etag}; the mutations add
 * {@code opc-work-request-id}, polled on the core work-request client. Delete returns no body.
 * The list is a bare JSON array paged with {@code opc-next-page}; GenerateAutonomousDatabaseWallet
 * returns the wallet zip as a binary body.
 */
@Path(AutonomousDatabaseService.API_PREFIX)
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AutonomousDatabaseController {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";

    private final AutonomousDatabaseService service;
    private final CompartmentValidator compartments;

    @Inject
    public AutonomousDatabaseController(AutonomousDatabaseService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    @POST
    @Path("/autonomousDatabases")
    public Response createAutonomousDatabase(Map<String, Object> body) {
        Map<String, Object> details = body(body);
        String compartmentId = DatabaseRequests.requireString(details, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return withWorkRequest(service.create(details));
        }
    }

    @GET
    @Path("/autonomousDatabases/{autonomousDatabaseId}")
    public Response getAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId) {
        StoredAutonomousDatabase database = service.get(autonomousDatabaseId);
        return Response.ok(database.toWire()).header("etag", database.getEtag()).build();
    }

    @GET
    @Path("/autonomousDatabases")
    public Response listAutonomousDatabases(@QueryParam("compartmentId") String compartmentId,
                                            @QueryParam("autonomousContainerDatabaseId") String containerId,
                                            @QueryParam("limit") Integer limit,
                                            @QueryParam("page") String page,
                                            @QueryParam("sortBy") String sortBy,
                                            @QueryParam("sortOrder") String sortOrder,
                                            @QueryParam("infrastructureType") String infrastructureType,
                                            @QueryParam("lifecycleState") String lifecycleState,
                                            @QueryParam("lifecycleStateNotEqualTo") String lifecycleStateNotEqualTo,
                                            @QueryParam("dbWorkload") String dbWorkload,
                                            @QueryParam("dbVersion") String dbVersion,
                                            @QueryParam("isFreeTier") Boolean isFreeTier,
                                            @QueryParam("displayName") String displayName,
                                            @QueryParam("isRefreshableClone") Boolean isRefreshableClone,
                                            @QueryParam("isDataGuardEnabled") Boolean isDataGuardEnabled,
                                            @QueryParam("isResourcePoolLeader") Boolean isResourcePoolLeader,
                                            @QueryParam("resourcePoolLeaderId") String resourcePoolLeaderId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        compartments.requireInQuery(compartmentId);
        AutonomousDatabaseService.ListFilter filter = new AutonomousDatabaseService.ListFilter(lifecycleState,
                lifecycleStateNotEqualTo, displayName, dbWorkload, dbVersion, isFreeTier, isDataGuardEnabled,
                isRefreshableClone, isResourcePoolLeader, resourcePoolLeaderId, containerId, infrastructureType,
                sortBy, sortOrder);
        return DatabaseRequests.list(service.list(compartmentId, filter).stream()
                .map(StoredAutonomousDatabase::toWire).toList(), limit, page);
    }

    @PUT
    @Path("/autonomousDatabases/{autonomousDatabaseId}")
    public Response updateAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                             @HeaderParam("if-match") String ifMatch,
                                             Map<String, Object> body) {
        return withWorkRequest(service.update(autonomousDatabaseId, body(body), ifMatch));
    }

    @DELETE
    @Path("/autonomousDatabases/{autonomousDatabaseId}")
    public Response deleteAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                             @HeaderParam("if-match") String ifMatch) {
        return Response.noContent().header(WORK_REQUEST_ID, service.delete(autonomousDatabaseId, ifMatch)).build();
    }

    // Start has no body; Stop's isForceStop and Restart's details change nothing here, and clients
    // may send either without a content type.
    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/autonomousDatabases/{autonomousDatabaseId}/actions/start")
    public Response startAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                            @HeaderParam("if-match") String ifMatch) {
        return withWorkRequest(service.start(autonomousDatabaseId, ifMatch));
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/autonomousDatabases/{autonomousDatabaseId}/actions/stop")
    public Response stopAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                           @HeaderParam("if-match") String ifMatch) {
        return withWorkRequest(service.stop(autonomousDatabaseId, ifMatch));
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Path("/autonomousDatabases/{autonomousDatabaseId}/actions/restart")
    public Response restartAutonomousDatabase(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                              @HeaderParam("if-match") String ifMatch) {
        return withWorkRequest(service.restart(autonomousDatabaseId, ifMatch));
    }

    @POST
    @Path("/autonomousDatabases/{autonomousDatabaseId}/actions/generateWallet")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response generateAutonomousDatabaseWallet(@PathParam("autonomousDatabaseId") String autonomousDatabaseId,
                                                     Map<String, Object> body) {
        byte[] wallet = service.generateWallet(autonomousDatabaseId, body(body));
        StoredAutonomousDatabase database = service.get(autonomousDatabaseId);
        return Response.ok(wallet, MediaType.APPLICATION_OCTET_STREAM)
                .header("etag", database.getEtag())
                .header("content-length", wallet.length)
                .header("last-modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)))
                .build();
    }

    private static Response withWorkRequest(AutonomousDatabaseService.Result result) {
        return Response.ok(result.database().toWire())
                .header("etag", result.database().getEtag())
                .header(WORK_REQUEST_ID, result.workRequestId())
                .build();
    }

    private static Map<String, Object> body(Map<String, Object> body) {
        return body != null ? body : Map.of();
    }
}
