package io.floci.oci.services.queue;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.queue.model.StoredQueue;
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
 * OCI Queue control plane — {@code /20210201/queues…}. Every mutation is work-request
 * based: {@code 202} with an {@code opc-work-request-id} header and NO body. Lists are
 * wrapped in {@code {"items":[…]}} collections (unlike Identity/Object Storage).
 */
@Path("/20210201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueAdminController {

    private final QueueService service;
    private final WorkRequestService workRequests;

    @Inject
    public QueueAdminController(QueueService service, WorkRequestService workRequests) {
        this.service = service;
        this.workRequests = workRequests;
    }

    @POST
    @Path("/queues")
    public Response createQueue(Map<String, Object> body) {
        QueueService.WorkRequestOutcome outcome = service.createQueue(
                str(body, "displayName"), str(body, "compartmentId"),
                integer(body, "retentionInSeconds"), integer(body, "visibilityInSeconds"),
                integer(body, "timeoutInSeconds"), integer(body, "deadLetterQueueDeliveryCount"),
                integer(body, "channelConsumptionLimit"),
                stringMap(body, "freeformTags"), definedTags(body));
        return accepted(outcome);
    }

    @GET
    @Path("/queues/{queueId}")
    public Response getQueue(@PathParam("queueId") String queueId) {
        StoredQueue q = service.getQueue(queueId);
        return Response.ok(queueJson(q)).header("etag", q.getEtag()).build();
    }

    @GET
    @Path("/queues")
    public Response listQueues(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("displayName") String displayName,
                               @QueryParam("id") String id,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listQueues(compartmentId, displayName, id)
                .stream().map(QueueAdminController::queueSummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/queues/{queueId}")
    public Response updateQueue(@PathParam("queueId") String queueId,
                                @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        return accepted(service.updateQueue(queueId,
                str(body, "displayName"), integer(body, "visibilityInSeconds"),
                integer(body, "timeoutInSeconds"), integer(body, "deadLetterQueueDeliveryCount"),
                integer(body, "channelConsumptionLimit"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch));
    }

    @DELETE
    @Path("/queues/{queueId}")
    public Response deleteQueue(@PathParam("queueId") String queueId,
                                @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteQueue(queueId, ifMatch));
    }

    @POST
    @Path("/queues/{queueId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("queueId") String queueId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        return accepted(service.changeCompartment(queueId, str(body, "compartmentId"), ifMatch));
    }

    @POST
    @Path("/queues/{queueId}/actions/purge")
    public Response purgeQueue(@PathParam("queueId") String queueId,
                               Map<String, Object> body) {
        return accepted(service.purgeQueue(queueId, str(body, "purgeType"), stringList(body, "channelIds")));
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get(QueueService.WR_SERVICE, workRequestId);
        return Response.ok(wr.toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        List<Map<String, Object>> items = workRequests.list(QueueService.WR_SERVICE, compartmentId)
                .stream().map(StoredWorkRequest::toWire).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId) {
        workRequests.get(QueueService.WR_SERVICE, workRequestId);
        return Response.ok(Map.of("items", List.of())).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId) {
        workRequests.get(QueueService.WR_SERVICE, workRequestId);
        return Response.ok(Map.of("items", List.of())).build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private static Response accepted(QueueService.WorkRequestOutcome outcome) {
        return Response.accepted()
                .header("opc-work-request-id", outcome.workRequestId())
                .build();
    }

    static Map<String, Object> queueJson(StoredQueue q) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", q.getId());
        json.put("displayName", q.getDisplayName());
        json.put("compartmentId", q.getCompartmentId());
        json.put("timeCreated", q.getTimeCreated());
        json.put("timeUpdated", q.getTimeUpdated());
        json.put("lifecycleState", q.getLifecycleState());
        json.put("messagesEndpoint", q.getMessagesEndpoint());
        json.put("retentionInSeconds", q.getRetentionInSeconds());
        json.put("visibilityInSeconds", q.getVisibilityInSeconds());
        json.put("timeoutInSeconds", q.getTimeoutInSeconds());
        json.put("deadLetterQueueDeliveryCount", q.getDeadLetterQueueDeliveryCount());
        json.put("channelConsumptionLimit", q.getChannelConsumptionLimit());
        // Terraform marks systemTags computed; omitting it drifts on every plan.
        json.put("systemTags", Map.of());
        if (q.getFreeformTags() != null) {
            json.put("freeformTags", q.getFreeformTags());
        }
        if (q.getDefinedTags() != null) {
            json.put("definedTags", q.getDefinedTags());
        }
        return json;
    }

    /** QueueSummary: no retention/visibility/timeout/dlq fields on the wire. */
    private static Map<String, Object> queueSummaryJson(StoredQueue q) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", q.getId());
        json.put("displayName", q.getDisplayName());
        json.put("compartmentId", q.getCompartmentId());
        json.put("timeCreated", q.getTimeCreated());
        json.put("timeUpdated", q.getTimeUpdated());
        json.put("lifecycleState", q.getLifecycleState());
        json.put("messagesEndpoint", q.getMessagesEndpoint());
        json.put("systemTags", Map.of());
        if (q.getFreeformTags() != null) {
            json.put("freeformTags", q.getFreeformTags());
        }
        if (q.getDefinedTags() != null) {
            json.put("definedTags", q.getDefinedTags());
        }
        return json;
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

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }

    private static List<String> stringList(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return null;
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
