package io.floci.oci.services.objectstorage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciEvents;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.tenancy.TenancyNames;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredLifecyclePolicy;
import io.floci.oci.services.objectstorage.model.StoredLifecycleRule;
import io.floci.oci.services.objectstorage.model.StoredMultipartUpload;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredPar;
import io.floci.oci.services.objectstorage.model.StoredReplicationPolicy;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

@ApplicationScoped
public class ObjectStorageService implements CompartmentUsage, Resettable {

    private static final Logger LOG = Logger.getLogger(ObjectStorageService.class);

    private static final String NAMESPACE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int NAMESPACE_LENGTH = 12;

    private final StorageBackend<String, StoredBucket> buckets;
    private final StorageBackend<String, StoredOsObject> objects;
    private final StorageBackend<String, StoredMultipartUpload> uploads;
    /**
     * One lock per multipart upload, so a part being saved cannot recreate an upload that a commit, an
     * abort or a lifecycle rule has just removed.
     */
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();
    private final StorageBackend<String, StoredPar> pars;
    private final ObjectVersions versions;
    private final BucketReplication replication;
    private final BucketLifecycle lifecycle;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    /** Bucket and object events go to the Events service through this, unresolvable when none is deployed. */
    private final Instance<OciEvents> events;
    /**
     * The tenancy each pre-authenticated request token was last found in, so a token in use is not
     * looked up across every partition each time. Checked against the store on every use.
     */
    private final Map<String, String> parOwners = new ConcurrentHashMap<>();

    @Inject
    public ObjectStorageService(StorageFactory storageFactory, EmulatorConfig config,
                                ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                                OciContext ociContext, Instance<OciEvents> events) {
        this.config = config;
        this.events = events;
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
        this.versions = new ObjectVersions(objects,
                storageFactory.create("objectstorage", "objectstorage-object-versions.json",
                        new TypeReference<Map<String, StoredOsObject>>() {}));
        this.replication = new BucketReplication(
                storageFactory.create("objectstorage", "objectstorage-replication.json",
                        new TypeReference<Map<String, StoredReplicationPolicy>>() {}),
                buckets, objects, ociContext);
        this.lifecycle = new BucketLifecycle(storageFactory.create("objectstorage", "objectstorage-lifecycle.json",
                new TypeReference<Map<String, StoredLifecyclePolicy>>() {}));
        storageFactory.afterLoad(this::migrateSavedNamespaces);
    }

    ObjectStorageService(StorageBackend<String, StoredBucket> buckets,
                         StorageBackend<String, StoredOsObject> objects,
                         StorageBackend<String, StoredMultipartUpload> uploads,
                         StorageBackend<String, StoredPar> pars,
                         EmulatorConfig config,
                         WorkRequestService workRequests) {
        this(buckets, objects, uploads, pars, new InMemoryStorage<>(), config, workRequests,
                OciContext.fromConfig(config));
    }

    ObjectStorageService(StorageBackend<String, StoredBucket> buckets,
                         StorageBackend<String, StoredOsObject> objects,
                         StorageBackend<String, StoredMultipartUpload> uploads,
                         StorageBackend<String, StoredPar> pars,
                         StorageBackend<String, StoredReplicationPolicy> policies,
                         EmulatorConfig config,
                         WorkRequestService workRequests,
                         OciContext ociContext) {
        this.ociContext = ociContext;
        this.buckets = buckets;
        this.objects = objects;
        this.uploads = uploads;
        this.pars = pars;
        this.versions = new ObjectVersions(objects, new InMemoryStorage<>());
        this.replication = new BucketReplication(policies, buckets, objects, ociContext);
        this.lifecycle = new BucketLifecycle(new InMemoryStorage<>());
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
        this.events = null;
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
        int rewritten = 0;
        for (Map.Entry<String, Map<String, V>> partition
                : TenancyAwareStorageBackend.entriesByPartition(store).entrySet()) {
            String namespace = namespaceOf(TenancyAwareStorageBackend.tenancyOf(partition.getKey()));
            Map<String, V> updates = new LinkedHashMap<>();
            partition.getValue().forEach((key, entry) -> {
                if (!namespace.equals(namespaceOfEntry.apply(entry))) {
                    setNamespace.accept(entry, namespace);
                    updates.put(key, entry);
                }
            });
            if (!updates.isEmpty()) {
                TenancyAwareStorageBackend.putAllIn(store, partition.getKey(), updates);
                rewritten += updates.size();
            }
        }
        if (rewritten > 0) {
            store.flush();
        }
        return rewritten;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("objectstorage")
                .enabled(config.services().objectstorage().enabled())
                .storageKey("objectstorage")
                .resourceClasses(ObjectStorageController.class, ParAccessController.class, S3CompatResource.class)
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
                                     String storageTier, Boolean objectEventsEnabled,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags,
                                     String versioning) {
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("name is required");
        }
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        String initialVersioning = versioning == null ? ObjectVersions.DISABLED
                : versioningValue(versioning, List.of(ObjectVersions.ENABLED, ObjectVersions.DISABLED));
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
        b.setObjectEventsEnabled(Boolean.TRUE.equals(objectEventsEnabled));
        b.setVersioning(initialVersioning);
        b.setId(Ocids.generate("bucket", ociContext.realm(), ociContext.regionCode()));
        b.setFreeformTags(freeformTags);
        b.setDefinedTags(definedTags);
        buckets.put(name, b);
        LOG.infof("createBucket %s", name);
        emitBucketEvent("createbucket", b);
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
                                     Boolean objectEventsEnabled,
                                     Map<String, String> freeformTags,
                                     Map<String, Map<String, Object>> definedTags,
                                     String versioning, String ifMatch) {
        // Under the bucket's replication lock, so the read-only check and a policy creation that checks this
        // bucket's versioning cannot both pass.
        return replication.writing(ociContext.region(), bucketName, () -> {
            StoredBucket b = getBucket(namespaceName, bucketName);
            Etags.checkIfMatch(ifMatch, b.getEtag());
            if (versioning != null) {
                // Versioning can be suspended but never disabled once a bucket exists.
                String requested = versioningValue(versioning,
                        List.of(ObjectVersions.ENABLED, ObjectVersions.SUSPENDED));
                if (ObjectVersions.ENABLED.equals(requested) && b.getIsReadOnly()) {
                    throw OciException.conflict("Bucket '" + bucketName + "' is the destination of a replication "
                            + "policy. Versioning cannot be enabled on a replication destination.");
                }
                b.setVersioning(requested);
            }
            if (metadata != null) {
                b.setMetadata(metadata);
            }
            if (publicAccessType != null) {
                b.setPublicAccessType(publicAccessType);
            }
            if (objectEventsEnabled != null) {
                b.setObjectEventsEnabled(objectEventsEnabled);
            }
            if (freeformTags != null) {
                b.setFreeformTags(freeformTags);
            }
            if (definedTags != null) {
                b.setDefinedTags(definedTags);
            }
            b.setEtag(Etags.newEtag());
            buckets.put(bucketName, b);
            emitBucketEvent("updatebucket", b);
            return b;
        });
    }

    public void deleteBucket(String namespaceName, String bucketName, String ifMatch) {
        replication.writing(ociContext.region(), bucketName, () -> {
            StoredBucket b = getBucket(namespaceName, bucketName);
            Etags.checkIfMatch(ifMatch, b.getEtag());
            if (replication.isSource(bucketName)) {
                throw OciException.bucketReplicationEnabled(bucketName);
            }
            if (b.getIsReadOnly()) {
                throw OciException.conflict("Bucket '" + bucketName + "' is the destination of a replication policy. "
                        + "Make it writable before deleting it.");
            }
            boolean hasObjects = !objects.scan(k -> k.startsWith(bucketName + "/")).isEmpty();
            if (hasObjects || versions.hasPrevious(partition(), bucketName)) {
                throw OciException.bucketNotEmpty("Bucket named '" + bucketName + "' is not empty. "
                        + "Delete all object versions first.");
            }
            pars.scan(k -> true).stream()
                    .filter(p -> bucketName.equals(p.getBucket()))
                    .forEach(p -> {
                        pars.delete(p.getToken());
                        parOwners.remove(p.getToken());
                    });
            lifecycle.forget(bucketName);
            buckets.delete(bucketName);
            emitBucketEvent("deletebucket", b);
        });
    }

    // ── Objects ────────────────────────────────────────────────────────────────

    public StoredOsObject putObject(String namespaceName, String bucketName, String objectName,
                                    byte[] data, String contentType, String contentMd5,
                                    Map<String, String> metadata,
                                    String ifMatch, String ifNoneMatch) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            return putObject(namespaceName, bucketName, objectName, data, contentType, contentMd5,
                    metadata, ifMatch, ifNoneMatch, null);
        });
    }

    private StoredOsObject putObject(String namespaceName, String bucketName, String objectName,
                                     byte[] data, String contentType, String contentMd5,
                                     Map<String, String> metadata,
                                     String ifMatch, String ifNoneMatch, String multipartMd5) {
        StoredBucket bucket = getBucket(namespaceName, bucketName);
        replication.requireWritable(bucket);
        StoredOsObject existing = versions.latest(partition(), bucketName, objectName).orElse(null);
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
        o.setMultipartMd5(multipartMd5);
        o.setEtag(Etags.newEtag());
        o.setContentType(contentType != null ? contentType : "application/octet-stream");
        o.setStorageTier("Standard");
        String now = ObjectVersions.now();
        o.setTimeCreated(existing != null ? existing.getTimeCreated() : now);
        o.setTimeModified(now);
        o.setMetadata(metadata == null ? Map.of() : metadata);
        versions.writeLatest(partition(), bucket, o);
        replication.replicatePut(ociContext.region(), bucketName, o);
        emitObjectEvent(existing == null ? "createobject" : "updateobject", bucketName, o);
        return o;
    }

    public StoredOsObject getObject(String namespaceName, String bucketName, String objectName) {
        return getObject(namespaceName, bucketName, objectName, null);
    }

    /** GetObject and HeadObject: the latest version, or the one {@code versionId} names. */
    public StoredOsObject getObject(String namespaceName, String bucketName, String objectName, String versionId) {
        getBucket(namespaceName, bucketName);
        Optional<StoredOsObject> found = versionId == null
                ? versions.latest(partition(), bucketName, objectName)
                : versions.find(partition(), bucketName, objectName, versionId);
        return found.orElseThrow(() -> OciException.objectNotFound(objectNotFound(bucketName, objectName, versionId)));
    }

    /** The response headers of a DeleteObject: a version ID only when the delete created a delete marker. */
    public record ObjectDeletion(Instant lastModified, String markerVersionId) {
    }

    /**
     * DeleteObject. Without a version ID it deletes the latest version, which on an Enabled bucket leaves a
     * delete marker. With one it deletes that version for good, a delete marker included.
     */
    public ObjectDeletion deleteObject(String namespaceName, String bucketName, String objectName, String ifMatch,
                                       String versionId) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            StoredBucket bucket = getBucket(namespaceName, bucketName);
            replication.requireWritable(bucket);
            if (versionId != null) {
                if (ifMatch != null) {
                    Etags.checkIfMatch(ifMatch, getObject(namespaceName, bucketName, objectName, versionId).getEtag());
                }
                return deleteVersion(bucketName, objectName, versionId);
            }
            StoredOsObject latest = getObject(namespaceName, bucketName, objectName, null);
            Etags.checkIfMatch(ifMatch, latest.getEtag());
            ObjectVersions.Deletion deletion = versions.deleteLatest(partition(), bucket, latest);
            replicateLatestChange(bucketName, objectName, versions.latest(partition(), bucketName, objectName));
            emitObjectEvent("deleteobject", bucketName, latest);
            return new ObjectDeletion(Instant.now(), deletion.createdMarker() ? deletion.marker().getVersionId() : null);
        });
    }

    /** Deleting a version that was the live latest one is a deletion of the object, and emits its event. */
    private ObjectDeletion deleteVersion(String bucketName, String objectName, String versionId) {
        Optional<StoredOsObject> latest = versions.latest(partition(), bucketName, objectName)
                .filter(current -> versionId.equals(current.getVersionId()));
        Optional<StoredOsObject> promoted = versions.deleteVersion(partition(), bucketName, objectName, versionId,
                objectNotFound(bucketName, objectName, versionId));
        if (latest.isPresent() || promoted.isPresent()) {
            replicateLatestChange(bucketName, objectName, promoted);
        }
        latest.ifPresent(deleted -> emitObjectEvent("deleteobject", bucketName, deleted));
        return new ObjectDeletion(Instant.now(), null);
    }

    /** Mirrors a change of the live latest version into a replication destination: a promotion or a removal. */
    private void replicateLatestChange(String bucketName, String objectName, Optional<StoredOsObject> promoted) {
        promoted.ifPresentOrElse(
                latest -> replication.replicatePut(ociContext.region(), bucketName, latest),
                () -> replication.replicateDelete(ociContext.region(), bucketName, objectName));
    }

    private String objectNotFound(String bucketName, String objectName, String versionId) {
        return "The object '" + objectName + "'" + (versionId == null ? "" : " with version '" + versionId + "'")
                + " does not exist in bucket '" + bucketName + "' with namespace '" + namespace() + "'";
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
        return replication.writing(ociContext.region(), bucketName, () -> {
            replication.requireWritable(getBucket(namespaceName, bucketName));
            List<BatchDeleteResult.DeletedObject> deleted = new ArrayList<>();
            List<BatchDeleteResult.FailedDelete> failed = new ArrayList<>();
            for (BatchDeleteItem item : items) {
                try {
                    deleteObject(namespaceName, bucketName, item.objectName(), item.ifMatch(), null);
                    deleted.add(new BatchDeleteResult.DeletedObject(
                            item.objectName(), Instant.now()));
                } catch (OciException e) {
                    failed.add(new BatchDeleteResult.FailedDelete(
                            item.objectName(), e.getHttpStatus(), e.getMessage()));
                }
            }
            return new BatchDeleteResult(deleted, failed);
        });
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

    public record VersionListing(List<StoredOsObject> items, List<String> prefixes, String nextPage) {
    }

    /**
     * ListObjectVersions: every version and delete marker, by name and then newest first, with the same
     * {@code prefix}/{@code start}/{@code end}/{@code startAfter} filters and delimiter grouping as
     * ListObjects. Unlike ListObjects it pages with {@code opc-next-page}. The page token names the last
     * entry returned rather than an offset, so deleting the versions of one page, as the CLI's bulk delete
     * does before asking for the next, does not skip any.
     */
    public VersionListing listObjectVersions(String namespaceName, String bucketName, String prefix, String start,
                                             String end, String startAfter, String delimiter, Integer limit,
                                             String page) {
        getBucket(namespaceName, bucketName);
        if (limit != null && limit < 1) {
            throw OciException.invalidParameter("limit must be a positive integer");
        }
        int effectiveLimit = limit == null ? OciPage.MAX_LIMIT : Math.min(limit, OciPage.MAX_LIMIT);
        ObjectVersions.Cursor after = ObjectVersions.Cursor.decode(page);
        List<StoredOsObject> selected = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        ObjectVersions.Cursor lastReturned = null;
        String nextPage = null;
        for (StoredOsObject version : versions.all(partition(), bucketName)) {
            String name = version.getName();
            if ((prefix != null && !name.startsWith(prefix))
                    || (start != null && name.compareTo(start) < 0)
                    || (end != null && name.compareTo(end) >= 0)
                    || (startAfter != null && name.compareTo(startAfter) <= 0)) {
                continue;
            }
            String commonPrefix = commonPrefix(name, prefix, delimiter);
            if (commonPrefix != null && prefixes.contains(commonPrefix)) {
                continue;
            }
            ObjectVersions.Cursor position = commonPrefix != null ? ObjectVersions.Cursor.ofPrefix(commonPrefix)
                    : ObjectVersions.Cursor.of(version);
            if (after != null && !after.isBefore(position)) {
                continue;
            }
            if (selected.size() + prefixes.size() >= effectiveLimit) {
                nextPage = lastReturned.encode();
                break;
            }
            if (commonPrefix != null) {
                prefixes.add(commonPrefix);
            } else {
                selected.add(version);
            }
            lastReturned = position;
        }
        return new VersionListing(selected, List.copyOf(prefixes), nextPage);
    }

    private static String commonPrefix(String name, String prefix, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return null;
        }
        String scope = prefix != null ? prefix : "";
        int index = name.indexOf(delimiter, scope.length());
        return index < 0 ? null : name.substring(0, index + delimiter.length());
    }

    public StoredOsObject renameObject(String namespaceName, String bucketName,
                                       String sourceName, String newName,
                                       String srcIfMatch, String newIfMatch, String newIfNoneMatch) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            StoredBucket bucket = getBucket(namespaceName, bucketName);
            replication.requireWritable(bucket);
            StoredOsObject source = getObject(namespaceName, bucketName, sourceName);
            Etags.checkIfMatch(srcIfMatch, source.getEtag());
            StoredOsObject target = versions.latest(partition(), bucketName, newName).orElse(null);
            Etags.checkIfMatch(newIfMatch, target != null ? target.getEtag() : null);
            Etags.checkIfNoneMatch(newIfNoneMatch, target != null ? target.getEtag() : null);

            // Only the latest version is renamed. The rename deletes the source the way DeleteObject does, so
            // on an Enabled bucket the source keeps its versions and gains a delete marker.
            StoredOsObject renamed = copyOf(source, newName);
            renamed.setTimeCreated(source.getTimeCreated());
            versions.deleteLatest(partition(), bucket, source);
            versions.writeLatest(partition(), bucket, renamed);
            replication.replicatePut(ociContext.region(), bucketName, renamed);
            replicateLatestChange(bucketName, sourceName, versions.latest(partition(), bucketName, sourceName));
            emitObjectEvent("deleteobject", bucketName, source);
            emitObjectEvent(target == null ? "createobject" : "updateobject", bucketName, renamed);
            return renamed;
        });
    }

    private static StoredOsObject copyOf(StoredOsObject source, String name) {
        StoredOsObject copy = new StoredOsObject();
        copy.setName(name);
        copy.setData(source.getData());
        copy.setMd5(source.getMd5());
        copy.setMultipartMd5(source.getMultipartMd5());
        copy.setEtag(Etags.newEtag());
        copy.setContentType(source.getContentType());
        copy.setStorageTier(source.getStorageTier());
        String now = ObjectVersions.now();
        copy.setTimeCreated(now);
        copy.setTimeModified(now);
        copy.setMetadata(source.getMetadata());
        return copy;
    }

    /**
     * Copy is async on real OCI: returns the work-request OCID for the 202 response.
     * CopyObject runs against the source region's endpoint and copies "within a region or to
     * another region": the destination bucket is looked up, and the copy written, in
     * {@code destRegion}. The work request stays in the source region, where clients poll it.
     */
    /** A finished copy: its work request and the object it wrote, read while the copy held the write lock. */
    public record CopiedObject(String workRequestId, StoredOsObject object) {
    }

    public String copyObject(String namespaceName, String bucketName, String sourceObjectName,
                             String destRegion, String destNamespace, String destBucket,
                             String destObjectName, String sourceIfMatch, String sourceVersionId) {
        return copy(namespaceName, bucketName, sourceObjectName, destRegion, destNamespace, destBucket,
                destObjectName, sourceIfMatch, sourceVersionId).workRequestId();
    }

    public CopiedObject copy(String namespaceName, String bucketName, String sourceObjectName,
                             String destRegion, String destNamespace, String destBucket,
                             String destObjectName, String sourceIfMatch, String sourceVersionId) {
        return replication.writing(destRegion, destBucket, () -> {
            StoredOsObject source = getObject(namespaceName, bucketName, sourceObjectName, sourceVersionId);
            Etags.checkIfMatch(sourceIfMatch, source.getEtag());
            String destPartition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(), destRegion);
            StoredBucket destination = TenancyAwareStorageBackend.getIn(buckets, destPartition, destBucket)
                    .orElseThrow(() -> OciException.bucketNotFound("Either the bucket named '" + destBucket
                            + "' does not exist in the namespace '" + destNamespace + "' in region " + destRegion
                            + " or you are not authorized to access it"));
            replication.requireWritable(destination);

            // A copy, of the latest version or a previous one, becomes the latest version at the destination.
            StoredOsObject copy = copyOf(source, destObjectName);
            boolean replaced = TenancyAwareStorageBackend.getIn(objects, destPartition,
                    objectKey(destBucket, destObjectName)).isPresent();
            versions.writeLatest(destPartition, destination, copy);
            replication.replicatePut(destRegion, destBucket, copy);
            if (destRegion == null || destRegion.equals(ociContext.region())) {
                emitObjectEvent(replaced ? "updateobject" : "createobject", destBucket, copy);
            }

            StoredBucket bucket = getBucket(namespaceName, bucketName);
            // Object Storage work requests terminate in COMPLETED (Identity uses SUCCEEDED).
            String workRequestId = workRequests.completed("objectstorage", "COPY_OBJECT", bucket.getCompartmentId(),
                    List.of(WorkRequestService.resource("object", "CREATED", destObjectName,
                            "/n/" + namespace() + "/b/" + destBucket + "/o/" + destObjectName)),
                    "COMPLETED");
            return new CopiedObject(workRequestId, copy);
        });
    }

    // ── Multipart uploads ──────────────────────────────────────────────────────

    public StoredMultipartUpload createMultipartUpload(String namespaceName, String bucketName,
                                                       String objectName, String contentType,
                                                       Map<String, String> metadata) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            replication.requireWritable(getBucket(namespaceName, bucketName));
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
        });
    }

    public record UploadedPart(String etag, String md5) {
    }

    public UploadedPart uploadPart(String namespaceName, String bucketName, String objectName,
                                   String uploadId, int partNum, byte[] data) {
        if (partNum < 1 || partNum > 10000) {
            getUpload(bucketName, uploadId, objectName);
            throw OciException.invalidParameter("uploadPartNum must be between 1 and 10000");
        }
        return underUploadLock(bucketName, uploadId, () -> {
            StoredMultipartUpload u = getUpload(bucketName, uploadId, objectName);
            String etag = Etags.newEtag();
            u.getParts().put(partNum, new StoredMultipartUpload.Part(etag, data));
            uploads.put(uploadKey(bucketName, uploadId), u);
            return new UploadedPart(etag, md5Base64(data));
        });
    }

    /**
     * Runs {@code action} under the upload's lock, and drops the lock once the upload is gone, whether the
     * action removed it or it never existed. Upload ids are random and never reused, so a caller still
     * waiting on a dropped lock only finds the upload missing.
     */
    private <T> T underUploadLock(String bucketName, String uploadId, Supplier<T> action) {
        String key = uploadKey(bucketName, uploadId);
        Object lock = uploadLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                return action.get();
            } finally {
                if (uploads.get(key).isEmpty()) {
                    uploadLocks.remove(key, lock);
                }
            }
        }
    }

    int uploadLockCount() {
        return uploadLocks.size();
    }

    public StoredOsObject commitMultipartUpload(String namespaceName, String bucketName,
                                                String objectName, String uploadId,
                                                List<Map<String, Object>> partsToCommit) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            return underUploadLock(bucketName, uploadId,
                    () -> commitLocked(namespaceName, bucketName, objectName, uploadId, partsToCommit));
        });
    }

    private StoredOsObject commitLocked(String namespaceName, String bucketName, String objectName, String uploadId,
                                        List<Map<String, Object>> partsToCommit) {
        StoredMultipartUpload u = getUpload(bucketName, uploadId, objectName);
        if (partsToCommit == null || partsToCommit.isEmpty()) {
            throw OciException.invalidParameter("partsToCommit must contain at least one part");
        }
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        List<byte[]> committedParts = new ArrayList<>();
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
            committedParts.add(part.getData());
        }
        byte[] data = assembled.toByteArray();
        StoredOsObject o = putObject(namespaceName, bucketName, u.getObject(), data,
                u.getContentType(), null, u.getMetadata(), null, null, multipartMd5(committedParts));
        uploads.delete(uploadKey(bucketName, uploadId));
        return o;
    }

    public void abortMultipartUpload(String namespaceName, String bucketName,
                                     String objectName, String uploadId) {
        underUploadLock(bucketName, uploadId, () -> {
            getUpload(bucketName, uploadId, objectName);
            uploads.delete(uploadKey(bucketName, uploadId));
            return null;
        });
    }

    public List<StoredMultipartUpload> listMultipartUploads(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return uploads.scan(k -> k.startsWith(bucketName + "/")).stream()
                .sorted(Comparator.comparing(StoredMultipartUpload::getTimeCreated))
                .toList();
    }

    StoredMultipartUpload getUpload(String bucketName, String uploadId, String objectName) {
        StoredMultipartUpload u = uploads.get(uploadKey(bucketName, uploadId))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "No such upload: " + uploadId));
        if (objectName != null && !objectName.equals(u.getObject())) {
            throw OciException.notAuthorizedOrNotFound("No such upload for object: " + objectName);
        }
        return u;
    }

    // ── Object lifecycle policies ──────────────────────────────────────────────

    public StoredLifecyclePolicy putLifecyclePolicy(String namespaceName, String bucketName, Object items,
                                                    String ifMatch, String ifNoneMatch) {
        // Under the lock deleteBucket holds, so a policy is never stored for a bucket deleted meanwhile, where a
        // new bucket of the same name would inherit it.
        return replication.writing(ociContext.region(), bucketName, () -> {
            getBucket(namespaceName, bucketName);
            return lifecycle.put(bucketName, items, ifMatch, ifNoneMatch);
        });
    }

    public StoredLifecyclePolicy getLifecyclePolicy(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return lifecycle.get(bucketName);
    }

    public void deleteLifecyclePolicy(String namespaceName, String bucketName, String ifMatch) {
        getBucket(namespaceName, bucketName);
        lifecycle.delete(bucketName, ifMatch);
    }

    /** The lifecycle policy store, whose partitions are the tenancies and regions the sweeper visits. */
    StorageBackend<String, StoredLifecyclePolicy> lifecyclePolicyStore() {
        return lifecycle.store();
    }

    /**
     * Applies every enabled lifecycle rule of the current partition's buckets as of {@code now}. DELETE on
     * objects deletes the latest version as DeleteObject does, so a bucket with versioning Enabled keeps it as
     * a previous version under a delete marker ("When Lifecycle policies delete the latest version of an
     * object, that object becomes a previous version and a delete marker is created", usingversioning.htm).
     * DELETE on previous versions deletes them for good, ABORT drops uncommitted multipart uploads. ARCHIVE
     * and INFREQUENT_ACCESS are accepted but change nothing: storage tiers and archival are not emulated.
     *
     * @return how many objects, versions and uploads were removed
     */
    public int applyLifecycle(Instant now) {
        int removed = 0;
        for (StoredLifecyclePolicy policy : lifecycle.store().scan(k -> true)) {
            Optional<StoredBucket> bucket = buckets.get(policy.getBucketName());
            if (bucket.isEmpty() || bucket.get().getIsReadOnly()) {
                continue;
            }
            for (StoredLifecycleRule rule : policy.getItems()) {
                if (!rule.isEnabled()) {
                    continue;
                }
                try {
                    removed += applyRule(bucket.get(), rule, now);
                } catch (RuntimeException e) {
                    LOG.warnv("Lifecycle rule {0} on bucket {1} failed: {2}", rule.getName(),
                            policy.getBucketName(), e.getMessage());
                }
            }
        }
        return removed;
    }

    private int applyRule(StoredBucket bucket, StoredLifecycleRule rule, Instant now) {
        String bucketName = bucket.getName();
        String target = BucketLifecycle.targetOf(rule);
        if (BucketLifecycle.MULTIPART_UPLOADS.equals(target)) {
            int aborted = 0;
            for (StoredMultipartUpload upload : uploads.scan(k -> k.startsWith(bucketName + "/"))) {
                if (BucketLifecycle.matches(rule, upload.getObject())
                        && BucketLifecycle.isDue(rule, Instant.parse(upload.getTimeCreated()), now)) {
                    // A commit or abort may have removed it since the scan.
                    boolean removed = underUploadLock(bucketName, upload.getUploadId(), () -> {
                        String key = uploadKey(bucketName, upload.getUploadId());
                        if (uploads.get(key).isEmpty()) {
                            return false;
                        }
                        uploads.delete(key);
                        return true;
                    });
                    if (removed) {
                        aborted++;
                    }
                }
            }
            return aborted;
        }
        if (!BucketLifecycle.DELETE.equals(rule.getAction())) {
            return 0;
        }
        return BucketLifecycle.PREVIOUS_VERSIONS.equals(target)
                ? deletePreviousVersions(bucketName, rule, now)
                : deleteLatestObjects(bucketName, rule, now);
    }

    private int deleteLatestObjects(String bucketName, StoredLifecycleRule rule, Instant now) {
        int deleted = 0;
        for (StoredOsObject o : objects.scan(k -> k.startsWith(bucketName + "/"))) {
            if (!BucketLifecycle.matches(rule, o.getName())
                    || !BucketLifecycle.isDue(rule, Instant.parse(o.getTimeModified()), now)) {
                continue;
            }
            try {
                // The ETag read here, so an object rewritten since the scan is left alone.
                deleteObject(namespace(), bucketName, o.getName(), o.getEtag(), null);
                deleted++;
            } catch (OciException e) {
                LOG.debugv("Lifecycle left {0}/{1} alone: {2}", bucketName, o.getName(), e.getMessage());
            }
        }
        return deleted;
    }

    /**
     * A previous version's age counts from when it stopped being the latest ("Delete any previous object
     * versions 120 days after the object version transitions from the latest version to a previous version",
     * usinglifecyclepolicies.htm). A version stored before that time was recorded falls back to the next
     * newer version of its name, which is when it was replaced. Delete markers are left alone (inferred:
     * Oracle describes the action on object versions only).
     */
    private int deletePreviousVersions(String bucketName, StoredLifecycleRule rule, Instant now) {
        int deleted = 0;
        List<StoredOsObject> all = versions.all(partition(), bucketName);
        for (int i = 1; i < all.size(); i++) {
            StoredOsObject version = all.get(i);
            StoredOsObject newer = all.get(i - 1);
            if (!newer.getName().equals(version.getName()) || version.isDeleteMarker()
                    || !BucketLifecycle.matches(rule, version.getName())) {
                continue;
            }
            String since = version.getTimePrevious() != null ? version.getTimePrevious() : newer.getTimeModified();
            if (BucketLifecycle.isDue(rule, Instant.parse(since), now)
                    && deletePreviousVersion(bucketName, version)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Deletes {@code version} for good if it is still a previous version. The check and the delete share the
     * bucket's write lock, so a version promoted to the latest since the sweep listed it is kept.
     */
    boolean deletePreviousVersion(String bucketName, StoredOsObject version) {
        return replication.writing(ociContext.region(), bucketName, () -> {
            if (!versions.isPrevious(partition(), bucketName, version.getName(), version.getVersionId())) {
                LOG.debugv("Lifecycle left version {0} of {1}/{2} alone: it is the latest version now",
                        version.getVersionId(), bucketName, version.getName());
                return false;
            }
            try {
                deleteObject(namespace(), bucketName, version.getName(), null, version.getVersionId());
                return true;
            } catch (OciException e) {
                LOG.debugv("Lifecycle left version {0} of {1}/{2} alone: {3}", version.getVersionId(), bucketName,
                        version.getName(), e.getMessage());
                return false;
            }
        });
    }

    // ── Replication ────────────────────────────────────────────────────────────

    public StoredReplicationPolicy createReplicationPolicy(String namespaceName, String bucketName, String name,
                                                           String destinationRegionName,
                                                           String destinationBucketName) {
        getBucket(namespaceName, bucketName);
        return replication.create(namespace(), bucketName, () -> getBucket(namespaceName, bucketName), name,
                destinationRegionName, destinationBucketName);
    }

    public StoredReplicationPolicy getReplicationPolicy(String namespaceName, String bucketName,
                                                        String replicationId) {
        getBucket(namespaceName, bucketName);
        return replication.get(bucketName, replicationId);
    }

    public List<StoredReplicationPolicy> listReplicationPolicies(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return replication.list(bucketName);
    }

    public void deleteReplicationPolicy(String namespaceName, String bucketName, String replicationId) {
        getBucket(namespaceName, bucketName);
        replication.delete(bucketName, replicationId);
    }

    public List<StoredReplicationPolicy> listReplicationSources(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        return replication.sourcesOf(bucketName);
    }

    public void makeBucketWritable(String namespaceName, String bucketName) {
        getBucket(namespaceName, bucketName);
        replication.makeWritable(bucketName, () -> getBucket(namespaceName, bucketName));
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
        parOwners.remove(par.getToken());
    }

    @Override
    public void clear() {
        parOwners.clear();
    }

    int parOwnerCount() {
        return parOwners.size();
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


    private void emitBucketEvent(String type, StoredBucket b) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("namespace", b.getNamespace());
        details.put("publicAccessType", b.getPublicAccessType());
        details.put("eTag", b.getEtag());
        // Oracle's bucket example carries the compartment OCID as resourceId (eventsproducers.htm).
        emit(new OciEvents.Event("com.oraclecloud.objectstorage." + type, "objectstorage", b.getCompartmentId(),
                b.getName(), b.getCompartmentId(),
                b.getFreeformTags() != null ? b.getFreeformTags() : Map.of(),
                b.getDefinedTags() != null ? b.getDefinedTags() : Map.of(), details));
    }

    /** Object events fire only when the bucket has objectEventsEnabled; objects emit none by default. */
    private void emitObjectEvent(String type, String bucketName, StoredOsObject o) {
        Optional<StoredBucket> bucket = buckets.get(bucketName);
        if (bucket.isEmpty() || !Boolean.TRUE.equals(bucket.get().getObjectEventsEnabled())) {
            return;
        }
        StoredBucket b = bucket.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eTag", o.getEtag());
        details.put("namespace", b.getNamespace());
        details.put("archivalState", null);
        details.put("bucketName", b.getName());
        details.put("bucketId", b.getId());
        emit(new OciEvents.Event("com.oraclecloud.objectstorage." + type, "objectstorage", b.getCompartmentId(),
                o.getName(), "", null, null, details));
    }

    private void emit(OciEvents.Event event) {
        if (events == null || !events.isResolvable()) {
            return;
        }
        try {
            events.get().emit(event);
        } catch (RuntimeException e) {
            LOG.warnv("Could not emit {0} for {1}: {2}", event.eventType(), event.resourceName(), e.getMessage());
        }
    }

    private String partition() {
        return TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(), ociContext.region());
    }

    /** A versioning value from a request body, in the casing OCI returns, or 400 when {@code allowed} lacks it. */
    private static String versioningValue(String requested, List<String> allowed) {
        return allowed.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).equals(requested.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> OciException.invalidParameter("Invalid versioning value '" + requested
                        + "'. Supported values are: " + String.join(", ", allowed) + "."));
    }

    static String objectKey(String bucketName, String objectName) {
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

    static String multipartMd5(List<byte[]> partsInOrder) {
        try {
            MessageDigest objectDigest = MessageDigest.getInstance("MD5");
            for (byte[] part : partsInOrder) {
                objectDigest.update(MessageDigest.getInstance("MD5").digest(part));
            }
            return Base64.getEncoder().encodeToString(objectDigest.digest()) + "-" + partsInOrder.size();
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
