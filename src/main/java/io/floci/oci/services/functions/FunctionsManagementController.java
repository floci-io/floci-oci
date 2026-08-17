package io.floci.oci.services.functions;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Functions management plane — {@code /20181201/applications} and
 * {@code /20181201/functions}. No work requests anywhere: lifecycle states are polled.
 * Lists are bare JSON arrays; note the exact {@code memoryInMBs} casing.
 */
@Path("/20181201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FunctionsManagementController {

    private final FunctionsService service;

    @Inject
    public FunctionsManagementController(FunctionsService service) {
        this.service = service;
    }

    // ── Applications ───────────────────────────────────────────────────────────

    @POST
    @Path("/applications")
    public Response createApplication(Map<String, Object> body) {
        StoredApplication app = service.createApplication(
                str(body, "compartmentId"), str(body, "displayName"),
                stringList(body, "subnetIds"), stringMap(body, "config"),
                str(body, "shape"), stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(applicationJson(app, true)).header("etag", app.getEtag()).build();
    }

    @GET
    @Path("/applications/{applicationId}")
    public Response getApplication(@PathParam("applicationId") String applicationId) {
        StoredApplication app = service.getApplication(applicationId);
        return Response.ok(applicationJson(app, true)).header("etag", app.getEtag()).build();
    }

    @GET
    @Path("/applications")
    public Response listApplications(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("displayName") String displayName,
                                     @QueryParam("id") String id,
                                     @QueryParam("lifecycleState") String lifecycleState,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listApplications(compartmentId, displayName, id)
                .stream()
                .filter(a -> lifecycleState == null || lifecycleState.equals(a.getLifecycleState()))
                .map(a -> applicationJson(a, false))
                .toList();
        return paged(summaries, limit, page);
    }

    @PUT
    @Path("/applications/{applicationId}")
    public Response updateApplication(@PathParam("applicationId") String applicationId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        StoredApplication app = service.updateApplication(applicationId,
                stringMap(body, "config"), stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(applicationJson(app, true)).header("etag", app.getEtag()).build();
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
        service.changeApplicationCompartment(applicationId, str(body, "compartmentId"), ifMatch);
        return Response.ok().build();
    }

    // ── Functions ──────────────────────────────────────────────────────────────

    @POST
    @Path("/functions")
    public Response createFunction(Map<String, Object> body) {
        StoredFunction fn = service.createFunction(
                str(body, "applicationId"), str(body, "displayName"), str(body, "image"),
                longValue(body, "memoryInMBs"), integer(body, "timeoutInSeconds"),
                stringMap(body, "config"), stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(functionJson(fn, true)).header("etag", fn.getEtag()).build();
    }

    @GET
    @Path("/functions/{functionId}")
    public Response getFunction(@PathParam("functionId") String functionId) {
        StoredFunction fn = service.getFunction(functionId);
        return Response.ok(functionJson(fn, true)).header("etag", fn.getEtag()).build();
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
                .map(f -> functionJson(f, false))
                .toList();
        return paged(summaries, limit, page);
    }

    @PUT
    @Path("/functions/{functionId}")
    public Response updateFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch,
                                   Map<String, Object> body) {
        StoredFunction fn = service.updateFunction(functionId, str(body, "image"),
                longValue(body, "memoryInMBs"), integer(body, "timeoutInSeconds"),
                stringMap(body, "config"), stringMap(body, "freeformTags"),
                definedTags(body), ifMatch);
        return Response.ok(functionJson(fn, true)).header("etag", fn.getEtag()).build();
    }

    @DELETE
    @Path("/functions/{functionId}")
    public Response deleteFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch) {
        service.deleteFunction(functionId, ifMatch);
        return Response.noContent().build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    /** Application; the summary variant drops config + syslogUrl. */
    private static Map<String, Object> applicationJson(StoredApplication app, boolean full) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", app.getId());
        json.put("compartmentId", app.getCompartmentId());
        json.put("displayName", app.getDisplayName());
        json.put("lifecycleState", app.getLifecycleState());
        json.put("subnetIds", app.getSubnetIds());
        json.put("shape", app.getShape());
        json.put("timeCreated", app.getTimeCreated());
        json.put("timeUpdated", app.getTimeUpdated());
        if (full) {
            json.put("config", app.getConfig());
        }
        if (app.getFreeformTags() != null) {
            json.put("freeformTags", app.getFreeformTags());
        }
        if (app.getDefinedTags() != null) {
            json.put("definedTags", app.getDefinedTags());
        }
        return json;
    }

    /** Function; the summary variant drops config. Exact casing: memoryInMBs. */
    private Map<String, Object> functionJson(StoredFunction fn, boolean full) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", fn.getId());
        json.put("displayName", fn.getDisplayName());
        json.put("applicationId", fn.getApplicationId());
        json.put("compartmentId", fn.getCompartmentId());
        json.put("lifecycleState", fn.getLifecycleState());
        json.put("image", fn.getImage());
        json.put("imageDigest", fn.getImageDigest());
        json.put("shape", fn.getShape());
        json.put("memoryInMBs", fn.getMemoryInMBs());
        json.put("timeoutInSeconds", fn.getTimeoutInSeconds());
        json.put("invokeEndpoint", service.invokeEndpoint());
        json.put("timeCreated", fn.getTimeCreated());
        json.put("timeUpdated", fn.getTimeUpdated());
        if (full) {
            json.put("config", fn.getConfig());
        }
        if (fn.getFreeformTags() != null) {
            json.put("freeformTags", fn.getFreeformTags());
        }
        if (fn.getDefinedTags() != null) {
            json.put("definedTags", fn.getDefinedTags());
        }
        return json;
    }

    private static Response paged(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer integer(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.intValue() : null;
    }

    private static Long longValue(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.longValue() : null;
    }

    private static List<String> stringList(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return null;
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }
}
