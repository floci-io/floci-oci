package io.floci.oci.services.streaming;

import io.floci.oci.core.common.OciException;
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
 * OCI Streaming data plane — served on the stream's {@code messagesEndpoint}.
 * GetMessages takes a mandatory {@code cursor} query param, returns a bare JSON array
 * and advances via the {@code opc-next-cursor} response header. Commit/heartbeat are
 * POSTs with the cursor in the query string and no body.
 */
@Path("/20180418")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StreamDataController {

    public static final String OPC_NEXT_CURSOR = "opc-next-cursor";

    private final StreamingService service;

    @Inject
    public StreamDataController(StreamingService service) {
        this.service = service;
    }

    @POST
    @Path("/streams/{streamId}/messages")
    public Response putMessages(@PathParam("streamId") String streamId, Map<String, Object> body) {
        List<Map<String, Object>> entries = mapList(body, "messages");
        if (entries == null) {
            throw OciException.missingParameter("messages is required");
        }
        List<StreamingService.PutEntryResult> results = service.putMessages(streamId, entries);
        int failures = 0;
        List<Map<String, Object>> wireEntries = new ArrayList<>();
        for (StreamingService.PutEntryResult result : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (result.error() != null) {
                failures++;
                entry.put("error", result.error());
                entry.put("errorMessage", result.errorMessage());
            } else {
                entry.put("partition", String.valueOf(result.partition()));
                entry.put("offset", result.offset());
                entry.put("timestamp", result.timestamp());
            }
            wireEntries.add(entry);
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("failures", failures);
        wire.put("entries", wireEntries);
        return Response.ok(wire).build();
    }

    @GET
    @Path("/streams/{streamId}/messages")
    public Response getMessages(@PathParam("streamId") String streamId,
                                @QueryParam("cursor") String cursor,
                                @QueryParam("limit") Integer limit) {
        StreamingService.ConsumeResult result = service.getMessages(streamId, cursor, limit);
        return Response.ok(result.messages())
                .header(OPC_NEXT_CURSOR, result.nextCursor())
                .build();
    }

    @POST
    @Path("/streams/{streamId}/cursors")
    public Response createCursor(@PathParam("streamId") String streamId, Map<String, Object> body) {
        String cursor = service.createCursor(streamId,
                body != null && body.get("partition") != null ? String.valueOf(body.get("partition")) : null,
                str(body, "type"), longValue(body, "offset"), str(body, "time"));
        return Response.ok(Map.of("value", cursor)).build();
    }

    @POST
    @Path("/streams/{streamId}/groupCursors")
    public Response createGroupCursor(@PathParam("streamId") String streamId,
                                      Map<String, Object> body) {
        String cursor = service.createGroupCursor(streamId, str(body, "groupName"),
                str(body, "type"), str(body, "time"),
                Boolean.TRUE.equals(body != null ? body.get("commitOnGet") : null));
        return Response.ok(Map.of("value", cursor)).build();
    }

    @POST
    @Path("/streams/{streamId}/commit")
    @Consumes(MediaType.WILDCARD) // cursor travels as a query param; the body is empty
    public Response consumerCommit(@PathParam("streamId") String streamId,
                                   @QueryParam("cursor") String cursor) {
        return Response.ok(Map.of("value", service.consumerCommit(streamId, cursor))).build();
    }

    @POST
    @Path("/streams/{streamId}/heartbeat")
    @Consumes(MediaType.WILDCARD)
    public Response consumerHeartbeat(@PathParam("streamId") String streamId,
                                      @QueryParam("cursor") String cursor) {
        return Response.ok(Map.of("value", service.consumerHeartbeat(streamId, cursor))).build();
    }

    @GET
    @Path("/streams/{streamId}/groups/{groupName}")
    public Response getGroup(@PathParam("streamId") String streamId,
                             @PathParam("groupName") String groupName) {
        return Response.ok(service.getGroup(streamId, groupName)).build();
    }

    @PUT
    @Path("/streams/{streamId}/groups/{groupName}")
    public Response updateGroup(@PathParam("streamId") String streamId,
                                @PathParam("groupName") String groupName,
                                Map<String, Object> body) {
        service.updateGroup(streamId, groupName, str(body, "type"), str(body, "time"));
        return Response.ok().build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long longValue(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> body, String key) {
        if (body == null || !(body.get(key) instanceof List<?> list)) {
            return null;
        }
        return (List<Map<String, Object>>) list;
    }
}
