package io.floci.oci.services.functions;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * OCI Functions management plane, API version {@code 20260325}, which every current SDK and the
 * OCI CLI call. Same applications and functions as {@link FunctionsManagementController}, with
 * this version's contract:
 * <ul>
 *   <li>every create, update, delete and change-compartment reports an {@code opc-work-request-id};
 *   create still returns the body and {@code etag}, the others return no body;</li>
 *   <li>a function names its image in the polymorphic {@code sourceDetails}, and has no top-level
 *   {@code image}/{@code imageDigest};</li>
 *   <li>the runtime and pre-built function catalogs.</li>
 * </ul>
 * The SDKs do not declare status codes; 200 for create and 202 for the operations that answer
 * with only a work request follow the rest of floci-oci.
 */
@Path("/20260325")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FunctionsManagementV2Controller {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";
    private static final Set<String> SORT_BY = Set.of("timeCreated", "id", "displayName");
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Set<String> LIFECYCLE_STATES = Set.of("CREATING", "ACTIVE", "INACTIVE", "UPDATING",
            "DELETING", "DELETED", "FAILED");

    private final FunctionsService service;
    private final CompartmentValidator compartments;
    private final OciContext ociContext;

    @Inject
    public FunctionsManagementV2Controller(FunctionsService service, CompartmentValidator compartments,
                                           OciContext ociContext) {
        this.service = service;
        this.compartments = compartments;
        this.ociContext = ociContext;
    }

    // ── Applications ───────────────────────────────────────────────────────────

    @POST
    @Path("/applications")
    public Response createApplication(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(FunctionsRequests.str(body, "compartmentId"))) {
            StoredApplication app = service.createApplication(
                    FunctionsRequests.str(body, "compartmentId"), FunctionsRequests.str(body, "displayName"),
                    FunctionsRequests.stringList(body, "subnetIds"), FunctionsRequests.stringMap(body, "config"),
                    FunctionsRequests.str(body, "shape"), FunctionsRequests.stringMap(body, "freeformTags"),
                    FunctionsRequests.definedTags(body),
                    FunctionsRequests.settings(body, FunctionsRequests.APPLICATION_SETTINGS));
            String workRequestId = applicationWorkRequest("CREATE_APPLICATION", "CREATED", app);
            return Response.ok(FunctionsWire.application(app, true))
                    .header("etag", app.getEtag())
                    .header(WORK_REQUEST_ID, workRequestId)
                    .build();
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
                                     @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        FunctionsRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        List<StoredApplication> applications = service.listApplications(compartmentId, displayName, id).stream()
                .filter(a -> lifecycleState == null || lifecycleState.equals(a.getLifecycleState()))
                .toList();
        List<Map<String, Object>> summaries = sorted(applications, sortBy, sortOrder, StoredApplication::getTimeCreated,
                StoredApplication::getId, StoredApplication::getDisplayName).stream()
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
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"),
                FunctionsRequests.definedTags(body),
                FunctionsRequests.settings(body, FunctionsRequests.APPLICATION_SETTINGS), ifMatch);
        return accepted(applicationWorkRequest("UPDATE_APPLICATION", "UPDATED", app));
    }

    @DELETE
    @Path("/applications/{applicationId}")
    public Response deleteApplication(@PathParam("applicationId") String applicationId,
                                      @HeaderParam("if-match") String ifMatch) {
        StoredApplication app = service.getApplication(applicationId);
        service.deleteApplication(applicationId, ifMatch);
        return accepted(applicationWorkRequest("DELETE_APPLICATION", "DELETED", app));
    }

    /** The SDK's operation types have no change-compartment value, so it reports UPDATE_APPLICATION. */
    @POST
    @Path("/applications/{applicationId}/actions/changeCompartment")
    public Response changeApplicationCompartment(@PathParam("applicationId") String applicationId,
                                                 @HeaderParam("if-match") String ifMatch,
                                                 Map<String, Object> body) {
        String compartmentId = FunctionsRequests.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeApplicationCompartment(applicationId, compartmentId, ifMatch);
            return accepted(applicationWorkRequest("UPDATE_APPLICATION", "UPDATED",
                    service.getApplication(applicationId)));
        }
    }

    // ── Functions ──────────────────────────────────────────────────────────────

    @POST
    @Path("/functions")
    public Response createFunction(Map<String, Object> body) {
        Map<String, Object> source = FunctionsRequests.map(body, "sourceDetails");
        if (source == null) {
            throw OciException.missingParameter("Missing required parameter: sourceDetails");
        }
        ContainerImage image = containerImage(source, true);
        StoredFunction fn = service.createFunction(
                FunctionsRequests.str(body, "applicationId"), FunctionsRequests.str(body, "displayName"),
                image.image(), image.digest(),
                FunctionsRequests.longValue(body, "memoryInMBs"), FunctionsRequests.integer(body, "timeoutInSeconds"),
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"),
                FunctionsRequests.definedTags(body),
                FunctionsRequests.settings(body, FunctionsRequests.FUNCTION_SETTINGS));
        String workRequestId = functionWorkRequest("CREATE_FUNCTION", "CREATED", fn);
        return Response.ok(FunctionsWire.function(fn, true, service.invokeEndpoint(), true))
                .header("etag", fn.getEtag())
                .header(WORK_REQUEST_ID, workRequestId)
                .build();
    }

    @GET
    @Path("/functions/{functionId}")
    public Response getFunction(@PathParam("functionId") String functionId) {
        StoredFunction fn = service.getFunction(functionId);
        return Response.ok(FunctionsWire.function(fn, true, service.invokeEndpoint(), true))
                .header("etag", fn.getEtag())
                .build();
    }

    @GET
    @Path("/functions")
    public Response listFunctions(@QueryParam("applicationId") String applicationId,
                                  @QueryParam("displayName") String displayName,
                                  @QueryParam("id") String id,
                                  @QueryParam("lifecycleState") String lifecycleState,
                                  @QueryParam("sortBy") String sortBy,
                                  @QueryParam("sortOrder") String sortOrder,
                                  @QueryParam("limit") Integer limit,
                                  @QueryParam("page") String page) {
        FunctionsRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        List<StoredFunction> functions = service.listFunctions(applicationId, displayName, id).stream()
                .filter(f -> lifecycleState == null || lifecycleState.equals(f.getLifecycleState()))
                .toList();
        List<Map<String, Object>> summaries = sorted(functions, sortBy, sortOrder, StoredFunction::getTimeCreated,
                StoredFunction::getId, StoredFunction::getDisplayName).stream()
                .map(f -> FunctionsWire.function(f, false, service.invokeEndpoint(), true))
                .toList();
        return FunctionsRequests.paged(summaries, limit, page, false);
    }

    @PUT
    @Path("/functions/{functionId}")
    public Response updateFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch,
                                   Map<String, Object> body) {
        Map<String, Object> source = FunctionsRequests.map(body, "sourceDetails");
        ContainerImage image = source != null ? containerImage(source, false) : new ContainerImage(null, null);
        StoredFunction fn = service.updateFunction(functionId, image.image(), image.digest(),
                FunctionsRequests.longValue(body, "memoryInMBs"), FunctionsRequests.integer(body, "timeoutInSeconds"),
                FunctionsRequests.stringMap(body, "config"), FunctionsRequests.stringMap(body, "freeformTags"),
                FunctionsRequests.definedTags(body),
                FunctionsRequests.settings(body, FunctionsRequests.FUNCTION_SETTINGS), ifMatch);
        return accepted(functionWorkRequest("UPDATE_FUNCTION", "UPDATED", fn));
    }

    @DELETE
    @Path("/functions/{functionId}")
    public Response deleteFunction(@PathParam("functionId") String functionId,
                                   @HeaderParam("if-match") String ifMatch) {
        StoredFunction fn = service.getFunction(functionId);
        service.deleteFunction(functionId, ifMatch);
        return accepted(functionWorkRequest("DELETE_FUNCTION", "DELETED", fn));
    }

    // ── Catalogs ───────────────────────────────────────────────────────────────

    @GET
    @Path("/functionsRuntimes")
    public Response listFunctionsRuntimes(@QueryParam("functionsRuntimeId") String functionsRuntimeId,
                                          @QueryParam("name") String name,
                                          @QueryParam("nameContains") String nameContains,
                                          @QueryParam("nameStartsWith") String nameStartsWith,
                                          @QueryParam("os") String os,
                                          @QueryParam("language") String language,
                                          @QueryParam("lifecycleState") String lifecycleState,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        List<Map<String, Object>> items = FunctionsCatalog.runtimes(ociContext.realm(), ociContext.regionCode())
                .stream()
                .filter(r -> functionsRuntimeId == null || functionsRuntimeId.equals(r.get("id")))
                .filter(r -> name == null || name.equals(r.get("name")))
                .filter(r -> nameContains == null || r.get("name").toString().contains(nameContains))
                .filter(r -> nameStartsWith == null || r.get("name").toString().startsWith(nameStartsWith))
                .filter(r -> os == null || os.equals(r.get("os")))
                .filter(r -> language == null || language.equals(r.get("language")))
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.get("lifecycleState")))
                .toList();
        return FunctionsRequests.paged(items, limit, page, true);
    }

    @GET
    @Path("/functionsRuntimes/{functionsRuntimeId}")
    public Response getFunctionsRuntime(@PathParam("functionsRuntimeId") String functionsRuntimeId) {
        return catalogEntry(FunctionsCatalog.runtimes(ociContext.realm(), ociContext.regionCode()),
                functionsRuntimeId, "functions runtime");
    }

    @GET
    @Path("/functionsRuntimeVersions")
    public Response listFunctionsRuntimeVersions(@QueryParam("functionsRuntimeId") String functionsRuntimeId,
                                                 @QueryParam("functionsRuntimeName") String functionsRuntimeName,
                                                 @QueryParam("functionsRuntimeVersionId") String versionId,
                                                 @QueryParam("displayName") String displayName,
                                                 @QueryParam("lifecycleState") String lifecycleState,
                                                 @QueryParam("limit") Integer limit,
                                                 @QueryParam("page") String page) {
        List<Map<String, Object>> runtimes = FunctionsCatalog.runtimes(ociContext.realm(), ociContext.regionCode());
        List<Map<String, Object>> items = FunctionsCatalog.runtimeVersions(ociContext.realm(), ociContext.regionCode())
                .stream()
                .filter(v -> functionsRuntimeId == null || functionsRuntimeId.equals(v.get("functionsRuntimeId")))
                .filter(v -> functionsRuntimeName == null || runtimes.stream().anyMatch(r ->
                        functionsRuntimeName.equals(r.get("name")) && r.get("id").equals(v.get("functionsRuntimeId"))))
                .filter(v -> versionId == null || versionId.equals(v.get("id")))
                .filter(v -> displayName == null || displayName.equals(v.get("displayName")))
                .filter(v -> lifecycleState == null || lifecycleState.equals(v.get("lifecycleState")))
                .toList();
        return FunctionsRequests.paged(items, limit, page, true);
    }

    @GET
    @Path("/functionsRuntimeVersions/{functionsRuntimeVersionId}")
    public Response getFunctionsRuntimeVersion(
            @PathParam("functionsRuntimeVersionId") String functionsRuntimeVersionId) {
        return catalogEntry(FunctionsCatalog.runtimeVersions(ociContext.realm(), ociContext.regionCode()),
                functionsRuntimeVersionId, "functions runtime version");
    }

    /** No pre-built functions are published: floci-oci runs container images only. */
    @GET
    @Path("/pbfListings")
    public Response listPbfListings(@QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        return FunctionsRequests.paged(List.of(), limit, page, true);
    }

    @GET
    @Path("/pbfListings/triggers")
    public Response listTriggers(@QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        return FunctionsRequests.paged(List.of(), limit, page, true);
    }

    @GET
    @Path("/pbfListings/{pbfListingId}")
    public Response getPbfListing(@PathParam("pbfListingId") String pbfListingId) {
        throw notFound("pre-built function listing", pbfListingId);
    }

    @GET
    @Path("/pbfListingVersions")
    public Response listPbfListingVersions(@QueryParam("pbfListingId") String pbfListingId,
                                           @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        if (pbfListingId == null || pbfListingId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: pbfListingId");
        }
        return FunctionsRequests.paged(List.of(), limit, page, true);
    }

    @GET
    @Path("/pbfListingVersions/{pbfListingVersionId}")
    public Response getPbfListingVersion(@PathParam("pbfListingVersionId") String pbfListingVersionId) {
        throw notFound("pre-built function listing version", pbfListingVersionId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private record ContainerImage(String image, String digest) {
    }

    /**
     * The image of a {@code sourceDetails}. CONTAINER_IMAGE is the only source floci-oci can run:
     * ARCHIVE (code plus a runtime) and PRE_BUILT_FUNCTIONS are 501 MethodNotImplemented, and a
     * pre-built function is not a valid update source at all in the SDK.
     */
    private static ContainerImage containerImage(Map<String, Object> source, boolean create) {
        String sourceType = FunctionsRequests.str(source, "sourceType");
        if (sourceType == null) {
            throw OciException.missingParameter("Missing required parameter: sourceDetails.sourceType");
        }
        return switch (sourceType) {
            case "CONTAINER_IMAGE" -> {
                String image = FunctionsRequests.str(source, "image");
                if (create && image == null) {
                    throw OciException.missingParameter("Missing required parameter: sourceDetails.image");
                }
                yield new ContainerImage(image, FunctionsRequests.str(source, "imageDigest"));
            }
            case "ARCHIVE" -> throw OciException.notImplemented(
                    "Functions from an ARCHIVE source are not supported by floci-oci: use a CONTAINER_IMAGE");
            case "PRE_BUILT_FUNCTIONS" -> {
                if (!create) {
                    throw OciException.invalidParameter("sourceType PRE_BUILT_FUNCTIONS cannot be used to update a function");
                }
                throw OciException.notImplemented(
                        "Pre-built functions are not supported by floci-oci: use a CONTAINER_IMAGE");
            }
            default -> throw OciException.invalidParameter("Invalid sourceDetails.sourceType: " + sourceType);
        };
    }

    /** Sorted by {@code sortBy} when given; otherwise in the service's order (oldest first). */
    private static <T> List<T> sorted(List<T> items, String sortBy, String sortOrder, Function<T, String> timeCreated,
                                      Function<T, String> id, Function<T, String> displayName) {
        FunctionsRequests.requireEnum(sortBy, SORT_BY, "sortBy");
        FunctionsRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        if (sortBy == null && sortOrder == null) {
            return items;
        }
        Function<T, String> key = switch (sortBy != null ? sortBy : "timeCreated") {
            case "id" -> id;
            case "displayName" -> displayName;
            default -> timeCreated;
        };
        Comparator<T> order = Comparator.comparing(key, Comparator.nullsFirst(Comparator.naturalOrder()));
        return items.stream().sorted("DESC".equals(sortOrder) ? order.reversed() : order).toList();
    }

    private String applicationWorkRequest(String operationType, String actionType, StoredApplication app) {
        return service.workRequest(operationType, app.getCompartmentId(), "functionsApplication", actionType,
                app.getId(), "/20260325/applications/" + app.getId());
    }

    private String functionWorkRequest(String operationType, String actionType, StoredFunction fn) {
        return service.workRequest(operationType, fn.getCompartmentId(), "functionsFunction", actionType,
                fn.getId(), "/20260325/functions/" + fn.getId());
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header(WORK_REQUEST_ID, workRequestId).build();
    }

    private static Response catalogEntry(List<Map<String, Object>> entries, String id, String type) {
        return entries.stream()
                .filter(entry -> id.equals(entry.get("id")))
                .findFirst()
                .map(entry -> Response.ok(entry).build())
                .orElseThrow(() -> notFound(type, id));
    }

    private static OciException notFound(String type, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + type + " " + id);
    }
}
