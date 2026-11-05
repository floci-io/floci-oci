package io.floci.oci.services.psql;

import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.psql.model.StoredBackup;
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

import java.util.Map;

/**
 * Backups, restore and point-in-time recovery of OCI Database with PostgreSQL at
 * {@code /20220915}. Create, delete, copy, change-compartment and restore return {@code 202}
 * with an {@code opc-work-request-id} and no body; get and update return the Backup with an
 * {@code etag}.
 */
@Path("/20220915")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PsqlBackupsController {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";

    private final PsqlBackupsService backups;
    private final PsqlService dbSystems;
    private final CompartmentValidator compartments;

    @Inject
    public PsqlBackupsController(PsqlBackupsService backups, PsqlService dbSystems,
                                 CompartmentValidator compartments) {
        this.backups = backups;
        this.dbSystems = dbSystems;
        this.compartments = compartments;
    }

    @POST
    @Path("/backups")
    public Response createBackup(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        StoredDbSystem system = dbSystems.backupSource(PsqlRequests.requireString(details, "dbSystemId"));
        try (CompartmentValidator.Use ignored = compartments.useInBody(PsqlRequests.string(details, "compartmentId"))) {
            return accepted(backups.createBackup(system, details));
        }
    }

    @GET
    @Path("/backups/{backupId}")
    public Response getBackup(@PathParam("backupId") String backupId) {
        return withEtag(backups.getBackup(backupId));
    }

    @GET
    @Path("/backups")
    public Response listBackups(@QueryParam("compartmentId") String compartmentId,
                                @QueryParam("timeStarted") String timeStarted,
                                @QueryParam("timeEnded") String timeEnded,
                                @QueryParam("lifecycleState") String lifecycleState,
                                @QueryParam("displayName") String displayName,
                                @QueryParam("backupId") String backupId,
                                @QueryParam("id") String dbSystemId,
                                @QueryParam("backupSourceType") String backupSourceType,
                                @QueryParam("sortBy") String sortBy,
                                @QueryParam("sortOrder") String sortOrder,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        if (compartmentId != null) {
            compartments.requireInQuery(compartmentId);
        }
        return PsqlRequests.collection(backups.listBackups(compartmentId, timeStarted, timeEnded, lifecycleState,
                displayName, backupId, dbSystemId, backupSourceType, sortBy, sortOrder).stream()
                .map(StoredBackup::toSummaryWire).toList(), limit, page);
    }

    @PUT
    @Path("/backups/{backupId}")
    public Response updateBackup(@PathParam("backupId") String backupId,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        return withEtag(backups.updateBackup(backupId, body != null ? body : Map.of(), ifMatch));
    }

    @DELETE
    @Path("/backups/{backupId}")
    public Response deleteBackup(@PathParam("backupId") String backupId,
                                 @HeaderParam("if-match") String ifMatch) {
        return accepted(backups.deleteBackup(backupId, ifMatch));
    }

    @POST
    @Path("/backups/{backupId}/actions/changeCompartment")
    public Response changeBackupCompartment(@PathParam("backupId") String backupId,
                                            @HeaderParam("if-match") String ifMatch,
                                            Map<String, Object> body) {
        String compartmentId = PsqlRequests.requireString(body != null ? body : Map.of(), "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return accepted(backups.changeCompartment(backupId, compartmentId, ifMatch));
        }
    }

    @POST
    @Path("/backups/{backupId}/actions/copy")
    public Response copyBackup(@PathParam("backupId") String backupId,
                               @HeaderParam("if-match") String ifMatch,
                               Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        try (CompartmentValidator.Use ignored = compartments.useInBody(PsqlRequests.string(details, "compartmentId"))) {
            return accepted(backups.copyBackup(backupId, details, ifMatch));
        }
    }

    @POST
    @Path("/dbSystems/{dbSystemId}/actions/restore")
    @Consumes(MediaType.WILDCARD)
    public Response restoreDbSystem(@PathParam("dbSystemId") String dbSystemId,
                                    @HeaderParam("if-match") String ifMatch,
                                    Map<String, Object> body) {
        return accepted(dbSystems.restoreDbSystem(dbSystemId, body != null ? body : Map.of(), ifMatch));
    }

    @GET
    @Path("/dbSystems/{dbSystemId}/pitrDetails")
    public Response getPitrDetails(@PathParam("dbSystemId") String dbSystemId) {
        Map<String, Object> details = dbSystems.pitrDetails(dbSystemId);
        return Response.ok(details).header("etag", dbSystems.getDbSystem(dbSystemId).getEtag()).build();
    }

    private static Response withEtag(StoredBackup backup) {
        return Response.ok(backup.toWire()).header("etag", backup.getEtag()).build();
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header(WORK_REQUEST_ID, workRequestId).build();
    }
}
