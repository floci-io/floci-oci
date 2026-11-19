package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Amazon S3 Compatibility API (Object/Tasks/s3compatibleapi.htm), path style, over the same
 * buckets and objects the native Object Storage API serves. Reached through
 * {@link S3CompatHostFilter}, which rewrites {@code <namespace>.compat.objectstorage.<region>...}
 * requests onto {@code /_s3compat/<namespace>}. Errors are S3 XML, never the OCI JSON shape.
 *
 * <p>Oracle's supported list covers bucket, object, multipart and bucket-tagging operations;
 * CopyObject and ListObjectsV2 are served too because boto3, the AWS CLI and rclone depend on them.
 * Single-part ETags are the hex MD5 S3 clients expect, multipart ETags the hex MD5 of the part
 * MD5s plus {@code -N}.
 */
@Path("/_s3compat/{namespace}")
@ApplicationScoped
public class S3CompatResource {

    private static final Logger LOG = Logger.getLogger(S3CompatResource.class);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final String META_PREFIX = "x-amz-meta-";
    /**
     * Bucket subresources S3 defines. A request naming one floci-oci does not serve answers
     * NotImplemented instead of falling through to the plain bucket operation.
     */
    private static final Set<String> BUCKET_SUBRESOURCES = Set.of("accelerate", "acl", "analytics", "cors",
            "encryption", "intelligent-tiering", "inventory", "lifecycle", "location", "logging", "metrics",
            "notification", "object-lock", "ownershipControls", "policy", "policyStatus", "publicAccessBlock",
            "replication", "requestPayment", "tagging", "uploads", "versioning", "website");

    private final ObjectStorageService service;
    private final OciContext ociContext;

    @Inject
    public S3CompatResource(ObjectStorageService service, OciContext ociContext) {
        this.service = service;
        this.ociContext = ociContext;
    }

    // ── Service ────────────────────────────────────────────────────────────────

    @GET
    public Response listBuckets(@PathParam("namespace") String namespace) {
        return s3("/", () -> {
            service.requireNamespace(namespace);
            String compartment = (String) service.namespaceMetadata(namespace).get("defaultS3CompartmentId");
            S3Xml xml = S3Xml.document("ListAllMyBucketsResult")
                    .open("Owner").element("ID", ociContext.tenancyId()).element("DisplayName", namespace)
                    .close("Owner").open("Buckets");
            for (StoredBucket b : service.listBuckets(namespace, compartment)) {
                xml.open("Bucket").element("Name", b.getName())
                        .element("CreationDate", iso(b.getTimeCreated())).close("Bucket");
            }
            return xmlResponse(200, xml.close("Buckets").end("ListAllMyBucketsResult"));
        });
    }

    // ── Buckets ────────────────────────────────────────────────────────────────

    @PUT
    @Path("/{bucket}")
    @Consumes(MediaType.WILDCARD)
    public Response putBucket(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                              @Context UriInfo uri, InputStream body) {
        return s3("/" + bucket, () -> {
            service.requireNamespace(namespace);
            Response unsupported = unsupportedSubresource(uri, Set.of("tagging"), "/" + bucket);
            if (unsupported != null) {
                return unsupported;
            }
            if (has(uri, "tagging")) {
                Map<String, String> tags = new LinkedHashMap<>();
                Document doc = S3Xml.parse(readAll(body));
                Element tagSet = S3Xml.children(doc.getDocumentElement(), "TagSet").stream().findFirst().orElse(null);
                if (tagSet != null) {
                    for (Element tag : S3Xml.children(tagSet, "Tag")) {
                        tags.put(S3Xml.childText(tag, "Key"), S3Xml.childText(tag, "Value"));
                    }
                }
                service.updateBucket(namespace, bucket, null, null, null, tags, null, null, null);
                return Response.noContent().build();
            }
            String compartment = (String) service.namespaceMetadata(namespace).get("defaultS3CompartmentId");
            service.createBucket(namespace, bucket, compartment, null, null, null, null, null, null, null);
            return Response.ok().header("Location", "/" + bucket).build();
        });
    }

    @HEAD
    @Path("/{bucket}")
    public Response headBucket(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket) {
        return s3("/" + bucket, () -> {
            service.requireNamespace(namespace);
            service.getBucket(namespace, bucket);
            return Response.ok().header("x-amz-bucket-region", ociContext.region()).build();
        });
    }

    @DELETE
    @Path("/{bucket}")
    public Response deleteBucket(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                                 @Context UriInfo uri) {
        return s3("/" + bucket, () -> {
            service.requireNamespace(namespace);
            Response unsupported = unsupportedSubresource(uri, Set.of("tagging"), "/" + bucket);
            if (unsupported != null) {
                return unsupported;
            }
            if (has(uri, "tagging")) {
                service.updateBucket(namespace, bucket, null, null, null, Map.of(), null, null, null);
                return Response.noContent().build();
            }
            service.deleteBucket(namespace, bucket, null);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/{bucket}")
    public Response getBucket(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                              @Context UriInfo uri) {
        return s3("/" + bucket, () -> {
            service.requireNamespace(namespace);
            Response unsupported = unsupportedSubresource(uri, Set.of("location", "tagging", "versioning", "uploads"),
                    "/" + bucket);
            if (unsupported != null) {
                return unsupported;
            }
            StoredBucket b = service.getBucket(namespace, bucket);
            if (has(uri, "location")) {
                return xmlResponse(200, S3Xml.document("LocationConstraint").end("LocationConstraint")
                        .replace("></LocationConstraint>", ">" + S3Xml.escape(ociContext.region())
                                + "</LocationConstraint>"));
            }
            if (has(uri, "tagging")) {
                S3Xml xml = S3Xml.document("Tagging").open("TagSet");
                if (b.getFreeformTags() != null) {
                    b.getFreeformTags().forEach((k, v) -> xml.open("Tag").element("Key", k).element("Value", v)
                            .close("Tag"));
                }
                return xmlResponse(200, xml.close("TagSet").end("Tagging"));
            }
            if (has(uri, "versioning")) {
                return xmlResponse(200, S3Xml.document("VersioningConfiguration").end("VersioningConfiguration"));
            }
            if (has(uri, "uploads")) {
                return listMultipartUploads(namespace, bucket);
            }
            return listObjects(namespace, bucket, uri.getQueryParameters());
        });
    }

    @POST
    @Path("/{bucket}")
    @Consumes(MediaType.WILDCARD)
    public Response postBucket(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                               @Context UriInfo uri, InputStream body) {
        return s3("/" + bucket, () -> {
            service.requireNamespace(namespace);
            if (!has(uri, "delete")) {
                return error(400, "InvalidRequest", "Unsupported bucket POST", "/" + bucket);
            }
            Document doc = S3Xml.parse(readAll(body));
            String quietText = S3Xml.childText(doc.getDocumentElement(), "Quiet");
            boolean quiet = quietText != null && "true".equalsIgnoreCase(quietText.trim());
            List<ObjectStorageService.BatchDeleteItem> items = new ArrayList<>();
            for (Element object : S3Xml.children(doc.getDocumentElement(), "Object")) {
                items.add(new ObjectStorageService.BatchDeleteItem(S3Xml.childText(object, "Key"), null));
            }
            ObjectStorageService.BatchDeleteResult result = service.batchDeleteObjects(namespace, bucket, items);
            S3Xml xml = S3Xml.document("DeleteResult");
            if (!quiet) {
                result.deleted().forEach(d -> xml.open("Deleted").element("Key", d.objectName()).close("Deleted"));
            }
            for (ObjectStorageService.BatchDeleteResult.FailedDelete f : result.failed()) {
                if (f.statusCode() == 404) {
                    // S3 reports deleting a missing key as a success.
                    if (!quiet) {
                        xml.open("Deleted").element("Key", f.objectName()).close("Deleted");
                    }
                    continue;
                }
                xml.open("Error").element("Key", f.objectName()).element("Code", "InternalError")
                        .element("Message", f.errorMessage()).close("Error");
            }
            return xmlResponse(200, xml.end("DeleteResult"));
        });
    }

    private Response listObjects(String namespace, String bucket, MultivaluedMap<String, String> query) {
        boolean v2 = "2".equals(query.getFirst("list-type"));
        String prefix = query.getFirst("prefix");
        String delimiter = query.getFirst("delimiter");
        int maxKeys = parseInt(query.getFirst("max-keys"), 1000);
        String start;
        if (v2) {
            String token = query.getFirst("continuation-token");
            String startAfter = query.getFirst("start-after");
            start = token != null ? resumeAfter(decodeToken(token), prefix, delimiter)
                    : startAfter != null ? startAfterPosition(startAfter, prefix, delimiter) : null;
        } else {
            String marker = query.getFirst("marker");
            start = marker != null ? resumeAfter(marker, prefix, delimiter) : null;
        }
        ObjectStorageService.ObjectListing listing = maxKeys == 0
                ? new ObjectStorageService.ObjectListing(List.of(), List.of(), null)
                : service.listObjects(namespace, bucket, prefix, start, null, delimiter, maxKeys);
        String last = lastName(listing);
        boolean truncated = listing.nextStartWith() != null;
        if (truncated && isCommonPrefix(last, prefix, delimiter) && listing.nextStartWith().startsWith(last)) {
            // The native listing stopped inside the last folder returned; more is left only after it.
            String after = resumeAfter(last, prefix, delimiter);
            truncated = !service.listObjects(namespace, bucket, prefix, after, null, null, 1).objects().isEmpty();
        }
        // encoding-type=url: names are URL-encoded so keys with characters XML cannot carry still list.
        boolean url = "url".equalsIgnoreCase(query.getFirst("encoding-type"));
        String root = "ListBucketResult";
        S3Xml xml = S3Xml.document(root)
                .element("Name", bucket)
                .element("Prefix", encodeName(prefix == null ? "" : prefix, url))
                .element("Delimiter", encodeName(delimiter, url))
                .element("MaxKeys", maxKeys)
                .element("IsTruncated", truncated);
        if (url) {
            xml.element("EncodingType", "url");
        }
        if (v2) {
            xml.element("KeyCount", listing.objects().size() + listing.prefixes().size());
            xml.element("ContinuationToken", query.getFirst("continuation-token"));
            xml.element("StartAfter", encodeName(query.getFirst("start-after"), url));
            if (truncated) {
                xml.element("NextContinuationToken", encodeToken(last));
            }
        } else {
            xml.element("Marker", encodeName(query.getFirst("marker") == null ? "" : query.getFirst("marker"), url));
            if (truncated && delimiter != null) {
                xml.element("NextMarker", encodeName(last, url));
            }
        }
        for (StoredOsObject o : listing.objects()) {
            xml.open("Contents")
                    .element("Key", encodeName(o.getName(), url))
                    .element("LastModified", iso(o.getTimeModified()))
                    .element("ETag", quotedEtag(o))
                    .element("Size", o.getData() == null ? 0 : o.getData().length)
                    .element("StorageClass", "STANDARD")
                    .close("Contents");
        }
        for (String p : listing.prefixes()) {
            xml.open("CommonPrefixes").element("Prefix", encodeName(p, url)).close("CommonPrefixes");
        }
        return xmlResponse(200, xml.end(root));
    }

    private Response listMultipartUploads(String namespace, String bucket) {
        S3Xml xml = S3Xml.document("ListMultipartUploadsResult").element("Bucket", bucket)
                .element("IsTruncated", false);
        for (StoredMultipartUpload u : service.listMultipartUploads(namespace, bucket)) {
            xml.open("Upload").element("Key", u.getObject()).element("UploadId", u.getUploadId())
                    .element("Initiated", iso(u.getTimeCreated())).element("StorageClass", "STANDARD")
                    .close("Upload");
        }
        return xmlResponse(200, xml.end("ListMultipartUploadsResult"));
    }

    // ── Objects ────────────────────────────────────────────────────────────────

    @PUT
    @Path("/{bucket}/{key: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response putObject(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                              @PathParam("key") String key, @Context UriInfo uri, @Context HttpHeaders headers,
                              InputStream body) {
        String resource = "/" + bucket + "/" + key;
        return s3(resource, () -> {
            service.requireNamespace(namespace);
            MultivaluedMap<String, String> query = uri.getQueryParameters();
            if (query.containsKey("uploadId") && query.containsKey("partNumber")) {
                byte[] data = readBody(headers, body);
                StoredMultipartUpload upload = service.getUpload(bucket, query.getFirst("uploadId"), key);
                service.uploadPart(namespace, bucket, key, upload.getUploadId(),
                        parseInt(query.getFirst("partNumber"), 0), data);
                return Response.ok().header("ETag", "\"" + md5Hex(data) + "\"").build();
            }
            String copySource = headers.getHeaderString("x-amz-copy-source");
            if (copySource != null) {
                return copyObject(namespace, bucket, key, copySource, headers);
            }
            byte[] data = readBody(headers, body);
            WriteConditions conditions = writeConditions(namespace, bucket, key, headers);
            StoredOsObject o = service.putObject(namespace, bucket, key, data, headers.getHeaderString("Content-Type"),
                    headers.getHeaderString("Content-MD5"), userMetadata(headers), conditions.ifMatch(),
                    conditions.ifNoneMatch());
            return Response.ok().header("ETag", quotedEtag(o)).build();
        });
    }

    private Response copyObject(String namespace, String bucket, String key, String copySource, HttpHeaders headers) {
        // The version query is split off before decoding: an encoded '?' belongs to the key.
        int query = copySource.indexOf('?');
        String source = URLDecoder.decode(query < 0 ? copySource : copySource.substring(0, query),
                StandardCharsets.UTF_8);
        if (source.startsWith("/")) {
            source = source.substring(1);
        }
        int slash = source.indexOf('/');
        if (slash <= 0) {
            return error(400, "InvalidArgument", "x-amz-copy-source must be <bucket>/<key>", "/" + bucket + "/" + key);
        }
        String sourceBucket = source.substring(0, slash);
        String sourceKey = source.substring(slash + 1);
        StoredOsObject original = service.getObject(namespace, sourceBucket, sourceKey);
        String sourceEtag = unquote(quotedEtag(original));
        String sourceIfMatch = blankToNull(headers.getHeaderString("x-amz-copy-source-if-match"));
        String sourceIfNoneMatch = blankToNull(headers.getHeaderString("x-amz-copy-source-if-none-match"));
        if ((sourceIfMatch != null && !matchesAny(sourceIfMatch, sourceEtag))
                || (sourceIfNoneMatch != null && matchesAny(sourceIfNoneMatch, sourceEtag))) {
            return error(412, "PreconditionFailed", "At least one of the pre-conditions you specified did not hold",
                    "/" + bucket + "/" + key);
        }
        StoredOsObject copied;
        if ("REPLACE".equalsIgnoreCase(headers.getHeaderString("x-amz-metadata-directive"))) {
            String contentType = headers.getHeaderString("Content-Type");
            copied = service.putObject(namespace, bucket, key, original.getData(),
                    contentType != null ? contentType : original.getContentType(), null, userMetadata(headers),
                    null, null);
        } else {
            // The native ETag of the object just checked, so a source replaced meanwhile fails the copy.
            service.copyObject(namespace, sourceBucket, sourceKey, ociContext.region(), namespace, bucket, key,
                    original.getEtag(), null);
            copied = service.getObject(namespace, bucket, key);
        }
        return xmlResponse(200, S3Xml.document("CopyObjectResult")
                .element("LastModified", iso(copied.getTimeModified()))
                .element("ETag", quotedEtag(copied))
                .end("CopyObjectResult"));
    }

    @GET
    @Path("/{bucket}/{key: .+}")
    public Response getObject(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                              @PathParam("key") String key, @Context UriInfo uri, @Context HttpHeaders headers) {
        String resource = "/" + bucket + "/" + key;
        return s3(resource, () -> {
            service.requireNamespace(namespace);
            if (uri.getQueryParameters().containsKey("uploadId")) {
                return listParts(bucket, key, uri.getQueryParameters().getFirst("uploadId"));
            }
            StoredOsObject o = service.getObject(namespace, bucket, key);
            Response failed = readConditions(o, headers, resource);
            if (failed != null) {
                return failed;
            }
            byte[] data = o.getData() == null ? new byte[0] : o.getData();
            String range = headers.getHeaderString("Range");
            if (range != null && range.startsWith("bytes=")) {
                long[] span = parseRange(range.substring(6), data.length);
                if (span == null) {
                    return error(416, "InvalidRange", "The requested range is not satisfiable", resource);
                }
                byte[] slice = Arrays.copyOfRange(data, (int) span[0], (int) span[1] + 1);
                return objectHeaders(Response.status(206).entity(slice), o)
                        .header("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + data.length)
                        .build();
            }
            return objectHeaders(Response.ok(data), o).build();
        });
    }

    @HEAD
    @Path("/{bucket}/{key: .+}")
    public Response headObject(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                               @PathParam("key") String key, @Context HttpHeaders headers) {
        String resource = "/" + bucket + "/" + key;
        return s3(resource, () -> {
            service.requireNamespace(namespace);
            StoredOsObject o = service.getObject(namespace, bucket, key);
            Response failed = readConditions(o, headers, resource);
            if (failed != null) {
                return failed;
            }
            return objectHeaders(Response.ok(), o)
                    .header("Content-Length", o.getData() == null ? 0 : o.getData().length).build();
        });
    }

    @DELETE
    @Path("/{bucket}/{key: .+}")
    public Response deleteObject(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                                 @PathParam("key") String key, @Context UriInfo uri) {
        return s3("/" + bucket + "/" + key, () -> {
            service.requireNamespace(namespace);
            String uploadId = uri.getQueryParameters().getFirst("uploadId");
            if (uploadId != null) {
                service.abortMultipartUpload(namespace, bucket, key, uploadId);
                return Response.noContent().build();
            }
            service.getBucket(namespace, bucket);
            try {
                service.deleteObject(namespace, bucket, key, null, null);
            } catch (OciException e) {
                // S3 answers 204 for a key that is not there.
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
            }
            return Response.noContent().build();
        });
    }

    @POST
    @Path("/{bucket}/{key: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response postObject(@PathParam("namespace") String namespace, @PathParam("bucket") String bucket,
                               @PathParam("key") String key, @Context UriInfo uri, @Context HttpHeaders headers,
                               InputStream body) {
        String resource = "/" + bucket + "/" + key;
        return s3(resource, () -> {
            service.requireNamespace(namespace);
            MultivaluedMap<String, String> query = uri.getQueryParameters();
            if (query.containsKey("uploads")) {
                StoredMultipartUpload u = service.createMultipartUpload(namespace, bucket, key,
                        headers.getHeaderString("Content-Type"), userMetadata(headers));
                return xmlResponse(200, S3Xml.document("InitiateMultipartUploadResult")
                        .element("Bucket", bucket).element("Key", key).element("UploadId", u.getUploadId())
                        .end("InitiateMultipartUploadResult"));
            }
            String uploadId = query.getFirst("uploadId");
            if (uploadId == null) {
                return error(400, "InvalidRequest", "Unsupported object POST", resource);
            }
            StoredMultipartUpload upload = service.getUpload(bucket, uploadId, key);
            Document doc = S3Xml.parse(readAll(body));
            List<Map<String, Object>> parts = new ArrayList<>();
            int previous = 0;
            for (Element part : S3Xml.children(doc.getDocumentElement(), "Part")) {
                int number = parseInt(S3Xml.childText(part, "PartNumber"), 0);
                String etag = S3Xml.childText(part, "ETag");
                if (number <= previous) {
                    return error(400, "InvalidPartOrder",
                            "The list of parts was not in ascending order. Parts must be ordered by part number.",
                            resource);
                }
                previous = number;
                StoredMultipartUpload.Part stored = upload.getParts().get(number);
                if (stored == null || etag == null || !unquote(etag).equals(md5Hex(stored.getData()))) {
                    return error(400, "InvalidPart", "Part " + number + " was not uploaded with that ETag", resource);
                }
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("partNum", number);
                ref.put("etag", stored.getEtag());
                parts.add(ref);
            }
            StoredOsObject o = service.commitMultipartUpload(namespace, bucket, key, uploadId, parts);
            return xmlResponse(200, S3Xml.document("CompleteMultipartUploadResult")
                    .element("Location", "/" + bucket + "/" + key)
                    .element("Bucket", bucket).element("Key", key).element("ETag", quotedEtag(o))
                    .end("CompleteMultipartUploadResult"));
        });
    }

    private Response listParts(String bucket, String key, String uploadId) {
        StoredMultipartUpload upload = service.getUpload(bucket, uploadId, key);
        S3Xml xml = S3Xml.document("ListPartsResult").element("Bucket", bucket).element("Key", key)
                .element("UploadId", uploadId).element("IsTruncated", false);
        upload.getParts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> xml.open("Part")
                .element("PartNumber", e.getKey())
                .element("LastModified", iso(upload.getTimeCreated()))
                .element("ETag", "\"" + md5Hex(e.getValue().getData()) + "\"")
                .element("Size", e.getValue().getData().length)
                .close("Part"));
        return xmlResponse(200, xml.end("ListPartsResult"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The native preconditions a conditional S3 PutObject turns into. */
    private record WriteConditions(String ifMatch, String ifNoneMatch) {
    }

    /**
     * Translates {@code If-Match} and {@code If-None-Match} on PutObject. S3 compares them with the
     * hex-MD5 ETag this API exposes, not the native one, so {@code If-Match} is checked here and then
     * handed to the write as the native ETag of the object it matched, which keeps the write
     * conditional on that object still being there. {@code If-None-Match: *} passes through: the
     * write fails when any object exists. A failed condition is {@code 412 PreconditionFailed}, and
     * {@code If-Match} on a missing key is {@code 404 NoSuchKey}.
     */
    private WriteConditions writeConditions(String namespace, String bucket, String key, HttpHeaders headers) {
        String ifMatch = blankToNull(headers.getHeaderString("If-Match"));
        String ifNoneMatch = blankToNull(headers.getHeaderString("If-None-Match"));
        if (ifMatch == null && (ifNoneMatch == null || "*".equals(ifNoneMatch))) {
            return new WriteConditions(null, ifNoneMatch);
        }
        StoredOsObject current;
        try {
            current = service.getObject(namespace, bucket, key);
        } catch (OciException e) {
            if (ifMatch != null || !"ObjectNotFound".equals(e.getCode())) {
                throw e;
            }
            // If-None-Match with an ETag holds for a key that is not there.
            return new WriteConditions(null, null);
        }
        String currentEtag = unquote(quotedEtag(current));
        if (ifMatch != null && !matchesAny(ifMatch, currentEtag)) {
            throw OciException.noEtagMatch("At least one of the pre-conditions you specified did not hold");
        }
        if (ifNoneMatch != null && ("*".equals(ifNoneMatch) || matchesAny(ifNoneMatch, currentEtag))) {
            throw OciException.ifNoneMatchFailed("At least one of the pre-conditions you specified did not hold");
        }
        return new WriteConditions(current.getEtag(), null);
    }

    private static boolean matchesAny(String header, String etag) {
        for (String candidate : header.split(",")) {
            if ("*".equals(candidate.trim()) || unquote(candidate).equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @FunctionalInterface
    private interface S3Call {
        Response run() throws Exception;
    }

    /** Runs one S3 operation, turning the OCI errors it raises into S3 error XML. */
    private Response s3(String resource, S3Call call) {
        try {
            return call.run();
        } catch (OciException e) {
            return switch (e.getCode()) {
                case "BucketNotFound", "NamespaceNotFound" -> error(404, "NoSuchBucket", e.getMessage(), resource);
                case "ObjectNotFound" -> error(404, "NoSuchKey", e.getMessage(), resource);
                case "BucketAlreadyExists" -> error(409, "BucketAlreadyOwnedByYou", e.getMessage(), resource);
                case "BucketNotEmpty", "Conflict" -> error(409, "BucketNotEmpty", e.getMessage(), resource);
                case "NotAuthorizedOrNotFound" -> error(404, "NoSuchUpload", e.getMessage(), resource);
                case "NoEtagMatch", "IfNoneMatchFailed" -> error(412, "PreconditionFailed", e.getMessage(), resource);
                case "InvalidParameter", "MissingParameter" -> error(400, "InvalidArgument", e.getMessage(), resource);
                default -> error(e.getHttpStatus() >= 500 ? e.getHttpStatus() : 400, e.getCode(), e.getMessage(),
                        resource);
            };
        } catch (Exception e) {
            LOG.warnv("S3 compatibility request {0} failed: {1}", resource, e.getMessage());
            boolean malformed = e instanceof SAXException;
            return malformed
                    ? error(400, "MalformedXML", "The XML you provided was not well-formed", resource)
                    : error(500, "InternalError", e.getMessage(), resource);
        }
    }

    private static Response error(int status, String code, String message, String resource) {
        String requestId = UUID.randomUUID().toString();
        return Response.status(status).type(S3Xml.CONTENT_TYPE)
                .entity(S3Xml.error(code, message, resource, requestId))
                .header("x-amz-request-id", requestId)
                .build();
    }

    private static Response xmlResponse(int status, String xml) {
        return Response.status(status).type(S3Xml.CONTENT_TYPE).entity(xml).build();
    }

    private static Response.ResponseBuilder objectHeaders(Response.ResponseBuilder builder, StoredOsObject o) {
        builder.type(o.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : o.getContentType())
                .header("ETag", quotedEtag(o))
                .header("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.parse(o.getTimeModified()).atOffset(ZoneOffset.UTC)))
                .header("Accept-Ranges", "bytes");
        if (o.getMetadata() != null) {
            o.getMetadata().forEach((k, v) -> builder.header(META_PREFIX + k, v));
        }
        return builder;
    }

    /** The S3 ETag: hex MD5 for a single-part object, hex MD5 of the part MD5s plus {@code -N} for a multipart one. */
    static String quotedEtag(StoredOsObject o) {
        String multipart = o.getMultipartMd5();
        if (multipart != null && multipart.contains("-")) {
            int dash = multipart.lastIndexOf('-');
            return "\"" + HexFormat.of().formatHex(Base64.getDecoder().decode(multipart.substring(0, dash)))
                    + multipart.substring(dash) + "\"";
        }
        return "\"" + HexFormat.of().formatHex(Base64.getDecoder().decode(o.getMd5())) + "\"";
    }

    private static String md5Hex(byte[] data) {
        return HexFormat.of().formatHex(Base64.getDecoder().decode(ObjectStorageService.md5Base64(data)));
    }

    private static Map<String, String> userMetadata(HttpHeaders headers) {
        Map<String, String> metadata = new LinkedHashMap<>();
        headers.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(META_PREFIX) && !values.isEmpty()) {
                metadata.put(lower.substring(META_PREFIX.length()), values.get(0));
            }
        });
        return metadata;
    }

    private static byte[] readBody(HttpHeaders headers, InputStream body) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        InputStream in = AwsChunkedInputStream.isAwsChunked(headers.getHeaderString("Content-Encoding"),
                headers.getHeaderString("x-amz-content-sha256")) ? new AwsChunkedInputStream(body) : body;
        return in.readAllBytes();
    }

    private static byte[] readAll(InputStream body) throws IOException {
        return body == null ? new byte[0] : body.readAllBytes();
    }

    /**
     * GetObject and HeadObject conditions, against the S3 ETag: {@code If-Match} that fails is
     * {@code 412 PreconditionFailed}, {@code If-None-Match} that matches is {@code 304 Not Modified}.
     */
    private static Response readConditions(StoredOsObject o, HttpHeaders headers, String resource) {
        String etag = unquote(quotedEtag(o));
        String ifMatch = blankToNull(headers.getHeaderString("If-Match"));
        if (ifMatch != null && !matchesAny(ifMatch, etag)) {
            return error(412, "PreconditionFailed", "At least one of the pre-conditions you specified did not hold",
                    resource);
        }
        String ifNoneMatch = blankToNull(headers.getHeaderString("If-None-Match"));
        if (ifNoneMatch != null && matchesAny(ifNoneMatch, etag)) {
            return Response.notModified().header("ETag", quotedEtag(o)).build();
        }
        return null;
    }

    private static Response unsupportedSubresource(UriInfo uri, Set<String> supported, String resource) {
        for (String parameter : uri.getQueryParameters().keySet()) {
            if (BUCKET_SUBRESOURCES.contains(parameter) && !supported.contains(parameter)) {
                return error(501, "NotImplemented", "The ?" + parameter + " bucket operation is not implemented",
                        resource);
            }
        }
        return null;
    }

    /** S3's {@code encoding-type=url}: percent-encoding, with spaces as {@code %20} and {@code /} kept. */
    static String encodeName(String name, boolean url) {
        if (!url || name == null) {
            return name;
        }
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    /**
     * Where a v2 listing with {@code start-after} begins. With a delimiter, a {@code start-after}
     * inside a folder skips that whole folder: its common prefix sorts at or before {@code start-after},
     * and S3 omits those.
     */
    static String startAfterPosition(String startAfter, String prefix, String delimiter) {
        String folder = commonPrefixOf(startAfter, prefix, delimiter);
        return folder != null ? folder + '\uffff' : startAfter + '\u0000';
    }

    private static String commonPrefixOf(String name, String prefix, String delimiter) {
        String base = prefix == null ? "" : prefix;
        if (delimiter == null || delimiter.isEmpty() || !name.startsWith(base)) {
            return null;
        }
        int at = name.indexOf(delimiter, base.length());
        return at < 0 ? null : name.substring(0, at + delimiter.length());
    }

    private static boolean has(UriInfo uri, String parameter) {
        return uri.getQueryParameters().containsKey(parameter);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code [first, last]} for a single {@code a-b}, {@code a-} or {@code -n} range, or null when unsatisfiable. */
    static long[] parseRange(String spec, long length) {
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.contains(",") || length == 0) {
            return null;
        }
        try {
            String from = spec.substring(0, dash).trim();
            String to = spec.substring(dash + 1).trim();
            long first;
            long last;
            if (from.isEmpty()) {
                long suffix = Long.parseLong(to);
                first = Math.max(0, length - suffix);
                last = length - 1;
            } else {
                first = Long.parseLong(from);
                last = to.isEmpty() ? length - 1 : Math.min(Long.parseLong(to), length - 1);
            }
            return first > last || first >= length ? null : new long[]{first, last};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String etag) {
        return etag.replace("\"", "").trim();
    }

    private static String encodeToken(String nextStartWith) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nextStartWith.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter("Invalid continuation token");
        }
    }

    /**
     * Where a page resumes after {@code last}, the last key or common prefix the previous page
     * returned. After a common prefix it skips every key under that prefix, so a folder is never
     * listed twice and a listing cannot repeat the same page.
     */
    static String resumeAfter(String last, String prefix, String delimiter) {
        return isCommonPrefix(last, prefix, delimiter) ? last + '\uffff' : last + '\u0000';
    }

    /** Whether {@code name} is a common prefix of a listing under {@code prefix}, rather than a key. */
    private static boolean isCommonPrefix(String name, String prefix, String delimiter) {
        if (name == null || delimiter == null || delimiter.isEmpty()) {
            return false;
        }
        String base = prefix == null ? "" : prefix;
        return name.startsWith(base) && name.length() > base.length() && name.endsWith(delimiter);
    }

    private static String lastName(ObjectStorageService.ObjectListing listing) {
        String lastObject = listing.objects().isEmpty() ? null : listing.objects().getLast().getName();
        String lastPrefix = listing.prefixes().isEmpty() ? null : listing.prefixes().getLast();
        if (lastObject == null) {
            return lastPrefix;
        }
        return lastPrefix == null || lastObject.compareTo(lastPrefix) > 0 ? lastObject : lastPrefix;
    }

    private static String iso(String instant) {
        return instant == null ? null : ISO_MILLIS.format(Instant.parse(instant));
    }
}
