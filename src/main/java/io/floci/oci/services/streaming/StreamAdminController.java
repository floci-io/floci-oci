package io.floci.oci.services.streaming;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStreamPool;
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
 * OCI Streaming control plane: {@code /20180418/streams…} and {@code /20180418/streampools…}. CreateStream is dual-mode:
 * it returns the FULL stream body AND an {@code opc-work-request-id} header; Terraform
 * waits on the work request and reads the stream OCID off its {@code resources[]}.
 * Lists are bare JSON arrays.
 */
@Path("/20180418")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StreamAdminController {

    private final StreamingService service;
    private final CompartmentValidator compartments;
    private final WorkRequestService workRequests;

    @Inject
    public StreamAdminController(StreamingService service, WorkRequestService workRequests,
                                 CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
        this.workRequests = workRequests;
    }

    @POST
    @Path("/streams")
    public Response createStream(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(str(body, "compartmentId"))) {
            StreamingService.CreatedStream created = service.createStream(
                    str(body, "name"), integer(body, "partitions"), integer(body, "retentionInHours"),
                    str(body, "compartmentId"), str(body, "streamPoolId"),
                    stringMap(body, "freeformTags"), definedTags(body));
            return Response.ok(streamJson(created.stream()))
                    .header("etag", created.stream().getEtag())
                    .header("opc-work-request-id", created.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/streams/{streamId}")
    public Response getStream(@PathParam("streamId") String streamId) {
        StoredStream stream = service.getStream(streamId);
        return Response.ok(streamJson(stream)).header("etag", stream.getEtag()).build();
    }

    @GET
    @Path("/streams")
    public Response listStreams(@QueryParam("compartmentId") String compartmentId,
                                @QueryParam("streamPoolId") String streamPoolId,
                                @QueryParam("name") String name,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> summaries = service.listStreams(compartmentId, streamPoolId, name)
                .stream().map(this::streamSummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/streams/{streamId}")
    public Response updateStream(@PathParam("streamId") String streamId,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        StreamingService.UpdatedStream updated = service.updateStream(streamId,
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(streamJson(updated.stream()))
                .header("etag", updated.stream().getEtag())
                .header("opc-work-request-id", updated.workRequestId())
                .build();
    }

    @DELETE
    @Path("/streams/{streamId}")
    public Response deleteStream(@PathParam("streamId") String streamId,
                                 @HeaderParam("if-match") String ifMatch) {
        String workRequestId = service.deleteStream(streamId, ifMatch);
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    // ── Stream pools ───────────────────────────────────────────────────────────

    @POST
    @Path("/streampools")
    public Response createStreamPool(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(str(body, "compartmentId"))) {
            StreamingService.PoolChange created = service.createStreamPool(str(body, "name"),
                    str(body, "compartmentId"), objectMap(body, "kafkaSettings"),
                    objectMap(body, "customEncryptionKeyDetails"), stringMap(body, "freeformTags"),
                    definedTags(body));
            return poolResponse(created);
        }
    }

    @GET
    @Path("/streampools/{streamPoolId}")
    public Response getStreamPool(@PathParam("streamPoolId") String streamPoolId) {
        StoredStreamPool pool = service.getStreamPool(streamPoolId);
        return Response.ok(streamPoolJson(pool)).header("etag", pool.getEtag()).build();
    }

    @GET
    @Path("/streampools")
    public Response listStreamPools(@QueryParam("compartmentId") String compartmentId,
                                    @QueryParam("id") String id,
                                    @QueryParam("name") String name,
                                    @QueryParam("lifecycleState") String lifecycleState,
                                    @QueryParam("sortBy") String sortBy,
                                    @QueryParam("sortOrder") String sortOrder,
                                    @QueryParam("limit") Integer limit,
                                    @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        List<StoredStreamPool> found = service.listStreamPools(compartmentId, id, name, lifecycleState,
                sortBy, sortOrder);
        List<Map<String, Object>> summaries = found.stream().map(this::streamPoolSummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/streampools/{streamPoolId}")
    public Response updateStreamPool(@PathParam("streamPoolId") String streamPoolId,
                                     @HeaderParam("if-match") String ifMatch,
                                     Map<String, Object> body) {
        StreamingService.PoolChange updated = service.updateStreamPool(streamPoolId, str(body, "name"),
                objectMap(body, "kafkaSettings"), objectMap(body, "customEncryptionKeyDetails"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return poolResponse(updated);
    }

    @DELETE
    @Path("/streampools/{streamPoolId}")
    public Response deleteStreamPool(@PathParam("streamPoolId") String streamPoolId,
                                     @HeaderParam("if-match") String ifMatch) {
        String workRequestId = service.deleteStreamPool(streamPoolId, ifMatch);
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    @POST
    @Path("/streampools/{streamPoolId}/actions/changeCompartment")
    public Response changeStreamPoolCompartment(@PathParam("streamPoolId") String streamPoolId,
                                                @HeaderParam("if-match") String ifMatch,
                                                Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(str(body, "compartmentId"))) {
            String workRequestId = service.changeStreamPoolCompartment(streamPoolId,
                    str(body, "compartmentId"), ifMatch);
            return Response.ok().header("opc-work-request-id", workRequestId).build();
        }
    }

    private Response poolResponse(StreamingService.PoolChange change) {
        return Response.ok(streamPoolJson(change.pool()))
                .header("etag", change.pool().getEtag())
                .header("opc-work-request-id", change.workRequestId())
                .build();
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get(StreamingService.WR_SERVICE, workRequestId);
        return Response.ok(wr.toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> items = workRequests.list(StreamingService.WR_SERVICE, compartmentId)
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
        workRequests.get(StreamingService.WR_SERVICE, workRequestId);
        return Response.ok(Map.of("items", List.of())).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId) {
        workRequests.get(StreamingService.WR_SERVICE, workRequestId);
        return Response.ok(Map.of("items", List.of())).build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private Map<String, Object> streamJson(StoredStream stream) {
        Map<String, Object> json = streamSummaryJson(stream);
        // retentionInHours is on Stream but NOT on StreamSummary.
        json.put("retentionInHours", stream.getRetentionInHours());
        return json;
    }

    private Map<String, Object> streamSummaryJson(StoredStream stream) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", stream.getName());
        json.put("id", stream.getId());
        json.put("partitions", stream.getPartitions());
        json.put("compartmentId", stream.getCompartmentId());
        json.put("streamPoolId", stream.getStreamPoolId());
        json.put("lifecycleState", stream.getLifecycleState());
        json.put("timeCreated", stream.getTimeCreated());
        json.put("messagesEndpoint", service.messagesEndpoint());
        if (stream.getFreeformTags() != null) {
            json.put("freeformTags", stream.getFreeformTags());
        }
        if (stream.getDefinedTags() != null) {
            json.put("definedTags", stream.getDefinedTags());
        }
        return json;
    }

    private Map<String, Object> streamPoolJson(StoredStreamPool pool) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", pool.getId());
        json.put("compartmentId", pool.getCompartmentId());
        json.put("name", pool.getName());
        json.put("lifecycleState", pool.getLifecycleState());
        json.put("timeCreated", pool.getTimeCreated());
        Map<String, Object> kafka = new LinkedHashMap<>();
        if (pool.getBootstrapServers() != null) {
            kafka.put("bootstrapServers", pool.getBootstrapServers());
        }
        kafka.put("autoCreateTopicsEnable", pool.getAutoCreateTopicsEnable());
        kafka.put("logRetentionHours", pool.getLogRetentionHours());
        kafka.put("numPartitions", pool.getNumPartitions());
        json.put("kafkaSettings", kafka);
        Map<String, Object> key = new LinkedHashMap<>();
        if (pool.getKmsKeyId() != null) {
            key.put("kmsKeyId", pool.getKmsKeyId());
        }
        key.put("keyState", pool.getKmsKeyId() != null ? "ACTIVE" : "NONE");
        json.put("customEncryptionKey", key);
        if (pool.getLifecycleStateDetails() != null) {
            json.put("lifecycleStateDetails", pool.getLifecycleStateDetails());
        }
        json.put("isPrivate", false);
        putTags(json, pool.getFreeformTags(), pool.getDefinedTags());
        return json;
    }

    private Map<String, Object> streamPoolSummaryJson(StoredStreamPool pool) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", pool.getId());
        json.put("compartmentId", pool.getCompartmentId());
        json.put("name", pool.getName());
        json.put("lifecycleState", pool.getLifecycleState());
        json.put("timeCreated", pool.getTimeCreated());
        json.put("isPrivate", false);
        putTags(json, pool.getFreeformTags(), pool.getDefinedTags());
        return json;
    }

    private static void putTags(Map<String, Object> json, Map<String, String> freeformTags,
                                Map<String, Map<String, Object>> definedTags) {
        if (freeformTags != null) {
            json.put("freeformTags", freeformTags);
        }
        if (definedTags != null) {
            json.put("definedTags", definedTags);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
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
}
