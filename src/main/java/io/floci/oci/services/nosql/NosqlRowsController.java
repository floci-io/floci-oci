package io.floci.oci.services.nosql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NoSQL data plane, {@code /20190828}: single-row put, get and delete, and SQL queries. A put
 * or delete whose condition fails is a {@code 200} result with a null {@code version} or
 * {@code isSuccess:false}, not an error.
 */
@Path("/20190828")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NosqlRowsController {

    private final NosqlService service;
    private final CompartmentValidator compartments;

    @Inject
    public NosqlRowsController(NosqlService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    @PUT
    @Path("/tables/{tableNameOrId}/rows")
    public Response updateRow(@PathParam("tableNameOrId") String tableNameOrId,
                              @HeaderParam("if-match") String ifMatch,
                              Map<String, Object> body) {
        String compartmentId = NosqlWire.str(body, "compartmentId");
        if (compartmentId != null) {
            compartments.requireInBody(compartmentId);
        }
        NosqlService.PutOutcome outcome = service.putRow(tableNameOrId, compartmentId,
                NosqlWire.map(body, "value"), NosqlWire.str(body, "option"),
                NosqlWire.bool(body, "isGetReturnRow"), NosqlWire.integer(body, "ttl"),
                NosqlWire.bool(body, "isTtlUseTableDefault"), NosqlWire.bool(body, "isExactMatch"), ifMatch);
        Map<String, Object> json = new LinkedHashMap<>();
        if (outcome.version() != null) {
            json.put("version", outcome.version());
        }
        if (outcome.existingVersion() != null) {
            json.put("existingVersion", outcome.existingVersion());
        }
        if (outcome.existingValue() != null) {
            json.put("existingValue", outcome.existingValue());
        }
        if (outcome.generatedValue() != null) {
            json.put("generatedValue", outcome.generatedValue());
        }
        json.put("usage", NosqlWire.usage(outcome.version() == null ? 1 : 0, outcome.version() == null ? 0 : 1));
        Response.ResponseBuilder builder = Response.ok(json);
        if (outcome.version() != null) {
            builder.header("etag", outcome.version());
        }
        return builder.build();
    }

    @GET
    @Path("/tables/{tableNameOrId}/rows")
    public Response getRow(@PathParam("tableNameOrId") String tableNameOrId,
                           @QueryParam("key") List<String> key,
                           @QueryParam("compartmentId") String compartmentId) {
        checkQueryCompartment(compartmentId);
        Optional<NosqlService.RowRead> row = service.getRow(tableNameOrId, compartmentId, key);
        Map<String, Object> json = new LinkedHashMap<>();
        if (row.isEmpty()) {
            json.put("usage", NosqlWire.usage(1, 0));
            return Response.ok(json).build();
        }
        json.put("value", row.get().value());
        if (row.get().expiresAt() != null) {
            json.put("timeOfExpiration", Instant.ofEpochMilli(row.get().expiresAt()).toString());
        }
        json.put("usage", NosqlWire.usage(1, 0));
        return Response.ok(json).header("etag", row.get().version()).build();
    }

    @DELETE
    @Path("/tables/{tableNameOrId}/rows")
    public Response deleteRow(@PathParam("tableNameOrId") String tableNameOrId,
                              @QueryParam("key") List<String> key,
                              @QueryParam("compartmentId") String compartmentId,
                              @QueryParam("isGetReturnRow") Boolean isGetReturnRow,
                              @HeaderParam("if-match") String ifMatch) {
        checkQueryCompartment(compartmentId);
        NosqlService.DeleteOutcome outcome = service.deleteRow(tableNameOrId, compartmentId, key,
                isGetReturnRow, ifMatch);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("isSuccess", outcome.success());
        if (outcome.existingVersion() != null) {
            json.put("existingVersion", outcome.existingVersion());
        }
        if (outcome.existingValue() != null) {
            json.put("existingValue", outcome.existingValue());
        }
        json.put("usage", NosqlWire.usage(1, outcome.success() ? 1 : 0));
        return Response.ok(json).build();
    }

    @POST
    @Path("/query")
    public Response query(@QueryParam("limit") Integer limit,
                          @QueryParam("page") String page,
                          Map<String, Object> body) {
        String compartmentId = NosqlWire.str(body, "compartmentId");
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInBody(compartmentId);
        NosqlService.QueryOutcome outcome = service.query(compartmentId, NosqlWire.str(body, "statement"),
                NosqlWire.bool(body, "isPrepared"), NosqlWire.map(body, "variables"));
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(outcome.items(), limit, page);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("items", result.items());
        json.put("usage", NosqlWire.usage(outcome.rowsRead(), 0));
        Response.ResponseBuilder builder = Response.ok(json);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/query/prepare")
    public Response prepareStatement(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("statement") String statement,
                                     @QueryParam("isGetQueryPlan") Boolean isGetQueryPlan) {
        requireQueryCompartment(compartmentId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("statement", service.prepare(compartmentId, statement));
        json.put("usage", NosqlWire.usage(0, 0));
        return Response.ok(json).build();
    }

    @GET
    @Path("/query/summarize")
    public Response summarizeStatement(@QueryParam("compartmentId") String compartmentId,
                                       @QueryParam("statement") String statement) {
        requireQueryCompartment(compartmentId);
        return Response.ok(service.summarize(statement)).build();
    }

    private void requireQueryCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private void checkQueryCompartment(String compartmentId) {
        if (compartmentId != null) {
            compartments.requireInQuery(compartmentId);
        }
    }
}
