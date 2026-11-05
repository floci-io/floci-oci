package io.floci.oci.services.psql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.psql.model.StoredBackup;
import io.floci.oci.services.psql.model.StoredDbSystem;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backups of OCI Database with PostgreSQL DB systems. In real mode a backup is a
 * {@code pg_dumpall} of the running system, taken in the background into the emulator's dump
 * store, which no DB system container can see; creating a DB system from it, or restoring one, loads it
 * back. Create, delete, copy and change-compartment are work-request based, as in the SDK.
 */
@ApplicationScoped
public class PsqlBackupsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(PsqlBackupsService.class);

    /** Oracle's service limit: backups per tenancy per region. */
    static final int LIMIT = 10;
    static final String ENTITY_TYPE = "postgresqlBackup";
    static final Set<String> LIFECYCLE_STATES = Set.of("CREATING", "ACTIVE", "DELETING", "DELETED", "FAILED");
    static final Set<String> SOURCE_TYPES = Set.of("SCHEDULED", "MANUAL", "PITR", "COPIED");
    static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");
    static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final long GIGABYTE = 1024L * 1024 * 1024;

    private final StorageBackend<String, StoredBackup> backups;
    private final WorkRequestService workRequests;
    private final PsqlContainerManager containers;
    private final OciContext ociContext;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Executor worker;
    private final ExecutorService ownedWorker;

    @Inject
    public PsqlBackupsService(StorageFactory storageFactory, WorkRequestService workRequests,
                              PsqlContainerManager containers, OciContext ociContext, EmulatorConfig config) {
        this(storageFactory.create("psql", "psql-backups.json", new TypeReference<Map<String, StoredBackup>>() {}),
                workRequests, containers, ociContext, config, Clock.systemUTC(), null);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param containers null in mock mode
     * @param worker runs the dumps; null for the service's own single worker thread
     */
    PsqlBackupsService(StorageBackend<String, StoredBackup> backups, WorkRequestService workRequests,
                       PsqlContainerManager containers, OciContext ociContext, EmulatorConfig config, Clock clock,
                       Executor worker) {
        this.backups = backups;
        this.workRequests = workRequests;
        this.containers = containers;
        this.ociContext = ociContext;
        this.config = config;
        this.clock = clock;
        // No thread starts until the first dump is queued, which only real mode does.
        this.ownedWorker = worker != null ? null : Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "psql-backups");
            thread.setDaemon(true);
            return thread;
        });
        this.worker = worker != null ? worker : ownedWorker;
    }

    @PreDestroy
    void shutdown() {
        if (ownedWorker != null) {
            ownedWorker.shutdownNow();
        }
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(backups, tenancyId, compartmentId, StoredBackup::getCompartmentId,
                StoredBackup::getLifecycleState, backup -> "backup " + backup.getId()).toList();
    }

    boolean realMode() {
        return containers != null && !config.services().psql().mock();
    }

    // ── Backups ────────────────────────────────────────────────────────────────

    /** Backs up {@code system}, which the caller has checked is ACTIVE. Returns the work request id. */
    public String createBackup(StoredDbSystem system, Map<String, Object> details) {
        String displayName = PsqlRequests.requireString(details, "displayName");
        String compartmentId = PsqlRequests.requireString(details, "compartmentId");
        Integer retentionPeriod = retention(details);
        StoredBackup backup;
        synchronized (this) {
            requireUnderLimit();
            String now = clock.instant().toString();
            backup = new StoredBackup();
            backup.setId(Ocids.generate("postgresqlbackup", ociContext.realm(), ociContext.regionCode()));
            backup.setDisplayName(displayName);
            backup.setDescription(PsqlRequests.string(details, "description"));
            backup.setCompartmentId(compartmentId);
            backup.setTimeCreated(now);
            backup.setSourceType("MANUAL");
            backup.setBackupSize(0);
            backup.setRetentionPeriod(retentionPeriod);
            backup.setDbSystemId(system.getId());
            backup.setDbSystemDetails(dbSystemDetails(system));
            backup.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
            backup.setDefinedTags(PsqlRequests.definedTags(details));
            backup.setTenancyId(ociContext.tenancyId());
            backup.setRegion(ociContext.region());
            backup.setDumpId(backup.getId());
            backup.setTimeDumped(now);
            backup.setEtag(Etags.newEtag());
            if (!realMode()) {
                backup.setLifecycleState("ACTIVE");
                backups.put(backup.getId(), backup);
                return workRequests.succeeded(PsqlService.WR_SERVICE, "CREATE_POSTGRESQL_DB_SYSTEM_BACKUP",
                        compartmentId, resources(backup, "CREATED"));
            }
            backup.setLifecycleState("CREATING");
            backup.setWorkRequestId(workRequests.inProgress(PsqlService.WR_SERVICE,
                    "CREATE_POSTGRESQL_DB_SYSTEM_BACKUP", compartmentId, resources(backup, "CREATED")));
            backups.put(backup.getId(), backup);
        }
        String workRequestId = backup.getWorkRequestId();
        worker.execute(() -> takeDump(backup.getTenancyId(), backup.getRegion(), backup.getId(), system));
        LOG.infov("createBackup {0} of DB system {1}", backup.getId(), system.getId());
        return workRequestId;
    }

    /** Runs on the worker: no request scope, so the backup is found in its own partition. */
    private void takeDump(String tenancyId, String region, String backupId, StoredDbSystem system) {
        String failure = null;
        long bytes = 0;
        try {
            bytes = containers.dump(system, backupId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Backup {0} of DB system {1} failed", backupId, system.getId());
            failure = e.getMessage();
        }
        synchronized (this) {
            Optional<StoredBackup> found = TenancyAwareStorageBackend.getIn(backups,
                    TenancyAwareStorageBackend.regionalPartition(tenancyId, region), backupId);
            if (found.isEmpty() || !"CREATING".equals(found.get().getLifecycleState())) {
                LOG.debugv("Backup {0} moved on while its dump ran", backupId);
                return;
            }
            StoredBackup backup = found.get();
            backup.setLifecycleState(failure == null ? "ACTIVE" : "FAILED");
            backup.setLifecycleDetails(failure);
            backup.setBackupSize(failure == null ? (int) Math.max(1, (bytes + GIGABYTE - 1) / GIGABYTE) : 0);
            touch(backup);
            TenancyAwareStorageBackend.putIn(backups, TenancyAwareStorageBackend.regionalPartition(tenancyId, region),
                    backupId, backup);
            List<StoredWorkRequest.ErrorEntry> errors = failure == null ? List.of()
                    : List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", failure));
            workRequests.finish(tenancyId, region, backup.getWorkRequestId(), failure == null ? "SUCCEEDED" : "FAILED",
                    errors);
        }
    }

    /**
     * After a restart of the emulator, fails the backups whose dump the restart interrupted: a
     * dump cannot be resumed, and a backup left CREATING could never be deleted.
     */
    public synchronized void settleInterruptedBackups() {
        for (StoredBackup backup : TenancyAwareStorageBackend.scanAll(backups)) {
            if (!"CREATING".equals(backup.getLifecycleState())) {
                continue;
            }
            String message = "The emulator restarted while the backup was being taken";
            backup.setLifecycleState("FAILED");
            backup.setLifecycleDetails(message);
            touch(backup);
            TenancyAwareStorageBackend.putIn(backups,
                    TenancyAwareStorageBackend.regionalPartition(backup.getTenancyId(), backup.getRegion()),
                    backup.getId(), backup);
            workRequests.finish(backup.getTenancyId(), backup.getRegion(), backup.getWorkRequestId(), "FAILED",
                    List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", message)));
            LOG.warnv("Backup {0} failed: {1}", backup.getId(), message);
        }
    }

    public StoredBackup getBackup(String backupId) {
        return backups.get(backupId).orElseThrow(() -> notFound("backup", backupId));
    }

    public List<StoredBackup> listBackups(String compartmentId, String timeStarted, String timeEnded,
                                          String lifecycleState, String displayName, String backupId,
                                          String dbSystemId, String backupSourceType, String sortBy,
                                          String sortOrder) {
        PsqlRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        PsqlRequests.requireEnum(backupSourceType, SOURCE_TYPES, "backupSourceType");
        PsqlRequests.requireEnum(sortBy, SORT_BY, "sortBy");
        PsqlRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        Instant from = timeStarted != null ? instant(timeStarted, "timeStarted") : null;
        Instant to = timeEnded != null ? instant(timeEnded, "timeEnded") : null;
        boolean byName = "displayName".equals(sortBy);
        Comparator<StoredBackup> order = byName
                ? Comparator.comparing(StoredBackup::getDisplayName)
                : Comparator.comparing(StoredBackup::getTimeCreated);
        order = order.thenComparing(StoredBackup::getId);
        String direction = sortOrder != null ? sortOrder : (byName ? "ASC" : "DESC");
        if ("DESC".equals(direction)) {
            order = order.reversed();
        }
        return backups.scan(k -> true).stream()
                .filter(b -> compartmentId == null || compartmentId.equals(b.getCompartmentId()))
                .filter(b -> from == null || !Instant.parse(b.getTimeCreated()).isBefore(from))
                .filter(b -> to == null || !Instant.parse(b.getTimeCreated()).isAfter(to))
                .filter(b -> lifecycleState == null || lifecycleState.equals(b.getLifecycleState()))
                .filter(b -> displayName == null || displayName.equals(b.getDisplayName()))
                .filter(b -> backupId == null || backupId.equals(b.getId()))
                .filter(b -> dbSystemId == null || dbSystemId.equals(b.getDbSystemId()))
                .filter(b -> backupSourceType == null || backupSourceType.equals(b.getSourceType()))
                .sorted(order)
                .toList();
    }

    /** Only the names, description, retention and tags can change, as in the SDK. */
    public synchronized StoredBackup updateBackup(String backupId, Map<String, Object> details, String ifMatch) {
        StoredBackup backup = active(backupId, "updated");
        Etags.checkIfMatch(ifMatch, backup.getEtag());
        Integer retentionPeriod = retention(details);
        if (PsqlRequests.string(details, "displayName") != null) {
            backup.setDisplayName(PsqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            backup.setDescription(PsqlRequests.string(details, "description"));
        }
        if (retentionPeriod != null) {
            backup.setRetentionPeriod(retentionPeriod);
        }
        if (details.containsKey("freeformTags")) {
            backup.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
        }
        if (details.containsKey("definedTags")) {
            backup.setDefinedTags(PsqlRequests.definedTags(details));
        }
        touch(backup);
        backups.put(backup.getId(), backup);
        return backup;
    }

    /**
     * Deletes the backup, which stays readable as DELETED. Its dump file stays in the backup
     * store (a copy may share it) until a state reset removes the volume.
     */
    public synchronized String deleteBackup(String backupId, String ifMatch) {
        StoredBackup backup = getBackup(backupId);
        Etags.checkIfMatch(ifMatch, backup.getEtag());
        if (!"ACTIVE".equals(backup.getLifecycleState()) && !"FAILED".equals(backup.getLifecycleState())) {
            throw OciException.conflict("Backup " + backupId + " is " + backup.getLifecycleState()
                    + " and cannot be deleted");
        }
        backup.setLifecycleState("DELETED");
        touch(backup);
        backups.put(backup.getId(), backup);
        LOG.infov("deleteBackup {0}", backupId);
        return workRequests.succeeded(PsqlService.WR_SERVICE, "DELETE_POSTGRESQL_DB_SYSTEM_BACKUP",
                backup.getCompartmentId(), resources(backup, "DELETED"));
    }

    public synchronized String changeCompartment(String backupId, String compartmentId, String ifMatch) {
        StoredBackup backup = active(backupId, "moved");
        Etags.checkIfMatch(ifMatch, backup.getEtag());
        backup.setCompartmentId(compartmentId);
        touch(backup);
        backups.put(backup.getId(), backup);
        return workRequests.succeeded(PsqlService.WR_SERVICE, "MOVE_POSTGRESQL_DB_SYSTEM_BACKUP", compartmentId,
                resources(backup, "UPDATED"));
    }

    /**
     * Copies the backup into each of {@code regions}: an ACTIVE {@code COPIED} backup in that
     * region of the tenancy, sharing the dump, and a {@code COPIED} entry in the source's
     * {@code copyStatus}. Returns the work request id.
     */
    public synchronized String copyBackup(String backupId, Map<String, Object> details, String ifMatch) {
        StoredBackup source = active(backupId, "copied");
        Etags.checkIfMatch(ifMatch, source.getEtag());
        String compartmentId = PsqlRequests.requireString(details, "compartmentId");
        List<String> regions = PsqlRequests.stringList(details, "regions");
        if (regions == null || regions.isEmpty()) {
            throw OciException.missingParameter("Missing required parameter: regions");
        }
        for (String region : regions) {
            if (Regions.byName(region).isEmpty()) {
                throw OciException.invalidParameter("Unknown region: " + region);
            }
        }
        requireRoomForCopies(source.getTenancyId(), regions);
        Integer retentionPeriod = retention(details);
        String now = clock.instant().toString();
        List<StoredWorkRequest.Resource> resources = new ArrayList<>();
        List<Map<String, Object>> copyStatus = new ArrayList<>(source.getCopyStatus() != null
                ? source.getCopyStatus() : List.of());
        for (String region : regions) {
            StoredBackup copy = new StoredBackup();
            copy.setId(Ocids.generate("postgresqlbackup", ociContext.realmOf(region), Regions.code(region)));
            copy.setDisplayName(source.getDisplayName());
            copy.setDescription(source.getDescription());
            copy.setCompartmentId(compartmentId);
            copy.setTimeCreated(now);
            copy.setLifecycleState("ACTIVE");
            copy.setSourceType("COPIED");
            copy.setBackupSize(source.getBackupSize());
            copy.setRetentionPeriod(retentionPeriod != null ? retentionPeriod : source.getRetentionPeriod());
            copy.setDbSystemId(source.getDbSystemId());
            copy.setDbSystemDetails(source.getDbSystemDetails());
            copy.setSourceBackupDetails(Map.of("sourceRegion", source.getRegion(), "sourceBackupId", source.getId()));
            copy.setTenancyId(source.getTenancyId());
            copy.setRegion(region);
            copy.setDumpId(source.getDumpId());
            copy.setTimeDumped(source.getTimeDumped());
            copy.setEtag(Etags.newEtag());
            TenancyAwareStorageBackend.putIn(backups,
                    TenancyAwareStorageBackend.regionalPartition(source.getTenancyId(), region), copy.getId(), copy);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("region", region);
            status.put("state", "COPIED");
            status.put("backupId", copy.getId());
            copyStatus.add(status);
            resources.add(WorkRequestService.resource(ENTITY_TYPE, "CREATED", copy.getId(), null));
        }
        source.setCopyStatus(copyStatus);
        touch(source);
        backups.put(source.getId(), source);
        return workRequests.succeeded(PsqlService.WR_SERVICE, "CREATE_POSTGRESQL_DB_SYSTEM_BACKUP",
                source.getCompartmentId(), resources);
    }

    // ── Restore sources ────────────────────────────────────────────────────────

    /** An ACTIVE backup to restore, in the caller's tenancy and region. */
    public StoredBackup requireRestorable(String backupId) {
        return backups.get(backupId)
                .filter(b -> "ACTIVE".equals(b.getLifecycleState()))
                .orElseThrow(() -> new OciException("RelatedResourceNotAuthorizedOrNotFound",
                        "Authorization failed or requested resource not found: backup " + backupId, 400));
    }

    /**
     * The newest ACTIVE backup of {@code dbSystemId} taken at or before {@code timeToRestore}:
     * floci-oci archives no WAL, so a point in time resolves to the backup before it.
     */
    public StoredBackup requireAtOrBefore(String dbSystemId, String timeToRestore) {
        Instant time = instant(timeToRestore, "timeToRestore");
        return backups.scan(k -> true).stream()
                .filter(b -> dbSystemId.equals(b.getDbSystemId()) && "ACTIVE".equals(b.getLifecycleState()))
                .filter(b -> !Instant.parse(b.getTimeDumped()).isAfter(time))
                .max(Comparator.comparing(b -> Instant.parse(b.getTimeDumped())))
                .orElseThrow(() -> OciException.invalidParameter("DB system " + dbSystemId
                        + " has no backup at or before " + timeToRestore + " to recover to"));
    }

    /**
     * The PitrDetails of {@code system}: ACTIVE with a window from its first backup to now when
     * its management policy has a STANDARD point-in-time recovery policy, INACTIVE otherwise.
     */
    public Map<String, Object> pitrDetails(StoredDbSystem system) {
        Object pitrPolicy = system.getManagementPolicy() != null ? system.getManagementPolicy().get("pitrPolicy") : null;
        boolean standard = pitrPolicy instanceof Map<?, ?> policy && "STANDARD".equals(policy.get("kind"));
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("pitrState", standard ? "ACTIVE" : "INACTIVE");
        List<Map<String, Object>> windows = new ArrayList<>();
        if (standard) {
            backups.scan(k -> true).stream()
                    .filter(b -> system.getId().equals(b.getDbSystemId()) && "ACTIVE".equals(b.getLifecycleState()))
                    .map(b -> Instant.parse(b.getTimeDumped()))
                    .min(Comparator.naturalOrder())
                    .ifPresent(first -> windows.add(Map.of("timeRecoveryWindowStart", first.toString(),
                            "timeRecoveryWindowEnd", clock.instant().toString())));
        }
        wire.put("recoveryTimeWindows", windows);
        return wire;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void requireUnderLimit() {
        long live = backups.scan(k -> true).stream()
                .filter(b -> !"DELETED".equals(b.getLifecycleState()))
                .count();
        if (live >= LIMIT) {
            throw OciException.limitExceeded("The tenancy already has " + LIMIT
                    + " PostgreSQL backups in this region, the service limit");
        }
    }

    /** Every requested copy has to fit, counting a region named twice, before any is written. */
    private void requireRoomForCopies(String tenancyId, List<String> regions) {
        for (String region : Set.copyOf(regions)) {
            long live = TenancyAwareStorageBackend.scanAll(backups).stream()
                    .filter(b -> tenancyId.equals(b.getTenancyId()) && region.equals(b.getRegion()))
                    .filter(b -> !"DELETED".equals(b.getLifecycleState()))
                    .count();
            long requested = regions.stream().filter(region::equals).count();
            if (live + requested > LIMIT) {
                throw OciException.limitExceeded("The tenancy would have more than " + LIMIT
                        + " PostgreSQL backups in " + region + ", the service limit");
            }
        }
    }

    private StoredBackup active(String backupId, String action) {
        StoredBackup backup = getBackup(backupId);
        if (!"ACTIVE".equals(backup.getLifecycleState())) {
            throw OciException.conflict("Backup " + backupId + " is " + backup.getLifecycleState()
                    + " and cannot be " + action);
        }
        return backup;
    }

    private static Map<String, Object> dbSystemDetails(StoredDbSystem system) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("systemType", system.getSystemType());
        details.put("dbVersion", PsqlCatalog.major(system.getDbVersion()));
        if (system.getConfigId() != null) {
            details.put("configId", system.getConfigId());
        }
        return details;
    }

    private static Integer retention(Map<String, Object> details) {
        Integer retentionPeriod = PsqlRequests.integer(details, "retentionPeriod");
        if (retentionPeriod != null && retentionPeriod < 1) {
            throw OciException.invalidParameter("retentionPeriod must be at least 1 day");
        }
        return retentionPeriod;
    }

    private static Instant instant(String value, String name) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw OciException.invalidParameter("Invalid " + name + ": " + value);
        }
    }

    private void touch(StoredBackup backup) {
        backup.setTimeUpdated(clock.instant().toString());
        backup.setEtag(Etags.newEtag());
    }

    private static List<StoredWorkRequest.Resource> resources(StoredBackup backup, String actionType) {
        return List.of(WorkRequestService.resource(ENTITY_TYPE, actionType, backup.getId(),
                "/20220915/backups/" + backup.getId()));
    }

    private static OciException notFound(String type, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + type + " " + id);
    }
}
