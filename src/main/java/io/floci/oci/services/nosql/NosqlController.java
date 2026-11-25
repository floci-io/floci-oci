package io.floci.oci.services.nosql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.nosql.model.StoredNosqlTable;
import io.floci.oci.services.nosql.model.StoredNosqlTable.Index;
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
 * NoSQL control plane, {@code /20190828}: tables, indexes and work requests. Table and index
 * mutations answer {@code 202} with an {@code opc-work-request-id} header and no body.
 * {@code {tableNameOrId}} is a table OCID, or a table name together with {@code compartmentId}.
 */
@Path("/20190828")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NosqlController {

    private final NosqlService service;
    private final CompartmentValidator compartments;
    private final WorkRequestService workRequests;

    @Inject
    public NosqlController(NosqlService service, CompartmentValidator compartments,
                           WorkRequestService workRequests) {
        this.service = service;
        this.compartments = compartments;
        this.workRequests = workRequests;
    }

    // ── Tables ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/tables")
    public Response createTable(Map<String, Object> body) {
        String compartmentId = NosqlWire.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return NosqlWire.accepted(service.createTable(NosqlWire.str(body, "name"), compartmentId,
                    NosqlWire.str(body, "ddlStatement"), NosqlWire.limits(body),
                    NosqlWire.bool(body, "isAutoReclaimable"), NosqlWire.stringMap(body, "freeformTags"),
                    NosqlWire.definedTags(body)));
        }
    }

    @GET
    @Path("/tables")
    public Response listTables(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("name") String name,
                               @QueryParam("lifecycleState") String lifecycleState,
                               @QueryParam("sortBy") String sortBy,
                               @QueryParam("sortOrder") String sortOrder,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
        List<StoredNosqlTable> all = service.listTables(compartmentId, name, lifecycleState, sortBy, sortOrder);
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("autoReclaimableTables", all.stream()
                .filter(t -> t.isAutoReclaimable() && !"DELETED".equals(t.getLifecycleState())).count());
        counters.put("onDemandCapacityTables", all.stream()
                .filter(t -> "ON_DEMAND".equals(t.getTableLimits().getCapacityMode())
                        && !"DELETED".equals(t.getLifecycleState())).count());
        counters.put("availableReplicationRegions", List.of());
        return NosqlWire.page(all.stream().map(NosqlWire::tableSummary).toList(), limit, page, counters);
    }

    @GET
    @Path("/tables/{tableNameOrId}")
    public Response getTable(@PathParam("tableNameOrId") String tableNameOrId,
                             @QueryParam("compartmentId") String compartmentId) {
        checkQueryCompartment(compartmentId);
        StoredNosqlTable table = service.getTable(tableNameOrId, compartmentId);
        return Response.ok(NosqlWire.table(table)).header("etag", table.getEtag()).build();
    }

    @PUT
    @Path("/tables/{tableNameOrId}")
    public Response updateTable(@PathParam("tableNameOrId") String tableNameOrId,
                                @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        String compartmentId = NosqlWire.str(body, "compartmentId");
        checkBodyCompartment(compartmentId);
        return NosqlWire.accepted(service.updateTable(tableNameOrId, compartmentId,
                NosqlWire.str(body, "ddlStatement"), NosqlWire.limits(body),
                NosqlWire.stringMap(body, "freeformTags"), NosqlWire.definedTags(body), ifMatch));
    }

    @DELETE
    @Path("/tables/{tableNameOrId}")
    public Response deleteTable(@PathParam("tableNameOrId") String tableNameOrId,
                                @QueryParam("compartmentId") String compartmentId,
                                @QueryParam("isIfExists") Boolean isIfExists,
                                @HeaderParam("if-match") String ifMatch) {
        checkQueryCompartment(compartmentId);
        return NosqlWire.accepted(service.deleteTable(tableNameOrId, compartmentId, isIfExists, ifMatch));
    }

    @POST
    @Path("/tables/{tableNameOrId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("tableNameOrId") String tableNameOrId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        String from = NosqlWire.str(body, "fromCompartmentId");
        String to = NosqlWire.str(body, "toCompartmentId");
        checkBodyCompartment(from);
        try (CompartmentValidator.Use ignored = compartments.useInBody(to)) {
            return NosqlWire.accepted(service.changeCompartment(tableNameOrId, from, to, ifMatch));
        }
    }

    /** ListTableUsage: the emulator meters nothing, so there are no usage records. */
    @GET
    @Path("/tables/{tableNameOrId}/usage")
    public Response listTableUsage(@PathParam("tableNameOrId") String tableNameOrId,
                                   @QueryParam("compartmentId") String compartmentId,
                                   @QueryParam("limit") Integer limit,
                                   @QueryParam("page") String page) {
        checkQueryCompartment(compartmentId);
        service.getTable(tableNameOrId, compartmentId);
        return NosqlWire.page(List.of(), limit, page, Map.of());
    }

    // ── Indexes ────────────────────────────────────────────────────────────────

    @POST
    @Path("/tables/{tableNameOrId}/indexes")
    public Response createIndex(@PathParam("tableNameOrId") String tableNameOrId,
                                Map<String, Object> body) {
        String compartmentId = NosqlWire.str(body, "compartmentId");
        checkBodyCompartment(compartmentId);
        return NosqlWire.accepted(service.createIndex(tableNameOrId, compartmentId, NosqlWire.str(body, "name"),
                NosqlWire.indexKeys(body), NosqlWire.bool(body, "isIfNotExists")));
    }

    @GET
    @Path("/tables/{tableNameOrId}/indexes")
    public Response listIndexes(@PathParam("tableNameOrId") String tableNameOrId,
                                @QueryParam("compartmentId") String compartmentId,
                                @QueryParam("name") String name,
                                @QueryParam("lifecycleState") String lifecycleState,
                                @QueryParam("sortBy") String sortBy,
                                @QueryParam("sortOrder") String sortOrder,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        checkQueryCompartment(compartmentId);
        StoredNosqlTable table = service.getTable(tableNameOrId, compartmentId);
        List<Map<String, Object>> items = service.listIndexes(table, name, lifecycleState, sortBy, sortOrder)
                .stream().map(NosqlWire::indexSummary).toList();
        return NosqlWire.page(items, limit, page, Map.of());
    }

    @GET
    @Path("/tables/{tableNameOrId}/indexes/{indexName}")
    public Response getIndex(@PathParam("tableNameOrId") String tableNameOrId,
                             @PathParam("indexName") String indexName,
                             @QueryParam("compartmentId") String compartmentId) {
        checkQueryCompartment(compartmentId);
        StoredNosqlTable table = service.getTable(tableNameOrId, compartmentId);
        Index index = service.getIndex(table, indexName);
        return Response.ok(NosqlWire.index(table, index)).header("etag", index.getEtag()).build();
    }

    @DELETE
    @Path("/tables/{tableNameOrId}/indexes/{indexName}")
    public Response deleteIndex(@PathParam("tableNameOrId") String tableNameOrId,
                                @PathParam("indexName") String indexName,
                                @QueryParam("compartmentId") String compartmentId,
                                @QueryParam("isIfExists") Boolean isIfExists,
                                @HeaderParam("if-match") String ifMatch) {
        checkQueryCompartment(compartmentId);
        return NosqlWire.accepted(service.deleteIndex(tableNameOrId, compartmentId, indexName, isIfExists,
                ifMatch));
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> items = workRequests.list(NosqlService.WR_SERVICE, compartmentId).stream()
                .map(StoredWorkRequest::toWire).toList();
        return NosqlWire.page(items, limit, page, Map.of());
    }

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequests.get(NosqlService.WR_SERVICE, workRequestId).toWire()).build();
    }

    @DELETE
    @Path("/workRequests/{workRequestId}")
    public Response deleteWorkRequest(@PathParam("workRequestId") String workRequestId) {
        service.cancelWorkRequest(workRequestId);
        return Response.accepted().build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        workRequests.get(NosqlService.WR_SERVICE, workRequestId);
        return NosqlWire.page(List.of(), limit, page, Map.of());
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        workRequests.get(NosqlService.WR_SERVICE, workRequestId);
        return NosqlWire.page(List.of(), limit, page, Map.of());
    }

    private void checkQueryCompartment(String compartmentId) {
        if (compartmentId != null) {
            compartments.requireInQuery(compartmentId);
        }
    }

    private void checkBodyCompartment(String compartmentId) {
        if (compartmentId != null) {
            compartments.requireInBody(compartmentId);
        }
    }
}
