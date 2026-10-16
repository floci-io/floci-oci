package io.floci.oci.services.objectstorage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.TenancyNames;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredPar;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

@ApplicationScoped
public class ObjectStorageService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(ObjectStorageService.class);

    private static final String NAMESPACE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int NAMESPACE_LENGTH = 12;

    private final StorageBackend<String, StoredBucket> buckets;
    private final StorageBackend<String, StoredOsObject> objects;
    private final StorageBackend<String, StoredMultipartUpload> uploads;
    private final StorageBackend<String, StoredPar> pars;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    /**
     * The tenancy each pre-authenticated request token was last found in, so a token in use is not
     * looked up across every partition each time. Checked against the store on every use.
     */
    private final Map<String, String> parOwners = new ConcurrentHashMap<>();

    @Inject
    public ObjectStorageService(StorageFactory storageFactory, EmulatorConfig config,
                                ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                                OciContext ociContext) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.buckets = storageFactory.create("objectstorage", "objectstorage-buckets.json",
                new TypeReference<Map<String, StoredBucket>>() {});
        this.objects = storageFactory.create("objectstorage", "objectstorage-objects.json",
                new TypeReference<Map<String, StoredOsObject>>() {});
        this.uploads = storageFactory.create("objectstorage", "objectstorage-uploads.json",
                new TypeReference<Map<String, StoredMultipartUpload>>() {});
        this.pars = storageFactory.create("objectstorage", "objectstorage-pars.json",
                new TypeReference<Map<String, StoredPar>>() {});
        storageFactory.afterLoad(this::migrateSavedNamespaces);
    }

    ObjectStorageService(StorageBackend<String, StoredBucket> buckets,
                         StorageBackend<String, StoredOsObject> objects,
                         StorageBackend<String, StoredMultipartUpload> uploads,
                         StorageBackend<String, StoredPar> pars,
                         EmulatorConfig config,
                         WorkRequestService workRequests) {
        this.ociContext = OciContext.fromConfig(config);
        this.buckets = buckets;
        this.objects = objects;
        this.uploads = uploads;
        this.pars = pars;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
    }

    /**
     * Rewrites the namespace saved on buckets and multipart uploads to their tenancy's own. Data
     * written before each tenancy had its own namespace carries the configured default, which
     * clients would send back and get 404 NamespaceNotFound for. Pre-authenticated requests keep
     * theirs: it is part of the access URI already handed out.
     *
     * @return the number of entries rewritten
     */
    int migrateSavedNamespaces() {
        int rewritten = migrateSavedNamespaces(buckets, StoredBucket::getNamespace, StoredBucket::setNamespace)
                + migrateSavedNamespaces(uploads, StoredMultipartUpload::getNamespace,
                        StoredMultipartUpload::setNamespace);
        if (rewritten > 0) {
            LOG.infov("Moved {0} Object Storage buckets and uploads to their tenancy's namespace", rewritten);
        }
        return rewritten;
    }

    private <V> int migrateSavedNamespaces(StorageBackend<String, V> store, Function<V, String> namespaceOfEntry,
                                           BiConsumer<V, String> setNamespace) {
        if (!(store instanceof TenancyAwareStorageBackend<V> tenancyAware)) {
            return 0;
        }
        int rewritten = 0;
        for (String partition : tenancyAware.tenancies()) {
            String namespace = namespaceOf(TenancyAwareStorageBackend.tenancyOf(partition));
            Map<String, V> updates = new LinkedHashMap<>();
            for (String key : tenancyAware.keysForTenancy(partition)) {
                tenancyAware.getForTenancy(partition, key)
                        .filter(entry -> !namespace.equals(namespaceOfEntry.apply(entry)))
                        .ifPresent(entry -> {
                            setNamespace.accept(entry, namespace);
                            updates.put(key, entry);
                        });
            }
            if (!updates.isEmpty()) {
                tenancyAware.putAllForTenancy(partition, updates);
                rewritten += updates.size();
            }
        }
        if (rewritten > 0) {
            tenancyAware.flush();
        }
        return rewritten;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("objectstorage")
                .enabled(config.services().objectstorage().enabled())
                .storageKey("objectstorage")
                .resourceClasses(ObjectStorageController.class, ParAccessController.class)
                .build());
    }

    // ── Namespace ──────────────────────────────────────────────────────────────

    /** The namespace of the calling tenancy: one per tenancy, the same in every region. */
    public String namespace() {
        return namespaceOf(ociContext.tenancyId());
    }

    /**
     * Each tenancy has one unique, uneditable namespace. The default tenancy keeps
     * {@code default-namespace}; any other gets a stable 12 character lowercase string derived
     * from its OCID, the shape real namespaces have (e.g. {@code axzbp3kgiaim}).
     */
    public String namespaceOf(String tenancyId) {
        if (tenancyId == null || tenancyId.equals(config.defaultTenancyId())) {
            return config.defaultNamespace();
        }
        return TenancyNames.derive(tenancyId, NAMESPACE_ALPHABET, NAMESPACE_LENGTH);
    }

    /**
     * GetNamespace with its optional {@code compartmentId}: a tenancy OCID names that tenancy's
     * namespace. A compartment OCID resolves to the caller's namespace, because the emulator
     * does not look compartments up across tenancies.
     */
    public String namespaceFor(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            return namespace();
        }
        if (!Ocids.isValid(compartmentId)) {
            throw OciException.invalidParameter("compartmentId is not a valid OCID: " + compartmentId);
        }
        return Ocids.isOfType(compartmentId, "tenancy") ? namespaceOf(compartmentId) : namespace();
    }

    /** A namespace in the path that is not the caller's is 404 NamespaceNotFound, as on real OCI. */
    public void requireNamespace(String namespaceName) {
        if (!namespace().equals(namespaceName)) {
            throw OciException.namespaceNotFound("Either the namespace " + namespaceName
                    + " does not exist or you are not authorized to access it.");
        }
    }

    public Map<String, Object> namespaceMetadata(String namespaceName) {
        return Map.of(
                "namespace", namespace(),
                "defaultS3CompartmentId", ociContext.tenancyId(),
                "defaultSwiftCompartmentId", ociContext.tenancyId());
    }

    // ── Buckets ────────────────────────────────────────────────────────────────

    public StoredBucket createBucket(String namespaceName, String name, String compartmentId,
                                     Map<String, String> metadata, String publicAccessType,
                                     String storageTier,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags) {
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("name is required");
        }
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        if (buckets.get(name).isPresent()) {
            throw OciException.bucketAlreadyExists(
                    "Either the bucket '" + name + "' already exists or you are not authorized to create it");
        }
        StoredBucket b = new StoredBucket();
        b.setNamespace(namespace());
        b.setName(name);
        b.setCompartmentId(compartmentId);
        b.setMetadata(metadata == null ? Map.of() : metadata);
        b.setCreatedBy(ociContext.userIdOrTenancy());
        b.setTimeCreated(Instant.now().toString());
        b.setEtag(Etags.newEtag());
        b.setPublicAccessType(publicAccessType != null ? publicAccessType : "NoPublicAccess");
        b.setStorageTier(storageTier != null ? storageTier : "Standard");
        b.setObjectEventsEnabled(false);
        b.setVersioning("Disabled");
        b.setId(Ocids.generate("bucket", ociContext.realm(), ociContext.regionCode()));
        b.setFreeformTags(freeformTags);
        b.setDefinedTags(definedTags);
        buckets.put(name, b);
        LOG.infof("createBucket %s", name);
        return b;
    }

    public StoredBucket getBucket(String namespaceName, String bucketName) {
        return buckets.get(bucketName)
                .orElseThrow(() -> OciException.bucketNotFound(
                        "Either the bucket named '" + bucketName + "' does not exist in the namespace '"
                                + namespace() + "' or you are not authorized to access it"));
    }

    public List<StoredBucket> listBuckets(String namespaceName, String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        return buckets.scan(k -> true).stream()
                .filter(b -> compartmentId.equals(b.getCompartmentId()))
                .sorted(Comparator.comparing(StoredBucket::getName))
                .toList();
    }

    public StoredBucket updateBucket(String namespaceName, String bucketName,
                                     Map<String, String> metadata, String publicAccessType,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags,
                                     String ifMatch) {
        StoredBucket b = getBucket(namespaceName, bucketName);
        Etags.checkIfMatch(ifMatch, b.getEtag());
        if (metadata != null) {
            b.setMetadata(metadata);
        }
        if (publicAccessType != null) {
            b.setPublicAccessType(publicAccessType);
        }
        if (freeformTags != null) {
            b.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            b.setDefinedTags(definedTags);
        }
        b.setEtag(Etags.newEtag());
        buckets.put(bucketName, b);
        return b;
    }

    public void deleteBucket(String namespaceName, String bucketName, String ifMatch) {
        StoredBucket b = getBucket(namespaceName, bucketName);
        Etags.checkIfMatch(ifMatch, b.getEtag());
        boolean hasObjects = !objects.scan(k -> k.startsWith(bucketName + "/")).isEmpty();
        if (hasObjects) {
            throw OciException.conflict("Bucket named '" + bucketName + "' is not empty. Delete all objects first.");
        }
        pars.scan(k -> true).stream()
                .filter(p -> bucketName.equals(p.getBucket()))
                .forEach(p -> pars.delete(p.getToken()));
        buckets.delete(bucketName);
    }

    // ── Objects ────────────────────────────────────────────────────────────────

    public StoredOsObject putObject(String namespaceName, String bucketName, String objectName,
                                    byte[] data, String contentType, String contentMd5,
                                    Map<String, String> metadata,
                                    String ifMatch, String ifNoneMatch) {
        getBucket(namespaceName, bucketName);
        String key = objectKey(bucketName, objectName);
        StoredOsObject existing = objects.get(key).orElse(null);
        Etags.checkIfMatch(ifMatch, existing != null ? existing.getEtag() : null);
        Etags.checkIfNoneMatch(ifNoneMatch, existing != null ? existing.getEtag() : null);

        String md5 = md5Base64(data);
        if (contentMd5 != null && !contentMd5.isBlank() && !contentMd5.equals(md5)) {
            throw OciException.invalidParameter(
                    "The Content-MD5 you specified did not match what we received.");
        }

        StoredOsObject o = new StoredOsObject();
        o.setName(objectName);
        o.setData(data);
        o.setMd5(md5);
        o.setEtag(Etags.newEtag());
        o.setContentType(contentType != null ? contentType : "application/octet-stream");
        o.setStorageTier("Standard");
        String now = Instant.now().toString();
        o.setTimeCreated(existing != null ? existing.getTimeCreated() : now);
        o.setTimeModified(now);
        o.setMetadata(metadata == null ? Map.of() : metadata);
        objects.put(key, o);
        return o;
    }

    public StoredOsObject getObject(String namespaceName, String bucketName, String objectName) {
        getBucket(namespaceName, bucketName);
        return objects.get(objectKey(bucketName, objectName))
                .orElseThrow(() -> OciException.objectNotFound(
                        "The object '" + objectName + "' does not exist in bucket '" + bucketName
                                + "' with namespace '" + namespace() + "'"));
    }

    public void deleteObject(String namespaceName, String bucketName, String objectName, String ifMatch) {
        StoredOsObject o = getObject(namespaceName, bucketName, objectName);
        Etags.checkIfMatch(ifMatch, o.getEtag());
        objects.delete(objectKey(bucketName, objectName));
    }

    record BatchDeleteItem(String objectName, String ifMatch) {
    }

    record BatchDeleteResult(List<DeletedObject> deleted, List<FailedDelete> failed) {
        record DeletedObject(String objectName, Instant timeDeleted) {
        }

        record FailedDelete(String objectName, int statusCode, String errorMessage) {
        }
    }

    BatchDeleteResult batchDeleteObjects(String namespaceName, String bucketName,
                                                List<BatchDeleteItem> items) {
        getBucket(namespaceName, bucketName);
        List<BatchDeleteResult.DeletedObject> deleted = new ArrayList<>();
        List<BatchDeleteResult.FailedDelete> failed = new ArrayList<>();
        for (BatchDeleteItem item : items) {
            try {
                StoredOsObject o = getObject(namespaceName, bucketName, item.objectName());
                Etags.checkIfMatch(item.ifMatch(), o.getEtag());
                objects.delete(objectKey(bucketName, item.objectName()));
                deleted.add(new BatchDeleteResult.DeletedObject(
                        item.objectName(), Instant.now()));
            } catch (OciException e) {
                failed.add(new BatchDeleteResult.FailedDelete(
                        item.objectName(), e.getHttpStatus(), e.getMessage()));
            }
        }
        return new BatchDeleteResult(deleted, failed);
    }

    public record ObjectListing(List<StoredOsObject> objects, List<String> prefixes, String nextStartWith) {
    }

    /**
     * ListObjects: lexicographic order, {@code prefix}/{@code start}/{@code end} filters,
     * delimiter grouping, and {@code nextStartWith} truncation — its own contract, not
     * the opc-next-page header pagination.
     */
    public ObjectListing listObjects(String namespaceName, String bucketName, String prefix,
                                     String start, String end, String delimiter, Integer limit) {
        getBucket(namespaceName, bucketName);
        int effectiveLimit = limit != null && limit > 0 ? Math.min(limit, 1000) : 1000;

        List<StoredOsObject> all = objects.scan(k -> k.startsWith(bucketName + "/")).stream()
                .filter(o -> prefix == null || o.getName().startsWith(prefix))
                .filter(o -> start == null || o.getName().compareTo(start) >= 0)
                .filter(o -> end == null || o.getName().compareTo(end) < 0)
                .sorted(Comparator.comparing(StoredOsObject::getName))
                .toList();

        List<StoredOsObject> selected = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        String nextStartWith = null;

        for (StoredOsObject o : all) {
            if (selected.size() + prefixes.size() >= effectiveLimit) {
                nextStartWith = o.getName();
                break;
            }
            if (delimiter != null && !delimiter.isEmpty()) {
                String relative = prefix != null ? o.getName().substring(prefix.length()) : o.getName();
                int idx = relative.indexOf(delimiter);
                if (idx >= 0) {
                    prefixes.add((prefix != null ? prefix : "") + relative.substring(0, idx + delimiter.length()));
                    continue;
                }
            }
            selected.add(o);
        }
        return new ObjectListing(selected, List.copyOf(prefixes), nextStartWith);
    }

    public StoredOsObject renameObject(String namespaceName, String bucketName,
                                       String sourceName, String newName,
                                       String srcIfMatch, String newIfMatch, String newIfNoneMatch) {
        StoredOsObject source = getObject(namespaceName, bucketName, sourceName);
        Etags.checkIfMatch(srcIfMatch, source.getEtag());
        StoredOsObject target = objects.get(objectKey(bucketName, newName)).orElse(null);
        Etags.checkIfMatch(newIfMatch, target != null ? target.getEtag() : null);
        Etags.checkIfNoneMatch(newIfNoneMatch, target != null ? target.getEtag() : null);

        source.setName(newName);
        source.setEtag(Etags.newEtag());
        source.setTimeModified(Instant.now().toString());
        objects.put(objectKey(bucketName, newName), source);
        objects.delete(objectKey(bucketName, sourceName));
        return source;
    }

    /**
     * Copy is async on real OCI: returns the work-request OCID for the 202 response.
     * CopyObject runs against the source region's endpoint and copies "within a region or to
     * another region": the destination bucket is looked up, and the copy written, in
     * {@code destRegion}. The work request stays in the source region, where clients poll it.
     */
    public String copyObject(String namespaceName, String bucketName, String sourceObjectName,
                             String destRegion, String destNamespace, String destBucket,
                             String destObjectName, String sourceIfMatch) {
        StoredOsObject source = getObject(namespaceName, bucketName, sourceObjectName);
        Etags.checkIfMatch(sourceIfMatch, source.getEtag());
        String destPartition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(), destRegion);
        if (TenancyAwareStorageBackend.getIn(buckets, destPartition, destBucket).isEmpty()) {
            throw OciException.bucketNotFound("Either the bucket named '" + destBucket
                    + "' does not exist in the namespace '" + destNamespace + "' in region " + destRegion
                    + " or you are not authorized to access it");
        }

        StoredOsObject copy = new StoredOsObject();
        copy.setName(destObjectName);
        copy.setData(source.getData());
        copy.setMd5(source.getMd5());
        copy.setEtag(Etags.newEtag());
        copy.setContentType(source.getContentType());
        copy.setStorageTier(source.getStorageTier());
        String now = Instant.now().toString();
        copy.setTimeCreated(now);
        copy.setTimeModified(now);
        copy.setMetadata(source.getMetadata());
        TenancyAwareStorageBackend.putIn(objects, destPartition, objectKey(destBucket, destObjectName), copy);

        StoredBucket bucket = getBucket(namespaceName, bucketName);
        // Object Storage work requests terminate in COMPLETED (Identity uses SUCCEEDED).
        return workRequests.completed("objectstorage", "COPY_OBJECT", bucket.getCompartmentId(),
                List.of(WorkRequestService.resource("object", "CREATED", destObjectName,
                        "/n/" + namespace() + "/b/" + destBucket + "/o/" + destObjectName)),
                "COMPLETED");
    }

    // ── Multipart uploads ──────────────────────────────────────────────────────

    public StoredMultipartUpload createMultipartUpload(String namespaceName, String bucketName,
                                                       String objectName, String contentType,
                                                       Map<String, String> metadata) {
        getBucket(namespaceName, bucketName);
        if (objectName == null || objectName.isBlank()) {
            throw OciException.missingParameter("object is required");
        }
        StoredMultipartUpload u = new StoredMultipartUpload();
        u.setNamespace(namespace());
        u.setBucket(bucketName);
        u.setObject(objectName);
        u.setUploadId(UUID.randomUUID().toString());
        u.setTimeCreated(Instant.now().toString());
        u.setContentType(contentType);
        u.setMetadata(metadata);
        uploads.put(uploadKey(bucketName, u.getUploadId()), u);
        return u;
    }

    public record UploadedPart(String etag, String md5) {
    }

    public UploadedPart uploadPart(String namespaceName, String bucketName, String objectName,
                                   String uploadId, int partNum, byte[] data) {
        StoredMultipartUpload u = getUpload(bucketName, uploadId, objectName);
        if (partNum < 1 || partNum > 10000) {
            throw OciException.invalidParameter("uploadPartNum must be between 1 and 10000");
        }
        String etag = Etags.newEtag();
        u.getParts().put(partNum, new StoredMultipartUpload.Part(etag, data));
        uploads.put(uploadKey(bucketName, uploadId), u);
        return new UploadedPart(etag, md5Base64(data));
    }

    public StoredOsObject commitMultipartUpload(String namespaceName, String bucketName,
                                                String objectName, String uploadId,
                                                List<Map<String, Object>> partsToCommit) {
        StoredMultipartUpload u = getUpload(bucketName, uploadId, objectName);
        if (partsToCommit == null || partsToCommit.isEmpty()) {
            throw OciException.invalidParameter("partsToCommit must contain at least one part");
        }
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        List<Map<String, Object>> ordered = partsToCommit.stream()
                .sorted(Comparator.comparingInt(p -> ((Number) p.get("partNum")).intValue()))
                .toList();
        for (Map<String, Object> partRef : ordered) {
            int partNum = ((Number) partRef.get("partNum")).intValue();
            String etag = (String) partRef.get("etag");
            StoredMultipartUpload.Part part = u.getParts().get(partNum);
            if (part == null || !part.getEtag().equals(etag)) {
                throw OciException.invalidParameter(
                        "Part " + partNum + " with etag " + etag + " was not uploaded.");
            }
            assembled.writeBytes(part.getData());
        }
        byte[] data = assembled.toByteArray();
        StoredOsObject o = putObject(namespaceName, bucketName, u.getObject(), data,
                u.getContentType(), null, u.getMetadata(), null, null);
        uploads.delete(uploadKey(bucketName, uploadId));
        return o;
    }

    public void abortMultipartUpload(String namespaceName, String bucketName,
                                     String objectName, String uploadId) {
        getUpload(bucketName, uploadId, objectName);
        uploads.delete(uploadKey(bucketName, uploadId));
    }

    public List<StoredMultipartUpload> listMultipartUploads(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return uploads.scan(k -> k.startsWith(bucketName + "/")).stream()
                .sorted(Comparator.comparing(StoredMultipartUpload::getTimeCreated))
                .toList();
    }

    private StoredMultipartUpload getUpload(String bucketName, String uploadId, String objectName) {
        StoredMultipartUpload u = uploads.get(uploadKey(bucketName, uploadId))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "No such upload: " + uploadId));
        if (objectName != null && !objectName.equals(u.getObject())) {
            throw OciException.notAuthorizedOrNotFound("No such upload for object: " + objectName);
        }
        return u;
    }

    // ── Pre-authenticated requests ─────────────────────────────────────────────

    public StoredPar createPar(String namespaceName, String bucketName, String name,
                               String accessType, String timeExpires, String objectName,
                               String bucketListingAction) {
        getBucket(namespaceName, bucketName);
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("name is required");
        }
        if (accessType == null || accessType.isBlank()) {
            throw OciException.missingParameter("accessType is required");
        }
        if (timeExpires == null || timeExpires.isBlank()) {
            throw OciException.missingParameter("timeExpires is required");
        }
        StoredPar par = new StoredPar();
        par.setId(UUID.randomUUID().toString().replace("-", "") + ":" + name);
        par.setName(name);
        par.setToken(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes()));
        par.setNamespace(namespace());
        par.setBucket(bucketName);
        par.setObjectName(objectName);
        par.setAccessType(accessType);
        par.setBucketListingAction(bucketListingAction);
        par.setTimeCreated(Instant.now().toString());
        par.setTimeExpires(timeExpires);
        pars.put(par.getToken(), par);
        return par;
    }

    public StoredPar getParById(String namespaceName, String bucketName, String parId) {
        return pars.scan(k -> true).stream()
                .filter(p -> bucketName.equals(p.getBucket()) && parId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("No such PAR: " + parId));
    }

    public List<StoredPar> listPars(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return pars.scan(k -> true).stream()
                .filter(p -> bucketName.equals(p.getBucket()))
                .sorted(Comparator.comparing(StoredPar::getTimeCreated))
                .toList();
    }

    public void deletePar(String namespaceName, String bucketName, String parId) {
        StoredPar par = getParById(namespaceName, bucketName, parId);
        pars.delete(par.getToken());
    }

    /** A pre-authenticated request and the tenancy that created it, which the token acts as. */
    public record ParAccess(StoredPar par, String tenancyId) {
    }

    /**
     * Resolves a PAR token for the anonymous {@code /p/{token}/n/{namespace}/…} data path. The
     * request carries no signature, so the token is looked up in every tenancy's partition for
     * the request region, and must have been created under the namespace in the path.
     */
    public ParAccess resolveParToken(String token, String namespaceName) {
        ParAccess access = findPar(token)
                .filter(found -> found.par().getNamespace().equals(namespaceName))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "The pre-authenticated request does not exist or is expired."));
        if (Instant.parse(access.par().getTimeExpires()).isBefore(Instant.now())) {
            throw OciException.notAuthorizedOrNotFound(
                    "The pre-authenticated request does not exist or is expired.");
        }
        return access;
    }

    private Optional<ParAccess> findPar(String token) {
        String region = ociContext.region();
        String owner = parOwners.get(token);
        if (owner != null) {
            Optional<StoredPar> par = TenancyAwareStorageBackend.getIn(pars,
                    TenancyAwareStorageBackend.regionalPartition(owner, region), token);
            if (par.isPresent()) {
                return Optional.of(new ParAccess(par.get(), owner));
            }
        }
        Optional<ParAccess> found = TenancyAwareStorageBackend.findInRegion(pars, region, token, ociContext.tenancyId())
                .map(owned -> new ParAccess(owned.value(), owned.tenancyId()));
        found.ifPresentOrElse(access -> parOwners.put(token, access.tenancyId()), () -> parOwners.remove(token));
        return found;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────


    private static String objectKey(String bucketName, String objectName) {
        return bucketName + "/" + objectName;
    }

    private static String uploadKey(String bucketName, String uploadId) {
        return bucketName + "/" + uploadId;
    }

    static String md5Base64(byte[] data) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(buckets, tenancyId, compartmentId, StoredBucket::getCompartmentId,
                        null, b -> "bucket " + b.getName()).toList();
    }
}
