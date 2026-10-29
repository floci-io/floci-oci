package io.floci.oci.services.functions;

import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;
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

import java.util.List;
import java.util.Map;

/**
 * OCI Functions management plane, API version {@code 20181201}: {@code /applications} and
 * {@code /functions}. No work requests: lifecycle states are polled. Lists are bare JSON arrays;
 * note the exact {@code memoryInMBs} casing. {@link FunctionsManagementV2Controller} serves the
 * same resources on {@code 20260325}.
 */
@Path("/20181201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FunctionsManagementController {

    private final FunctionsService service;
    private final CompartmentValidator compartments;

    @Inject
    public FunctionsManagementController(FunctionsService service,
                                         CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    // ── Applications ───────────────────────────────────────────────────────────

    @POST
    @Path("/applications")
    public Response createApplication(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(FunctionsRequests.str(body, "compartmentId"))) {
            StoredApplication app = service.createApplication(
                    FunctionsRequests.str(body, "compartmentId"), FunctionsRequests.str(body, "displayName"),
                    FunctionsRequests.stringList(body, "subnetIds"), FunctionsRequests.stringMap(body, "config"),
                    FunctionsRequests.str(body, "shape"), FunctionsRequests.stringMap(body, "freeformTags"), FunctionsRequests.definedTags(body),
                    FunctionsRequests.settings(body, FunctionsRequests.APPLICATION_SETTINGS));
            return Response.ok(FunctionsWire.application(app, true)).header("etag", app.getEtag()).build();
        }
    }

    @GET
    @Path("/applications/{applicationId}")
    public Response getApplication(@PathParam("applicationId") String applicationId) {
        StoredApplication app = service.getApplication(applicationId);
        return Response.ok(FunctionsWire.application(app, true)).header("etag", app.getEtag()).build();
    }

    @GET
    @Path("/applications")
    public Response listApplications(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("displayName") String displayName,
                                     @QueryParam("id") String id,
                                     @QueryParam("lifecycleState") String lifecycleState,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> summaries = service.listApplications(compartmentId, displayName, id)
                .stream()
                .filter(a -> lifecycleState == null || lifecycleState.equals(a.getLifecycleState()))
                .map(a -> FunctionsWire.application(a, false))
                .toList();
        return FunctionsRequests.paged(summaries, limit, page, false);
    }

    @PUT
    @Path("/applications/{applicationId}")
    public Response updateApplication(@PathParam("applicationId") String applicationId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        StoredApplication app = service.updateApplication(applicationId,
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"), FunctionsRequests.definedTags(body),
                FunctionsRequests.settings(body, FunctionsRequests.APPLICATION_SETTINGS), ifMatch);
        return Response.ok(FunctionsWire.application(app, true)).header("etag", app.getEtag()).build();
    }

    @DELETE
    @Path("/applications/{applicationId}")
    public Response deleteApplication(@PathParam("applicationId") String applicationId,
                                      @HeaderParam("if-match") String ifMatch) {
        service.deleteApplication(applicationId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/applications/{applicationId}/actions/changeCompartment")
    public Response changeApplicationCompartment(@PathParam("applicationId") String applicationId,
                                                 @HeaderParam("if-match") String ifMatch,
                                                 Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(FunctionsRequests.str(body, "compartmentId"))) {
            service.changeApplicationCompartment(applicationId, FunctionsRequests.str(body, "compartmentId"), ifMatch);
            return Response.ok().build();
        }
    }

    // ── Functions ──────────────────────────────────────────────────────────────

    @POST
    @Path("/functions")
    public Response createFunction(Map<String, Object> body) {
        StoredFunction fn = service.createFunction(
                FunctionsRequests.str(body, "applicationId"), FunctionsRequests.str(body, "displayName"), FunctionsRequests.str(body, "image"), null,
                FunctionsRequests.longValue(body, "memoryInMBs"), FunctionsRequests.integer(body, "timeoutInSeconds"),
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"), FunctionsRequests.definedTags(body),
                FunctionsRequests.settings(body, FunctionsRequests.FUNCTION_SETTINGS));
        return Response.ok(FunctionsWire.function(fn, true, service.invokeEndpoint(), false)).header("etag", fn.getEtag()).build();
    }

    @GET
    @Path("/functions/{functionId}")
    public Response getFunction(@PathParam("functionId") String functionId) {
        StoredFunction fn = service.getFunction(functionId);
        return Response.ok(FunctionsWire.function(fn, true, service.invokeEndpoint(), false)).header("etag", fn.getEtag()).build();
    }

    @GET
    @Path("/functions")
    public Response listFunctions(@QueryParam("applicationId") String applicationId,
                                  @QueryParam("displayName") String displayName,
                                  @QueryParam("id") String id,
                                  @QueryParam("lifecycleState") String lifecycleState,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listFunctions(applicationId, displayName, id)
                .stream()
                .filter(f -> lifecycleState == null || lifecycleState.equals(f.getLifecycleState()))
                .map(f -> FunctionsWire.function(f, false, service.invokeEndpoint(), false))
                .toList();
        return FunctionsRequests.paged(summaries, limit, page, false);
    }

    @PUT
    @Path("/functions/{functionId}")
    public Response updateFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch,
                                   Map<String, Object> body) {
        StoredFunction fn = service.updateFunction(functionId, FunctionsRequests.str(body, "image"), null,
                FunctionsRequests.longValue(body, "memoryInMBs"), FunctionsRequests.integer(body, "timeoutInSeconds"),
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"),
                FunctionsRequests.definedTags(body), FunctionsRequests.settings(body, FunctionsRequests.FUNCTION_SETTINGS), ifMatch);
        return Response.ok(FunctionsWire.function(fn, true, service.invokeEndpoint(), false)).header("etag", fn.getEtag()).build();
    }

    @DELETE
    @Path("/functions/{functionId}")
    public Response deleteFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch) {
        service.deleteFunction(functionId, ifMatch);
        return Response.noContent().build();
    }
}
