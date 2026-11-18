package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import io.floci.oci.services.objectstorage.model.StoredReplicationPolicy;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Object Storage replication policies, and the mirroring of a source bucket's writes into its
 * destination bucket.
 *
 * <p>Real OCI replicates asynchronously with an unstated lag. floci-oci replicates inside the request
 * that wrote the source, which is the zero-lag case of the same contract and keeps tests deterministic.
 * Only writes made after the policy exists are replicated, deletes included, and a replica keeps the
 * source's name, data, metadata, ETag and MD5, as Oracle's replication documentation states.
 *
 * <p>Each write to a bucket holds that bucket's lock across the write and its replica, so a source
 * object and its replica change in the same order. Policy changes hold one policy lock, then the locks
 * of every bucket they touch, taken in key order: concurrent creates cannot both pass the conflict
 * checks, and no replication is in flight while a policy is deleted or stopped. A write takes a single
 * bucket lock and never the policy lock, so the two cannot deadlock.
 */
final class BucketReplication {

    private static final Logger LOG = Logger.getLogger(BucketReplication.class);
    static final String ACTIVE_MESSAGE = "The policy is active.";

    private final StorageBackend<String, StoredReplicationPolicy> policies;
    private final StorageBackend<String, StoredBucket> buckets;
    private final StorageBackend<String, StoredOsObject> objects;
    private final OciContext ociContext;
    private final ReentrantLock policyLock = new ReentrantLock();
    private final Map<String, ReentrantLock> bucketLocks = new ConcurrentHashMap<>();

    BucketReplication(StorageBackend<String, StoredReplicationPolicy> policies,
                      StorageBackend<String, StoredBucket> buckets,
                      StorageBackend<String, StoredOsObject> objects,
                      OciContext ociContext) {
        this.policies = policies;
        this.buckets = buckets;
        this.objects = objects;
        this.ociContext = ociContext;
    }

    // ── Policies ───────────────────────────────────────────────────────────────

    /** {@code source} reads the source bucket again once its lock is held. */
    StoredReplicationPolicy create(String namespace, String sourceBucketName, Supplier<StoredBucket> source,
                                   String name, String destinationRegion, String destinationBucket) {
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("name is required");
        }
        if (destinationRegion == null || destinationRegion.isBlank()) {
            throw OciException.missingParameter("destinationRegionName is required");
        }
        if (destinationBucket == null || destinationBucket.isBlank()) {
            throw OciException.missingParameter("destinationBucketName is required");
        }
        if (Regions.byName(destinationRegion).isEmpty()) {
            throw OciException.invalidParameter("Unknown destinationRegionName: " + destinationRegion);
        }
        String sourceRegion = ociContext.region();
        return changingPolicies(List.of(lockKey(sourceRegion, sourceBucketName),
                        lockKey(destinationRegion, destinationBucket)),
                () -> createLocked(namespace, source.get(), name, sourceRegion, destinationRegion, destinationBucket));
    }

    private StoredReplicationPolicy createLocked(String namespace, StoredBucket source, String name,
                                                 String sourceRegion, String destinationRegion,
                                                 String destinationBucket) {
        if (policyOf(sourceRegion, source.getName()).isPresent()) {
            throw OciException.replicationPolicyConflict("Replication policy exists for bucket '"
                    + source.getName() + "' in namespace '" + namespace + "'.");
        }
        if (source.getIsReadOnly()) {
            throw OciException.replicationPolicyConflict("Bucket '" + source.getName()
                    + "' is the destination of a replication policy, so it cannot also be a replication source.");
        }
        if (sourceRegion.equals(destinationRegion) && source.getName().equals(destinationBucket)) {
            throw OciException.invalidParameter("A bucket cannot replicate to itself.");
        }
        String destinationPartition = partition(destinationRegion);
        StoredBucket destination = TenancyAwareStorageBackend.getIn(buckets, destinationPartition, destinationBucket)
                .orElseThrow(() -> OciException.bucketNotFound("Either the bucket named '" + destinationBucket
                        + "' does not exist in the namespace '" + namespace + "' in region " + destinationRegion
                        + " or you are not authorized to access it"));
        if (policyOf(destinationRegion, destinationBucket).isPresent()) {
            throw OciException.replicationPolicyConflict("Bucket '" + destinationBucket + "' in " + destinationRegion
                    + " is a replication source, so it cannot also be a replication destination.");
        }
        if (destination.getIsReadOnly()) {
            throw OciException.replicationPolicyConflict("Bucket '" + destinationBucket + "' in " + destinationRegion
                    + " is already the destination of a replication policy.");
        }

        StoredReplicationPolicy policy = new StoredReplicationPolicy();
        policy.setId(UUID.randomUUID().toString());
        policy.setName(name);
        policy.setSourceRegionName(sourceRegion);
        policy.setSourceBucketName(source.getName());
        policy.setDestinationRegionName(destinationRegion);
        policy.setDestinationBucketName(destinationBucket);
        policy.setTimeCreated(now());
        policy.setStatus(StoredReplicationPolicy.ACTIVE);
        policy.setStatusMessage(ACTIVE_MESSAGE);
        save(policy);

        source.setReplicationEnabled(true);
        buckets.put(source.getName(), source);
        destination.setIsReadOnly(true);
        TenancyAwareStorageBackend.putIn(buckets, destinationPartition, destinationBucket, destination);
        LOG.infov("createReplicationPolicy {0}: {1} to {2} in {3}", name, source.getName(), destinationBucket,
                destinationRegion);
        return policy;
    }

    StoredReplicationPolicy get(String bucketName, String replicationId) {
        return policyOf(ociContext.region(), bucketName)
                .filter(policy -> policy.getId().equals(replicationId))
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("The replication policy '" + replicationId
                        + "' does not exist on bucket '" + bucketName + "' or you are not authorized to access it"));
    }

    List<StoredReplicationPolicy> list(String bucketName) {
        return policyOf(ociContext.region(), bucketName).stream().toList();
    }

    void delete(String bucketName, String replicationId) {
        policyLock.lock();
        try {
            StoredReplicationPolicy policy = get(bucketName, replicationId);
            withBuckets(List.of(lockKey(policy.getSourceRegionName(), policy.getSourceBucketName()),
                    lockKey(policy.getDestinationRegionName(), policy.getDestinationBucketName())), () -> {
                deleteLocked(policy);
                return null;
            });
        } finally {
            policyLock.unlock();
        }
    }

    private void deleteLocked(StoredReplicationPolicy policy) {
        TenancyAwareStorageBackend.deleteAllIn(policies, partition(policy.getSourceRegionName()),
                List.of(key(policy)));
        updateBucket(policy.getSourceRegionName(), policy.getSourceBucketName(),
                bucket -> bucket.setReplicationEnabled(false));
        if (policy.isActive()) {
            updateBucket(policy.getDestinationRegionName(), policy.getDestinationBucketName(),
                    bucket -> bucket.setIsReadOnly(false));
        }
    }

    /** The active policies writing into {@code bucketName} in the request region: at most one. */
    List<StoredReplicationPolicy> sourcesOf(String bucketName) {
        String region = ociContext.region();
        return TenancyAwareStorageBackend.scanTenancy(policies, ociContext.tenancyId()).stream()
                .filter(StoredReplicationPolicy::isActive)
                .filter(policy -> region.equals(policy.getDestinationRegionName())
                        && bucketName.equals(policy.getDestinationBucketName()))
                .sorted(Comparator.comparing(StoredReplicationPolicy::getTimeCreated))
                .toList();
    }

    /**
     * Stops replication into {@code destination}: the bucket becomes writable and its source's policy
     * moves to {@code CLIENT_ERROR}, where it stays until it is deleted.
     */
    void makeWritable(String bucketName, Supplier<StoredBucket> destination) {
        policyLock.lock();
        try {
            List<String> keys = new ArrayList<>();
            keys.add(lockKey(ociContext.region(), bucketName));
            for (StoredReplicationPolicy policy : sourcesOf(bucketName)) {
                keys.add(lockKey(policy.getSourceRegionName(), policy.getSourceBucketName()));
            }
            withBuckets(keys, () -> {
                makeWritableLocked(destination.get());
                return null;
            });
        } finally {
            policyLock.unlock();
        }
    }

    private void makeWritableLocked(StoredBucket destination) {
        if (!destination.getIsReadOnly()) {
            throw OciException.conflict("Bucket '" + destination.getName()
                    + "' is not the destination of a replication policy.");
        }
        for (StoredReplicationPolicy policy : sourcesOf(destination.getName())) {
            policy.setStatus(StoredReplicationPolicy.CLIENT_ERROR);
            policy.setStatusMessage("Replication stopped: the destination bucket '" + destination.getName()
                    + "' in " + policy.getDestinationRegionName()
                    + " was made writable. Delete this policy to stop replicating.");
            save(policy);
        }
        destination.setIsReadOnly(false);
        buckets.put(destination.getName(), destination);
    }

    boolean isSource(String bucketName) {
        return policyOf(ociContext.region(), bucketName).isPresent();
    }

    void requireWritable(StoredBucket bucket) {
        if (bucket.getIsReadOnly()) {
            throw OciException.conflict("Bucket '" + bucket.getName()
                    + "' is the destination of a replication policy and is read-only. "
                    + "Make it writable to change its contents.");
        }
    }

    // ── Mirroring ──────────────────────────────────────────────────────────────

    /** Writes {@code written}, just stored in {@code bucketName} of {@code region}, into its destination. */
    void replicatePut(String region, String bucketName, StoredOsObject written) {
        activePolicyOf(region, bucketName).ifPresent(policy -> {
            String destinationPartition = partition(policy.getDestinationRegionName());
            String key = ObjectStorageService.objectKey(policy.getDestinationBucketName(), written.getName());
            Optional<StoredOsObject> existing = TenancyAwareStorageBackend.getIn(objects, destinationPartition, key);
            String now = Instant.now().toString();

            StoredOsObject replica = new StoredOsObject();
            replica.setName(written.getName());
            replica.setData(written.getData());
            replica.setMd5(written.getMd5());
            replica.setMultipartMd5(written.getMultipartMd5());
            replica.setEtag(written.getEtag());
            replica.setContentType(written.getContentType());
            replica.setStorageTier(written.getStorageTier());
            replica.setMetadata(written.getMetadata());
            replica.setTimeCreated(existing.map(StoredOsObject::getTimeCreated).orElse(now));
            replica.setTimeModified(now);
            TenancyAwareStorageBackend.putIn(objects, destinationPartition, key, replica);
            synced(policy);
        });
    }

    /** Deletes {@code objectName}, just deleted from {@code bucketName} of {@code region}, from its destination. */
    void replicateDelete(String region, String bucketName, String objectName) {
        activePolicyOf(region, bucketName).ifPresent(policy -> {
            TenancyAwareStorageBackend.deleteAllIn(objects, partition(policy.getDestinationRegionName()),
                    List.of(ObjectStorageService.objectKey(policy.getDestinationBucketName(), objectName)));
            synced(policy);
        });
    }

    // ── Locking ────────────────────────────────────────────────────────────────

    /** Runs a client write to {@code bucketName} in {@code region}, with its replication, under the bucket's lock. */
    <T> T writing(String region, String bucketName, Supplier<T> write) {
        return withBuckets(List.of(lockKey(region, bucketName)), write);
    }

    void writing(String region, String bucketName, Runnable write) {
        writing(region, bucketName, () -> {
            write.run();
            return null;
        });
    }

    private <T> T changingPolicies(List<String> bucketKeys, Supplier<T> change) {
        policyLock.lock();
        try {
            return withBuckets(bucketKeys, change);
        } finally {
            policyLock.unlock();
        }
    }

    private <T> T withBuckets(List<String> keys, Supplier<T> action) {
        List<ReentrantLock> held = new ArrayList<>();
        try {
            for (String key : keys.stream().distinct().sorted().toList()) {
                ReentrantLock lock = bucketLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
                lock.lock();
                held.add(lock);
            }
            return action.get();
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    private String lockKey(String region, String bucketName) {
        return partition(region) + "/" + bucketName;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Optional<StoredReplicationPolicy> policyOf(String region, String bucketName) {
        return TenancyAwareStorageBackend.scanTenancy(policies, ociContext.tenancyId()).stream()
                .filter(policy -> region.equals(policy.getSourceRegionName())
                        && bucketName.equals(policy.getSourceBucketName()))
                .findFirst();
    }

    private Optional<StoredReplicationPolicy> activePolicyOf(String region, String bucketName) {
        return policyOf(region, bucketName).filter(StoredReplicationPolicy::isActive);
    }

    /** Moves {@code timeLastSync} on the stored policy, never bringing back one deleted meanwhile. */
    private void synced(StoredReplicationPolicy policy) {
        TenancyAwareStorageBackend.getIn(policies, partition(policy.getSourceRegionName()), key(policy))
                .filter(StoredReplicationPolicy::isActive)
                .ifPresent(stored -> {
                    stored.setTimeLastSync(Instant.now().toString());
                    save(stored);
                });
    }

    private void save(StoredReplicationPolicy policy) {
        TenancyAwareStorageBackend.putIn(policies, partition(policy.getSourceRegionName()), key(policy), policy);
    }

    private void updateBucket(String region, String bucketName, Consumer<StoredBucket> change) {
        String bucketPartition = partition(region);
        TenancyAwareStorageBackend.getIn(buckets, bucketPartition, bucketName).ifPresent(bucket -> {
            change.accept(bucket);
            TenancyAwareStorageBackend.putIn(buckets, bucketPartition, bucketName, bucket);
        });
    }

    private String partition(String region) {
        return TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(), region);
    }

    private static String key(StoredReplicationPolicy policy) {
        return policy.getSourceBucketName() + "/" + policy.getId();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
