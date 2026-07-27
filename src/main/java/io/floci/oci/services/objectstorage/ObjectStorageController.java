package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredPar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OCI Object Storage API. Unversioned paths: {@code /n/{namespace}/b/{bucket}/o/{object}}.
 */
@Path("/")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ObjectStorageController {

    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).withLocale(Locale.US);

    private final ObjectStorageService service;
    private final WorkRequestService workRequests;

    @Inject
    public ObjectStorageController(ObjectStorageService service, WorkRequestService workRequests) {
        this.service = service;
        this.workRequests = workRequests;
    }

    // ── Namespace ──────────────────────────────────────────────────────────────

    @GET
    @Path("/n")
    public Response getNamespace() {
        // The body is a bare JSON string.
        return Response.ok("\"" + service.namespace() + "\"", MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/n/{namespaceName}")
    public Response getNamespaceMetadata(@PathParam("namespaceName") String namespaceName) {
        return Response.ok(service.namespaceMetadata(namespaceName)).build();
    }

    // ── Buckets ────────────────────────────────────────────────────────────────

    @POST
    @Path("/n/{namespaceName}/b")
    public Response createBucket(@PathParam("namespaceName") String namespaceName,
                                 Map<String, Object> body) {
        StoredBucket b = service.createBucket(namespaceName,
                str(body, "name"), str(body, "compartmentId"), stringMap(body, "metadata"),
                str(body, "publicAccessType"), str(body, "storageTier"),
                stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(b).header("etag", b.getEtag())
                .header("Location", "/n/" + service.namespace() + "/b/" + b.getName()).build();
    }

    @GET
    @Path("/n/{namespaceName}/b")
    public Response listBuckets(@PathParam("namespaceName") String namespaceName,
                                @QueryParam("compartmentId") String compartmentId,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listBuckets(namespaceName, compartmentId).stream()
                .map(ObjectStorageController::bucketSummary)
                .toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}")
    public Response getBucket(@PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @HeaderParam("if-match") String ifMatch,
                              @HeaderParam("if-none-match") String ifNoneMatch) {
        StoredBucket b = service.getBucket(namespaceName, bucketName);
        return Response.ok(b).header("etag", b.getEtag()).build();
    }

    @HEAD
    @Path("/n/{namespaceName}/b/{bucketName}")
    public Response headBucket(@PathParam("namespaceName") String namespaceName,
                               @PathParam("bucketName") String bucketName) {
        StoredBucket b = service.getBucket(namespaceName, bucketName);
        return Response.ok().header("etag", b.getEtag()).build();
    }

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}")
    public Response updateBucket(@PathParam("namespaceName") String namespaceName,
                                 @PathParam("bucketName") String bucketName,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        StoredBucket b = service.updateBucket(namespaceName, bucketName,
                stringMap(body, "metadata"), str(body, "publicAccessType"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(b).header("etag", b.getEtag()).build();
    }

    @DELETE
    @Path("/n/{namespaceName}/b/{bucketName}")
    public Response deleteBucket(@PathParam("namespaceName") String namespaceName,
                                 @PathParam("bucketName") String bucketName,
                                 @HeaderParam("if-match") String ifMatch) {
        service.deleteBucket(namespaceName, bucketName, ifMatch);
        return Response.noContent().build();
    }

    // ── Objects ────────────────────────────────────────────────────────────────

    @PUT
    @Path("/n/{namespaceName}/b/{bucketName}/o/{objectName:.+}")
    public Response putObject(@PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @PathParam("objectName") String objectName,
                              @HeaderParam("Content-Type") String contentType,
                              @HeaderParam("Content-MD5") String contentMd5,
                              @HeaderParam("if-match") String ifMatch,
                              @HeaderParam("if-none-match") String ifNoneMatch,
                              @Context HttpHeaders headers,
                              InputStream body) throws IOException {
        byte[] data = body.readAllBytes();
        StoredOsObject o = service.putObject(namespaceName, bucketName, objectName, data,
                contentType, contentMd5, opcMeta(headers), ifMatch, ifNoneMatch);
        return Response.ok()
                .header("etag", o.getEtag())
                .header("opc-content-md5", o.getMd5())
                .header("last-modified", RFC_1123.format(Instant.parse(o.getTimeModified())))
                .build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/o/{objectName:.+}")
    public Response getObject(@PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @PathParam("objectName") String objectName,
                              @HeaderParam("Range") String range,
                              @HeaderParam("if-match") String ifMatch,
                              @HeaderParam("if-none-match") String ifNoneMatch) {
        StoredOsObject o = service.getObject(namespaceName, bucketName, objectName);
        if (ifMatch != null) {
            io.floci.oci.core.common.Etags.checkIfMatch(ifMatch, o.getEtag());
        }
        return objectResponse(o, range, true);
    }

    @HEAD
    @Path("/n/{namespaceName}/b/{bucketName}/o/{objectName:.+}")
    public Response headObject(@PathParam("namespaceName") String namespaceName,
                               @PathParam("bucketName") String bucketName,
                               @PathParam("objectName") String objectName) {
        StoredOsObject o = service.getObject(namespaceName, bucketName, objectName);
        return objectResponse(o, null, false);
    }

    @DELETE
    @Path("/n/{namespaceName}/b/{bucketName}/o/{objectName:.+}")
    public Response deleteObject(@PathParam("namespaceName") String namespaceName,
                                 @PathParam("bucketName") String bucketName,
                                 @PathParam("objectName") String objectName,
                                 @HeaderParam("if-match") String ifMatch) {
        service.deleteObject(namespaceName, bucketName, objectName, ifMatch);
        return Response.noContent().build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/o")
    public Response listObjects(@PathParam("namespaceName") String namespaceName,
                                @PathParam("bucketName") String bucketName,
                                @QueryParam("prefix") String prefix,
                                @QueryParam("start") String start,
                                @QueryParam("end") String end,
                                @QueryParam("delimiter") String delimiter,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("fields") String fields) {
        ObjectStorageService.ObjectListing listing =
                service.listObjects(namespaceName, bucketName, prefix, start, end, delimiter, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objects", listing.objects().stream()
                .map(o -> objectSummary(o, fields))
                .toList());
        if (!listing.prefixes().isEmpty()) {
            body.put("prefixes", listing.prefixes());
        }
        if (listing.nextStartWith() != null) {
            body.put("nextStartWith", listing.nextStartWith());
        }
        return Response.ok(body).build();
    }

    // ── Object actions ─────────────────────────────────────────────────────────

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}/actions/renameObject")
    public Response renameObject(@PathParam("namespaceName") String namespaceName,
                                 @PathParam("bucketName") String bucketName,
                                 Map<String, Object> body) {
        String sourceName = str(body, "sourceName");
        String newName = str(body, "newName");
        if (sourceName == null || newName == null) {
            throw OciException.missingParameter("sourceName and newName are required");
        }
        StoredOsObject o = service.renameObject(namespaceName, bucketName, sourceName, newName,
                str(body, "srcObjIfMatchETag"), str(body, "newObjIfMatchETag"),
                str(body, "newObjIfNoneMatchETag"));
        return Response.ok()
                .header("etag", o.getEtag())
                .header("last-modified", RFC_1123.format(Instant.parse(o.getTimeModified())))
                .build();
    }

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}/actions/copyObject")
    public Response copyObject(@PathParam("namespaceName") String namespaceName,
                               @PathParam("bucketName") String bucketName,
                               Map<String, Object> body) {
        String sourceObjectName = str(body, "sourceObjectName");
        String destinationNamespace = str(body, "destinationNamespace");
        String destinationBucket = str(body, "destinationBucket");
        String destinationObjectName = str(body, "destinationObjectName");
        if (sourceObjectName == null || destinationNamespace == null
                || destinationBucket == null || destinationObjectName == null) {
            throw OciException.missingParameter(
                    "sourceObjectName, destinationRegion, destinationNamespace, destinationBucket"
                            + " and destinationObjectName are required");
        }
        String workRequestId = service.copyObject(namespaceName, bucketName, sourceObjectName,
                destinationNamespace, destinationBucket, destinationObjectName,
                str(body, "sourceObjectIfMatchETag"));
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    // ── Multipart uploads ──────────────────────────────────────────────────────

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}/u")
    public Response createMultipartUpload(@PathParam("namespaceName") String namespaceName,
                                          @PathParam("bucketName") String bucketName,
                                          Map<String, Object> body) {
        StoredMultipartUpload u = service.createMultipartUpload(namespaceName, bucketName,
                str(body, "object"), str(body, "contentType"), stringMap(body, "metadata"));
        return Response.ok(multipartJson(u)).build();
    }

    @PUT
    @Path("/n/{namespaceName}/b/{bucketName}/u/{objectName:.+}")
    public Response uploadPart(@PathParam("namespaceName") String namespaceName,
                               @PathParam("bucketName") String bucketName,
                               @PathParam("objectName") String objectName,
                               @QueryParam("uploadId") String uploadId,
                               @QueryParam("uploadPartNum") Integer uploadPartNum,
                               InputStream body) throws IOException {
        if (uploadId == null || uploadPartNum == null) {
            throw OciException.missingParameter("uploadId and uploadPartNum are required");
        }
        ObjectStorageService.UploadedPart part = service.uploadPart(namespaceName, bucketName,
                objectName, uploadId, uploadPartNum, body.readAllBytes());
        return Response.ok()
                .header("etag", part.etag())
                .header("opc-content-md5", part.md5())
                .build();
    }

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}/u/{objectName:.+}")
    public Response commitMultipartUpload(@PathParam("namespaceName") String namespaceName,
                                          @PathParam("bucketName") String bucketName,
                                          @PathParam("objectName") String objectName,
                                          @QueryParam("uploadId") String uploadId,
                                          Map<String, Object> body) {
        if (uploadId == null) {
            throw OciException.missingParameter("uploadId is required");
        }
        StoredOsObject o = service.commitMultipartUpload(namespaceName, bucketName, objectName,
                uploadId, partsToCommit(body));
        return Response.ok()
                .header("etag", o.getEtag())
                .header("opc-multipart-md5", o.getMd5())
                .header("last-modified", RFC_1123.format(Instant.parse(o.getTimeModified())))
                .build();
    }

    @DELETE
    @Path("/n/{namespaceName}/b/{bucketName}/u/{objectName:.+}")
    public Response abortMultipartUpload(@PathParam("namespaceName") String namespaceName,
                                         @PathParam("bucketName") String bucketName,
                                         @PathParam("objectName") String objectName,
                                         @QueryParam("uploadId") String uploadId) {
        if (uploadId == null) {
            throw OciException.missingParameter("uploadId is required");
        }
        service.abortMultipartUpload(namespaceName, bucketName, objectName, uploadId);
        return Response.noContent().build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/u")
    public Response listMultipartUploads(@PathParam("namespaceName") String namespaceName,
                                         @PathParam("bucketName") String bucketName,
                                         @QueryParam("limit") Integer limit,
                                         @QueryParam("page") String page) {
        List<Map<String, Object>> items = service.listMultipartUploads(namespaceName, bucketName)
                .stream().map(ObjectStorageController::multipartJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    // ── Pre-authenticated requests (management plane) ──────────────────────────

    @POST
    @Path("/n/{namespaceName}/b/{bucketName}/p")
    public Response createPar(@PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              Map<String, Object> body) {
        StoredPar par = service.createPar(namespaceName, bucketName,
                str(body, "name"), str(body, "accessType"), str(body, "timeExpires"),
                str(body, "objectName"), str(body, "bucketListingAction"));
        return Response.ok(parJson(par, true)).build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/p")
    public Response listPars(@PathParam("namespaceName") String namespaceName,
                             @PathParam("bucketName") String bucketName,
                             @QueryParam("limit") Integer limit,
                             @QueryParam("page") String page) {
        List<Map<String, Object>> items = service.listPars(namespaceName, bucketName).stream()
                .map(p -> parJson(p, false))
                .toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/p/{parId}")
    public Response getPar(@PathParam("namespaceName") String namespaceName,
                           @PathParam("bucketName") String bucketName,
                           @PathParam("parId") String parId) {
        return Response.ok(parJson(service.getParById(namespaceName, bucketName, parId), false))
                .build();
    }

    @DELETE
    @Path("/n/{namespaceName}/b/{bucketName}/p/{parId}")
    public Response deletePar(@PathParam("namespaceName") String namespaceName,
                              @PathParam("bucketName") String bucketName,
                              @PathParam("parId") String parId) {
        service.deletePar(namespaceName, bucketName, parId);
        return Response.noContent().build();
    }

    // ── Retention rules (read-only stub) ───────────────────────────────────────

    /**
     * Retention rules are not emulated, but the Terraform provider's bucket Read calls
     * ListRetentionRules unconditionally — a 404 here makes Terraform silently drop the
     * bucket from state. Answer with an empty collection.
     */
    @GET
    @Path("/n/{namespaceName}/b/{bucketName}/retentionRules")
    public Response listRetentionRules(@PathParam("namespaceName") String namespaceName,
                                       @PathParam("bucketName") String bucketName) {
        service.getBucket(namespaceName, bucketName);
        return Response.ok(Map.of("items", List.of())).build();
    }

    // ── Work requests (Object Storage exposes them unversioned at the root) ────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get("objectstorage", workRequestId);
        return Response.ok(wr.toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(
                workRequests.list("objectstorage", compartmentId).stream()
                        .map(StoredWorkRequest::toWire).toList(), limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId) {
        workRequests.get("objectstorage", workRequestId);
        return Response.ok(List.of()).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId) {
        workRequests.get("objectstorage", workRequestId);
        return Response.ok(List.of()).build();
    }

    // ── Shared response builders ───────────────────────────────────────────────

    static Response objectResponse(StoredOsObject o, String range, boolean includeBody) {
        byte[] data = o.getData();
        int status = 200;
        String contentRange = null;
        if (range != null && range.startsWith("bytes=")) {
            int[] bounds = parseRange(range, data.length);
            byte[] slice = new byte[bounds[1] - bounds[0] + 1];
            System.arraycopy(data, bounds[0], slice, 0, slice.length);
            contentRange = "bytes " + bounds[0] + "-" + bounds[1] + "/" + data.length;
            data = slice;
            status = 206;
        }
        Response.ResponseBuilder builder = Response.status(status)
                .header("etag", o.getEtag())
                .header("content-md5", o.getMd5())
                .header("last-modified", RFC_1123.format(Instant.parse(o.getTimeModified())))
                .header("storage-tier", o.getStorageTier());
        if (contentRange != null) {
            builder.header("content-range", contentRange);
        }
        if (o.getMetadata() != null) {
            o.getMetadata().forEach((k, v) -> builder.header("opc-meta-" + k, v));
        }
        if (includeBody) {
            builder.entity(data).type(o.getContentType());
        } else {
            builder.header("content-length", String.valueOf(data.length))
                    .type(o.getContentType());
        }
        return builder.build();
    }

    private static int[] parseRange(String range, int size) {
        String spec = range.substring("bytes=".length()).trim();
        String[] parts = spec.split("-", 2);
        try {
            if (parts[0].isEmpty()) {
                // suffix range: last N bytes
                int n = Integer.parseInt(parts[1]);
                int from = Math.max(0, size - n);
                return new int[]{from, size - 1};
            }
            int from = Integer.parseInt(parts[0]);
            int to = parts.length > 1 && !parts[1].isEmpty()
                    ? Math.min(Integer.parseInt(parts[1]), size - 1) : size - 1;
            if (from > to || from >= size) {
                throw OciException.invalidParameter("Invalid Range: " + range);
            }
            return new int[]{from, to};
        } catch (NumberFormatException e) {
            throw OciException.invalidParameter("Invalid Range: " + range);
        }
    }

    private static Map<String, Object> bucketSummary(StoredBucket b) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("namespace", b.getNamespace());
        summary.put("name", b.getName());
        summary.put("compartmentId", b.getCompartmentId());
        summary.put("createdBy", b.getCreatedBy());
        summary.put("timeCreated", b.getTimeCreated());
        summary.put("etag", b.getEtag());
        if (b.getFreeformTags() != null) {
            summary.put("freeformTags", b.getFreeformTags());
        }
        if (b.getDefinedTags() != null) {
            summary.put("definedTags", b.getDefinedTags());
        }
        return summary;
    }

    private static Map<String, Object> objectSummary(StoredOsObject o, String fields) {
        Map<String, Object> summary = new LinkedHashMap<>();
        // Default fields are name,size,timeCreated,md5; extra requested fields are additive.
        boolean all = fields == null || fields.isBlank();
        List<String> requested = all ? List.of("name", "size", "timeCreated", "md5")
                : List.of(fields.split(","));
        summary.put("name", o.getName());
        if (requested.contains("size")) {
            summary.put("size", o.getData().length);
        }
        if (requested.contains("timeCreated")) {
            summary.put("timeCreated", o.getTimeCreated());
        }
        if (requested.contains("md5")) {
            summary.put("md5", o.getMd5());
        }
        if (requested.contains("etag")) {
            summary.put("etag", o.getEtag());
        }
        if (requested.contains("timeModified")) {
            summary.put("timeModified", o.getTimeModified());
        }
        if (requested.contains("storageTier")) {
            summary.put("storageTier", o.getStorageTier());
        }
        return summary;
    }

    private static Map<String, Object> multipartJson(StoredMultipartUpload u) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("namespace", u.getNamespace());
        json.put("bucket", u.getBucket());
        json.put("object", u.getObject());
        json.put("uploadId", u.getUploadId());
        json.put("timeCreated", u.getTimeCreated());
        return json;
    }

    private Map<String, Object> parJson(StoredPar par, boolean includeUri) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", par.getId());
        json.put("name", par.getName());
        json.put("accessType", par.getAccessType());
        json.put("timeExpires", par.getTimeExpires());
        json.put("timeCreated", par.getTimeCreated());
        if (par.getObjectName() != null) {
            json.put("objectName", par.getObjectName());
        }
        if (par.getBucketListingAction() != null) {
            json.put("bucketListingAction", par.getBucketListingAction());
        }
        String accessPath = "/p/" + par.getToken() + "/n/" + par.getNamespace()
                + "/b/" + par.getBucket() + "/o/"
                + (par.getObjectName() != null ? par.getObjectName() : "");
        if (includeUri) {
            // accessUri is only returned on create — the token cannot be retrieved later.
            json.put("accessUri", accessPath);
        }
        json.put("fullPath", accessPath);
        return json;
    }

    private static Map<String, String> opcMeta(HttpHeaders headers) {
        Map<String, String> meta = new LinkedHashMap<>();
        headers.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("opc-meta-") && !values.isEmpty()) {
                meta.put(lower.substring("opc-meta-".length()), values.get(0));
            }
        });
        return meta;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> partsToCommit(Map<String, Object> body) {
        if (body == null || !(body.get("partsToCommit") instanceof List<?> list)) {
            return null;
        }
        return (List<Map<String, Object>>) list;
    }
}
