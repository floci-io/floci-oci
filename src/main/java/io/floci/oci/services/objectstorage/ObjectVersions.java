package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.objectstorage.model.StoredBucket;
import io.floci.oci.services.objectstorage.model.StoredOsObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Object versions of a bucket, following Oracle's object versioning documentation.
 *
 * <p>The latest version of a live object stays in the objects store under {@code <bucket>/<name>}, so
 * ListObjects, replication and the bucket scans read it as before. Previous versions and delete markers
 * live in a second store under {@code <bucket>/<versionId>}. An object whose latest version is a delete
 * marker therefore has no entry in the objects store, which is what hides it from GetObject and
 * ListObjects.
 *
 * <p>Every method takes the partition it works in, because CopyObject writes into another region.
 */
final class ObjectVersions {

    static final String ENABLED = "Enabled";
    static final String SUSPENDED = "Suspended";
    static final String DISABLED = "Disabled";

    /** Newest first; versions written in one instant are ordered by version ID so the order is stable. */
    static final Comparator<StoredOsObject> NEWEST_FIRST = Comparator
            .comparing((StoredOsObject o) -> Instant.parse(o.getTimeModified())).reversed()
            .thenComparing(StoredOsObject::getVersionId);

    /** ListObjectVersions order: by name, then newest first. */
    static final Comparator<StoredOsObject> LISTING_ORDER = Comparator
            .comparing(StoredOsObject::getName).thenComparing(NEWEST_FIRST);

    private static final AtomicReference<Instant> LAST_WRITE = new AtomicReference<>(Instant.EPOCH);

    private final StorageBackend<String, StoredOsObject> objects;
    private final StorageBackend<String, StoredOsObject> versions;

    /**
     * A position in ListObjectVersions order: one version, or a common prefix standing for every name
     * under it. The {@code opc-next-page} token is the last position a page returned.
     */
    record Cursor(String name, String timeModified, String versionId) {

        private static final String SEPARATOR = "\u0000";

        static Cursor of(StoredOsObject version) {
            return new Cursor(version.getName(), version.getTimeModified(), version.getVersionId());
        }

        static Cursor ofPrefix(String prefix) {
            return new Cursor(prefix, null, null);
        }

        boolean isPrefix() {
            return versionId == null;
        }

        /** Whether {@code position} comes after this cursor, so a page resuming here returns it. */
        boolean isBefore(Cursor position) {
            if (isPrefix()) {
                return position.name.compareTo(name) > 0 && !position.name.startsWith(name);
            }
            if (position.isPrefix()) {
                return name.compareTo(position.name) < 0 && !name.startsWith(position.name);
            }
            int byName = name.compareTo(position.name);
            if (byName != 0) {
                return byName < 0;
            }
            StoredOsObject self = probe(this);
            StoredOsObject other = probe(position);
            return NEWEST_FIRST.compare(self, other) < 0;
        }

        String encode() {
            String raw = isPrefix() ? "p" + SEPARATOR + name
                    : "v" + SEPARATOR + name + SEPARATOR + timeModified + SEPARATOR + versionId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String page) {
            if (page == null || page.isBlank()) {
                return null;
            }
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(page), StandardCharsets.UTF_8)
                        .split(SEPARATOR, -1);
                if (parts.length == 2 && "p".equals(parts[0])) {
                    return ofPrefix(parts[1]);
                }
                if (parts.length == 4 && "v".equals(parts[0])) {
                    Instant.parse(parts[2]);
                    return new Cursor(parts[1], parts[2], parts[3]);
                }
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw OciException.invalidParameter("Invalid page token: " + page);
            }
            throw OciException.invalidParameter("Invalid page token: " + page);
        }

        private static StoredOsObject probe(Cursor cursor) {
            StoredOsObject probe = new StoredOsObject();
            probe.setName(cursor.name);
            probe.setTimeModified(cursor.timeModified);
            probe.setVersionId(cursor.versionId);
            return probe;
        }
    }

    ObjectVersions(StorageBackend<String, StoredOsObject> objects, StorageBackend<String, StoredOsObject> versions) {
        this.objects = objects;
        this.versions = versions;
    }

    /**
     * A write time later than every earlier one, so the latest version is always the newest even when
     * two writes land in the same clock tick.
     */
    static String now() {
        return LAST_WRITE.updateAndGet(previous -> {
            Instant current = Instant.now();
            return current.isAfter(previous) ? current : previous.plusNanos(1_000);
        }).toString();
    }

    static String newVersionId() {
        return UUID.randomUUID().toString();
    }

    static boolean isEnabled(StoredBucket bucket) {
        return ENABLED.equals(bucket.getVersioning());
    }

    /** Enabled or Suspended: the bucket has had versioning, so it may hold previous versions. */
    static boolean isVersioned(StoredBucket bucket) {
        return bucket.getVersioning() != null && !DISABLED.equals(bucket.getVersioning());
    }

    /** What a delete did: the marker it created, if any. */
    record Deletion(StoredOsObject marker) {

        boolean createdMarker() {
            return marker != null;
        }
    }

    /**
     * The live latest version of {@code name}. An object stored before versions existed has no version ID;
     * it gets one derived from its name and ETag, the same on every read, without a write, so a read never
     * races a concurrent write to the same key. The ID is stored once the object is next moved or replaced.
     */
    Optional<StoredOsObject> latest(String partition, String bucketName, String name) {
        Optional<StoredOsObject> latest = TenancyAwareStorageBackend.getIn(objects, partition,
                ObjectStorageService.objectKey(bucketName, name));
        latest.filter(o -> o.getVersionId() == null).ifPresent(o -> o.setVersionId(legacyVersionId(o)));
        return latest;
    }

    static String legacyVersionId(StoredOsObject o) {
        return UUID.nameUUIDFromBytes((o.getName() + "\u0000" + o.getEtag()).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** One version of {@code name}, the latest included; a delete marker is not an object and is not found. */
    Optional<StoredOsObject> find(String partition, String bucketName, String name, String versionId) {
        Optional<StoredOsObject> latest = latest(partition, bucketName, name)
                .filter(o -> versionId.equals(o.getVersionId()));
        if (latest.isPresent()) {
            return latest;
        }
        return previous(partition, bucketName, name, versionId).filter(o -> !o.isDeleteMarker());
    }

    /**
     * Stores {@code written} as the latest version of its name. On an Enabled bucket the version it
     * replaces becomes a previous version; on a Disabled or Suspended bucket it is overwritten.
     */
    void writeLatest(String partition, StoredBucket bucket, StoredOsObject written) {
        String key = ObjectStorageService.objectKey(bucket.getName(), written.getName());
        if (isEnabled(bucket)) {
            latest(partition, bucket.getName(), written.getName())
                    .ifPresent(replaced -> keepAsPrevious(partition, bucket.getName(), replaced));
        }
        written.setVersionId(newVersionId());
        TenancyAwareStorageBackend.putIn(objects, partition, key, written);
    }

    /**
     * DeleteObject without a version ID. On an Enabled bucket the latest version becomes a previous
     * version and a delete marker records the deletion. Otherwise the latest version is deleted for
     * good, as on a bucket that never had versioning.
     */
    Deletion deleteLatest(String partition, StoredBucket bucket, StoredOsObject latest) {
        String key = ObjectStorageService.objectKey(bucket.getName(), latest.getName());
        if (!isEnabled(bucket)) {
            TenancyAwareStorageBackend.deleteAllIn(objects, partition, List.of(key));
            promote(partition, bucket.getName(), latest.getName());
            return new Deletion(null);
        }
        // Saved as a previous version before the live copy goes, so a stop in between loses nothing.
        keepAsPrevious(partition, bucket.getName(), latest);
        TenancyAwareStorageBackend.deleteAllIn(objects, partition, List.of(key));
        StoredOsObject marker = new StoredOsObject();
        marker.setName(latest.getName());
        marker.setVersionId(newVersionId());
        marker.setDeleteMarker(true);
        String now = now();
        marker.setTimeCreated(now);
        marker.setTimeModified(now);
        keepAsPrevious(partition, bucket.getName(), marker);
        return new Deletion(marker);
    }

    /**
     * DeleteObject with a version ID: that version, object or delete marker, is deleted for good. When it
     * leaves the newest remaining version on top, and that version is an object, it becomes the latest.
     *
     * @return the version that became the live latest, if the delete changed it
     */
    Optional<StoredOsObject> deleteVersion(String partition, String bucketName, String name, String versionId,
                                           String notFoundMessage) {
        Optional<StoredOsObject> latest = latest(partition, bucketName, name);
        if (latest.isPresent() && versionId.equals(latest.get().getVersionId())) {
            TenancyAwareStorageBackend.deleteAllIn(objects, partition,
                    List.of(ObjectStorageService.objectKey(bucketName, name)));
            return promote(partition, bucketName, name);
        }
        StoredOsObject version = previous(partition, bucketName, name, versionId)
                .orElseThrow(() -> OciException.objectNotFound(notFoundMessage));
        TenancyAwareStorageBackend.deleteAllIn(versions, partition, List.of(versionKey(bucketName, versionId)));
        return latest.isPresent() || !version.isDeleteMarker() ? Optional.empty()
                : promote(partition, bucketName, name);
    }

    /** Every version in the bucket, latest ones included, in ListObjectVersions order. */
    List<StoredOsObject> all(String partition, String bucketName) {
        List<StoredOsObject> all = new ArrayList<>();
        Set<String> live = new HashSet<>();
        for (StoredOsObject latest : TenancyAwareStorageBackend.scanIn(objects, partition,
                k -> k.startsWith(bucketName + "/"))) {
            StoredOsObject version = latest(partition, bucketName, latest.getName()).orElse(latest);
            live.add(version.getVersionId());
            all.add(version);
        }
        // A version can be in both stores only after a stop in the middle of a move; it is listed once.
        TenancyAwareStorageBackend.scanIn(versions, partition, k -> k.startsWith(bucketName + "/")).stream()
                .filter(previous -> !live.contains(previous.getVersionId()))
                .forEach(all::add);
        all.sort(LISTING_ORDER);
        return all;
    }

    boolean hasPrevious(String partition, String bucketName) {
        return !TenancyAwareStorageBackend.scanIn(versions, partition, k -> k.startsWith(bucketName + "/"))
                .isEmpty();
    }

    /** Moves the newest previous version of {@code name} to the top when it is an object and nothing live is. */
    private Optional<StoredOsObject> promote(String partition, String bucketName, String name) {
        String key = ObjectStorageService.objectKey(bucketName, name);
        if (TenancyAwareStorageBackend.getIn(objects, partition, key).isPresent()) {
            return Optional.empty();
        }
        Optional<StoredOsObject> newest = TenancyAwareStorageBackend.scanIn(versions, partition,
                        k -> k.startsWith(bucketName + "/")).stream()
                .filter(o -> name.equals(o.getName()))
                .min(NEWEST_FIRST)
                .filter(o -> !o.isDeleteMarker());
        newest.ifPresent(o -> {
            // Written live before the previous copy goes, so a stop in between loses nothing.
            TenancyAwareStorageBackend.putIn(objects, partition, key, o);
            TenancyAwareStorageBackend.deleteAllIn(versions, partition,
                    List.of(versionKey(bucketName, o.getVersionId())));
        });
        return newest;
    }

    private Optional<StoredOsObject> previous(String partition, String bucketName, String name, String versionId) {
        return TenancyAwareStorageBackend.getIn(versions, partition, versionKey(bucketName, versionId))
                .filter(o -> name.equals(o.getName()));
    }

    private void keepAsPrevious(String partition, String bucketName, StoredOsObject version) {
        TenancyAwareStorageBackend.putIn(versions, partition, versionKey(bucketName, version.getVersionId()), version);
    }

    private static String versionKey(String bucketName, String versionId) {
        return bucketName + "/" + versionId;
    }
}
