package io.floci.oci.services.containerinstances;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.containerinstances.model.StoredContainer;
import io.floci.oci.services.containerinstances.model.StoredContainerInstance;
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

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Container Instances, {@code /20210415} (oci-go-sdk/containerinstances, client BasePath
 * {@code 20210415}). Create answers 200 with the ContainerInstance, its {@code etag} and the
 * {@code opc-work-request-id}; every other write answers 202 with only the work request. Lists are
 * {@code {"items": [...]}} collections with {@code opc-next-page}. RetrieveLogs is a binary body,
 * served as text.
 */
@Path("/20210415")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContainerInstancesController {

    private static final String WORK_REQUEST_ID = "opc-work-request-id";
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");

    private final ContainerInstancesService service;
    private final WorkRequestService workRequests;
    private final CompartmentValidator compartments;

    @Inject
    public ContainerInstancesController(ContainerInstancesService service, WorkRequestService workRequests,
                                        CompartmentValidator compartments) {
        this.service = service;
        this.workRequests = workRequests;
        this.compartments = compartments;
    }

    // ── Container instances ────────────────────────────────────────────────────

    @POST
    @Path("/containerInstances")
    public Response create(Map<String, Object> body) {
        String compartmentId = ContainerInstancesRequests.string(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            ContainerInstancesService.Created created = service.create(body);
            return Response.ok(instanceWire(created.instance(), service.containersOf(created.instance())))
                    .header("etag", created.instance().getEtag())
                    .header(WORK_REQUEST_ID, created.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/containerInstances/{id}")
    public Response get(@PathParam("id") String id) {
        StoredContainerInstance instance = service.get(id);
        return Response.ok(instanceWire(instance, service.containersOf(instance)))
                .header("etag", instance.getEtag()).build();
    }

    @GET
    @Path("/containerInstances")
    public Response list(@QueryParam("compartmentId") String compartmentId,
                         @QueryParam("lifecycleState") String lifecycleState,
                         @QueryParam("displayName") String displayName,
                         @QueryParam("availabilityDomain") String availabilityDomain,
                         @QueryParam("sortBy") String sortBy,
                         @QueryParam("sortOrder") String sortOrder,
                         @QueryParam("limit") Integer limit,
                         @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        return collection(service.list(compartmentId, lifecycleState, displayName, availabilityDomain, sortBy,
                sortOrder).stream().map(ContainerInstancesController::instanceSummary).toList(), limit, page);
    }

    @PUT
    @Path("/containerInstances/{id}")
    public Response update(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch,
                           Map<String, Object> body) {
        return accepted(service.update(id, body, ifMatch));
    }

    @DELETE
    @Path("/containerInstances/{id}")
    public Response delete(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch) {
        return accepted(service.delete(id, ifMatch));
    }

    @POST
    @Path("/containerInstances/{id}/actions/start")
    @Consumes(MediaType.WILDCARD)
    public Response start(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch) {
        return accepted(service.start(id, ifMatch));
    }

    @POST
    @Path("/containerInstances/{id}/actions/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stop(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch) {
        return accepted(service.stop(id, ifMatch));
    }

    @POST
    @Path("/containerInstances/{id}/actions/restart")
    @Consumes(MediaType.WILDCARD)
    public Response restart(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch) {
        return accepted(service.restart(id, ifMatch));
    }

    @POST
    @Path("/containerInstances/{id}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        String compartmentId = ContainerInstancesRequests.string(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            return accepted(service.changeCompartment(id, compartmentId, ifMatch));
        }
    }

    @GET
    @Path("/containerInstanceShapes")
    public Response listShapes(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("availabilityDomain") String availabilityDomain,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        return collection(service.shapes(compartmentId, availabilityDomain).stream()
                .map(ContainerInstancesCatalog.Shape::toWire).toList(), limit, page);
    }

    // ── Containers ─────────────────────────────────────────────────────────────

    @GET
    @Path("/containers/{containerId}")
    public Response getContainer(@PathParam("containerId") String containerId) {
        StoredContainer container = service.getContainer(containerId);
        return Response.ok(containerWire(container)).header("etag", container.getEtag()).build();
    }

    @GET
    @Path("/containers")
    public Response listContainers(@QueryParam("compartmentId") String compartmentId,
                                   @QueryParam("containerInstanceId") String containerInstanceId,
                                   @QueryParam("lifecycleState") String lifecycleState,
                                   @QueryParam("displayName") String displayName,
                                   @QueryParam("availabilityDomain") String availabilityDomain,
                                   @QueryParam("sortBy") String sortBy,
                                   @QueryParam("sortOrder") String sortOrder,
                                   @QueryParam("limit") Integer limit,
                                   @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        return collection(service.listContainers(compartmentId, containerInstanceId, lifecycleState, displayName,
                availabilityDomain, sortBy, sortOrder).stream().map(ContainerInstancesController::containerSummary)
                .toList(), limit, page);
    }

    @PUT
    @Path("/containers/{containerId}")
    public Response updateContainer(@PathParam("containerId") String containerId,
                                    @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        return accepted(service.updateContainer(containerId, body, ifMatch));
    }

    @POST
    @Path("/containers/{containerId}/actions/retrieveLogs")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response retrieveLogs(@PathParam("containerId") String containerId,
                                 @QueryParam("isPrevious") Boolean isPrevious) {
        return Response.ok(service.logs(containerId).getBytes(StandardCharsets.UTF_8))
                .type(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"))
                .build();
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequestWire(workRequests.get(ContainerInstancesService.WR_SERVICE, workRequestId)))
                .build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("workRequestId") String workRequestId,
                                     @QueryParam("status") String status,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("availabilityDomain") String availabilityDomain,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        requireSortOrder(sortOrder);
        Comparator<StoredWorkRequest> order = Comparator.comparing(StoredWorkRequest::getTimeAccepted)
                .thenComparing(StoredWorkRequest::getId);
        if (!"ASC".equals(sortOrder)) {
            order = order.reversed();
        }
        return collection(workRequests.list(ContainerInstancesService.WR_SERVICE, compartmentId).stream()
                .filter(wr -> workRequestId == null || workRequestId.equals(wr.getId()))
                .filter(wr -> status == null || status.equals(wr.getStatus()))
                .filter(wr -> resourceId == null || wr.getResources().stream()
                        .anyMatch(resource -> resourceId.equals(resource.getIdentifier())))
                .sorted(order)
                .map(ContainerInstancesController::workRequestWire)
                .toList(), limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        StoredWorkRequest workRequest = workRequests.get(ContainerInstancesService.WR_SERVICE, workRequestId);
        List<Map<String, Object>> errors = workRequest.getErrors().stream().map(error -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("code", error.getCode());
            wire.put("message", error.getMessage());
            wire.put("timestamp", workRequest.getTimeFinished());
            return wire;
        }).toList();
        return collection(errors, limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        workRequests.get(ContainerInstancesService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    // ── Wire projection ────────────────────────────────────────────────────────

    /** ContainerInstance (container_instance.go). */
    static Map<String, Object> instanceWire(StoredContainerInstance instance, List<StoredContainer> containers) {
        Map<String, Object> json = instanceCommon(instance);
        json.put("containers", containers.stream().map(container -> {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("containerId", container.getId());
            ref.put("displayName", container.getDisplayName());
            return ref;
        }).toList());
        json.put("vnics", instance.getVnicIds().stream().map(vnicId -> Map.of("vnicId", vnicId)).toList());
        json.put("volumes", instance.getAttributes().getOrDefault("volumes", List.of()));
        json.put("imagePullSecrets", instance.getAttributes().getOrDefault("imagePullSecrets", List.of()));
        if (instance.getAttributes().get("dnsConfig") != null) {
            json.put("dnsConfig", instance.getAttributes().get("dnsConfig"));
        }
        return json;
    }

    /** ContainerInstanceSummary: no containers, VNICs, volumes or pull secrets. */
    static Map<String, Object> instanceSummary(StoredContainerInstance instance) {
        return instanceCommon(instance);
    }

    private static Map<String, Object> instanceCommon(StoredContainerInstance instance) {
        Map<String, Object> a = instance.getAttributes();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", instance.getId());
        json.put("displayName", instance.getDisplayName());
        json.put("compartmentId", instance.getCompartmentId());
        json.put("availabilityDomain", instance.getAvailabilityDomain());
        json.put("faultDomain", a.get("faultDomain"));
        json.put("lifecycleState", instance.getLifecycleState());
        if (instance.getLifecycleDetails() != null) {
            json.put("lifecycleDetails", instance.getLifecycleDetails());
        }
        json.put("containerCount", instance.getContainerIds().size());
        json.put("volumeCount", ContainerInstanceManager.mapList(a.get("volumes")).size());
        json.put("timeCreated", instance.getTimeCreated());
        json.put("timeUpdated", instance.getTimeUpdated());
        json.put("shape", a.get("shape"));
        json.put("shapeConfig", a.get("shapeConfig"));
        json.put("containerRestartPolicy", a.get("containerRestartPolicy"));
        json.put("gracefulShutdownTimeoutInSeconds", a.get("gracefulShutdownTimeoutInSeconds"));
        if (a.get("securityContext") != null) {
            json.put("securityContext", a.get("securityContext"));
        }
        json.put("tenantId", instance.getTenancyId());
        json.put("freeformTags", instance.getFreeformTags());
        json.put("definedTags", instance.getDefinedTags());
        json.put("systemTags", Map.of());
        return json;
    }

    /** Container (container.go); health checks carry a status floci-oci does not evaluate. */
    static Map<String, Object> containerWire(StoredContainer container) {
        Map<String, Object> json = containerCommon(container);
        Map<String, Object> a = container.getAttributes();
        for (String key : List.of("command", "arguments", "workingDirectory", "environmentVariables", "volumeMounts")) {
            if (a.get(key) != null) {
                json.put(key, a.get(key));
            }
        }
        if (a.get("healthChecks") != null) {
            json.put("healthChecks", ContainerInstanceManager.mapList(a.get("healthChecks")).stream().map(check -> {
                Map<String, Object> read = new LinkedHashMap<>(check);
                read.put("status", "UNKNOWN");
                return read;
            }).toList());
        }
        if (container.getExitCode() != null) {
            json.put("exitCode", container.getExitCode());
        }
        if (container.getTimeTerminated() != null) {
            json.put("timeTerminated", container.getTimeTerminated());
        }
        json.put("containerRestartAttemptCount", container.getContainerRestartAttemptCount());
        return json;
    }

    /** ContainerSummary. */
    static Map<String, Object> containerSummary(StoredContainer container) {
        return containerCommon(container);
    }

    private static Map<String, Object> containerCommon(StoredContainer container) {
        Map<String, Object> a = container.getAttributes();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", container.getId());
        json.put("displayName", container.getDisplayName());
        json.put("compartmentId", container.getCompartmentId());
        json.put("availabilityDomain", container.getAvailabilityDomain());
        json.put("faultDomain", a.get("faultDomain"));
        json.put("lifecycleState", container.getLifecycleState());
        if (container.getLifecycleDetails() != null) {
            json.put("lifecycleDetails", container.getLifecycleDetails());
        }
        json.put("timeCreated", container.getTimeCreated());
        json.put("timeUpdated", container.getTimeUpdated());
        json.put("containerInstanceId", container.getContainerInstanceId());
        json.put("imageUrl", container.getImageUrl());
        if (a.get("resourceConfig") != null) {
            json.put("resourceConfig", a.get("resourceConfig"));
        }
        json.put("isResourcePrincipalDisabled", a.getOrDefault("isResourcePrincipalDisabled", false));
        if (a.get("securityContext") != null) {
            json.put("securityContext", a.get("securityContext"));
        }
        json.put("freeformTags", container.getFreeformTags());
        json.put("definedTags", container.getDefinedTags());
        json.put("systemTags", Map.of());
        return json;
    }

    /** WorkRequest (work_request.go) has no errors field: they are served by ListWorkRequestErrors. */
    private static Map<String, Object> workRequestWire(StoredWorkRequest workRequest) {
        Map<String, Object> wire = workRequest.toWire();
        wire.remove("errors");
        return wire;
    }

    private static void requireSortOrder(String sortOrder) {
        if (sortOrder != null && !SORT_ORDERS.contains(sortOrder)) {
            throw OciException.invalidParameter("Invalid sortOrder: " + sortOrder);
        }
    }

    private static <T> Response collection(List<T> all, Integer limit, String page) {
        OciPage.Page<T> result = OciPage.paginate(all, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header(WORK_REQUEST_ID, workRequestId).build();
    }
}
