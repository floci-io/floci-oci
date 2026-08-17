package io.floci.oci.services.queue;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.queue.model.StoredQueue.StoredMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Queue data plane — served on the queue's {@code messagesEndpoint} (the emulator
 * base URL). Data-plane requests carry no etag/if-match semantics.
 */
@Path("/20210201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueDataController {

    private final QueueService service;

    @Inject
    public QueueDataController(QueueService service) {
        this.service = service;
    }

    @POST
    @Path("/queues/{queueId}/messages")
    public Response putMessages(@PathParam("queueId") String queueId, Map<String, Object> body) {
        List<Map<String, Object>> entries = mapList(body, "messages");
        if (entries == null) {
            throw OciException.missingParameter("messages is required");
        }
        List<Map<String, Object>> results = service.putMessages(queueId, entries).stream()
                .map(r -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("id", r.id());
                    entry.put("expireAfter", r.expireAfter());
                    return entry;
                })
                .toList();
        return Response.ok(Map.of("messages", results)).build();
    }

    @GET
    @Path("/queues/{queueId}/messages")
    public Response getMessages(@PathParam("queueId") String queueId,
                                @QueryParam("visibilityInSeconds") Integer visibilityInSeconds,
                                @QueryParam("timeoutInSeconds") Integer timeoutInSeconds,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("channelFilter") String channelFilter) {
        List<Map<String, Object>> messages = service.getMessages(queueId, visibilityInSeconds,
                        timeoutInSeconds, limit, channelFilter).stream()
                .map(QueueDataController::messageJson)
                .toList();
        return Response.ok(Map.of("messages", messages)).build();
    }

    @DELETE
    @Path("/queues/{queueId}/messages/{messageReceipt}")
    public Response deleteMessage(@PathParam("queueId") String queueId,
                                  @PathParam("messageReceipt") String messageReceipt) {
        service.deleteMessage(queueId, messageReceipt);
        return Response.noContent().build();
    }

    @POST
    @Path("/queues/{queueId}/messages/actions/deleteMessages")
    public Response deleteMessages(@PathParam("queueId") String queueId, Map<String, Object> body) {
        List<Map<String, Object>> entries = mapList(body, "entries");
        if (entries == null) {
            throw OciException.missingParameter("entries is required");
        }
        List<String> receipts = entries.stream()
                .map(e -> e.get("receipt") instanceof String s ? s : null)
                .toList();
        List<QueueService.BatchEntryError> outcomes = service.deleteMessages(queueId, receipts);
        List<Map<String, Object>> resultEntries = new ArrayList<>();
        int serverFailures = 0;
        for (QueueService.BatchEntryError outcome : outcomes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (outcome.errorCode() != null) {
                serverFailures++;
                entry.put("errorCode", outcome.errorCode());
                entry.put("errorMessage", outcome.errorMessage());
            }
            resultEntries.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverFailures", serverFailures);
        result.put("clientFailures", 0);
        result.put("entries", resultEntries);
        return Response.ok(result).build();
    }

    @PUT
    @Path("/queues/{queueId}/messages/{messageReceipt}")
    public Response updateMessage(@PathParam("queueId") String queueId,
                                  @PathParam("messageReceipt") String messageReceipt,
                                  Map<String, Object> body) {
        Integer visibility = body != null && body.get("visibilityInSeconds") instanceof Number n
                ? n.intValue() : null;
        if (visibility == null) {
            throw OciException.missingParameter("visibilityInSeconds is required");
        }
        StoredMessage m = service.updateMessage(queueId, messageReceipt, visibility);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", m.getId());
        result.put("visibleAfter", m.getVisibleAfter());
        return Response.ok(result).build();
    }

    @POST
    @Path("/queues/{queueId}/messages/actions/updateMessages")
    public Response updateMessages(@PathParam("queueId") String queueId, Map<String, Object> body) {
        List<Map<String, Object>> entries = mapList(body, "entries");
        if (entries == null) {
            throw OciException.missingParameter("entries is required");
        }
        List<Map<String, Object>> resultEntries = new ArrayList<>();
        int serverFailures = 0;
        for (Map<String, Object> entry : entries) {
            String receipt = entry.get("receipt") instanceof String s ? s : null;
            Integer visibility = entry.get("visibilityInSeconds") instanceof Number n
                    ? n.intValue() : null;
            Map<String, Object> resultEntry = new LinkedHashMap<>();
            try {
                if (receipt == null || visibility == null) {
                    throw OciException.invalidParameter("receipt and visibilityInSeconds are required");
                }
                StoredMessage m = service.updateMessage(queueId, receipt, visibility);
                resultEntry.put("id", m.getId());
                resultEntry.put("visibleAfter", m.getVisibleAfter());
            } catch (OciException e) {
                serverFailures++;
                resultEntry.put("errorCode", e.getHttpStatus());
                resultEntry.put("errorMessage", e.getMessage());
            }
            resultEntries.add(resultEntry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverFailures", serverFailures);
        result.put("clientFailures", 0);
        result.put("entries", resultEntries);
        return Response.ok(result).build();
    }

    @GET
    @Path("/queues/{queueId}/stats")
    public Response getStats(@PathParam("queueId") String queueId) {
        QueueService.QueueStats stats = service.getStats(queueId);
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("visibleMessages", stats.visibleMessages());
        queue.put("inFlightMessages", stats.inFlightMessages());
        queue.put("sizeInBytes", stats.sizeInBytes());
        Map<String, Object> dlq = new LinkedHashMap<>();
        dlq.put("visibleMessages", stats.dlqVisible());
        dlq.put("inFlightMessages", stats.dlqInFlight());
        dlq.put("sizeInBytes", stats.dlqSizeInBytes());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue", queue);
        result.put("dlq", dlq);
        return Response.ok(result).build();
    }

    @GET
    @Path("/queues/{queueId}/channels")
    public Response listChannels(@PathParam("queueId") String queueId) {
        return Response.ok(Map.of("items", service.listChannels(queueId))).build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private static Map<String, Object> messageJson(StoredMessage m) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", m.getId());
        json.put("content", m.getContent());
        json.put("receipt", m.getReceipt());
        json.put("deliveryCount", m.getDeliveryCount());
        json.put("visibleAfter", m.getVisibleAfter());
        json.put("expireAfter", m.getExpireAfter());
        json.put("createdAt", m.getCreatedAt());
        if (m.getChannelId() != null || m.getCustomProperties() != null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (m.getChannelId() != null) {
                metadata.put("channelId", m.getChannelId());
            }
            if (m.getCustomProperties() != null) {
                metadata.put("customProperties", m.getCustomProperties());
            }
            json.put("metadata", metadata);
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return null;
        }
        return (List<Map<String, Object>>) list;
    }
}
