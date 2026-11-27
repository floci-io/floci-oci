package io.floci.oci.services.database;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.NetworkClaims;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.NetworkUsage;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceConfigAccess;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Autonomous Databases (Serverless) on the Database service, API version {@code 20160918}
 * (oci-go-sdk/database DatabaseClient). Each mutation records a work request with an
 * {@code autonomousDatabase} resource, which Terraform reads to confirm the create.
 *
 * <p>In mock mode every operation is synchronous: a database is AVAILABLE as soon as it is
 * created and its work request SUCCEEDED. Otherwise each database runs in its own Autonomous
 * Database Free container (local/oracle/oci-docs/adb-free-spike.md). Create, Start, Stop,
 * Restart and Delete answer at once with PROVISIONING, STARTING, STOPPING, RESTARTING or
 * TERMINATING and an IN_PROGRESS work request; the Docker work runs on one worker thread, and a
 * poller moves a database to AVAILABLE once Docker reports the container healthy, or to
 * UNAVAILABLE when it exits or the ready timeout passes. A container is never recreated: after a
 * restart of floci-oci it is adopted, and a database whose container is gone is UNAVAILABLE.
 */
@ApplicationScoped
public class AutonomousDatabaseService implements Resettable, CompartmentUsage, NetworkUsage {

    private static final Logger LOG = Logger.getLogger(AutonomousDatabaseService.class);

    static final String SERVICE = "database";
    static final String API_PREFIX = "/20160918";
    static final String ENTITY_TYPE = "autonomousDatabase";
    private static final String BASE_PATH = API_PREFIX + "/autonomousDatabases/";

    static final Set<String> LIFECYCLE_STATES = Set.of("PROVISIONING", "AVAILABLE", "STOPPING", "STOPPED",
            "STARTING", "TERMINATING", "TERMINATED", "UNAVAILABLE", "RESTORE_IN_PROGRESS", "RESTORE_FAILED",
            "BACKUP_IN_PROGRESS", "SCALE_IN_PROGRESS", "AVAILABLE_NEEDS_ATTENTION", "UPDATING",
            "MAINTENANCE_IN_PROGRESS", "RESTARTING", "RECREATING", "ROLE_CHANGE_IN_PROGRESS", "UPGRADING",
            "INACCESSIBLE", "STANDBY", "TRANSPORTING");
    static final Set<String> DB_WORKLOADS = Set.of("OLTP", "DW", "AJD", "APEX", "LH");
    private static final Set<String> COMPUTE_MODELS = Set.of("ECPU", "OCPU");
    private static final Set<String> LICENSE_MODELS = Set.of("LICENSE_INCLUDED", "BRING_YOUR_OWN_LICENSE");
    private static final Set<String> DATABASE_EDITIONS = Set.of("STANDARD_EDITION", "ENTERPRISE_EDITION");
    private static final Set<String> MAINTENANCE_SCHEDULES = Set.of("EARLY", "REGULAR");
    private static final Set<String> OPEN_MODES = Set.of("READ_ONLY", "READ_WRITE");
    private static final Set<String> PERMISSION_LEVELS = Set.of("RESTRICTED", "UNRESTRICTED");
    private static final Set<String> SORT_FIELDS = Set.of("TIMECREATED", "DISPLAYNAME");
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    static final Set<String> INFRASTRUCTURE_TYPES = Set.of("CLOUD", "CLOUD_AT_CUSTOMER");

    /** CreateAutonomousDatabaseBase fields that are also AutonomousDatabase fields, so they are echoed. */
    private static final List<String> CREATE_ECHOED = List.of("subscriptionId", "availabilityDomain",
            "characterSet", "ncharacterSet", "cpuCoreCount", "backupRetentionPeriodInDays", "computeModel",
            "computeCount", "ocpuCount", "dataStorageSizeInTBs", "dataStorageSizeInGBs", "isFreeTier", "kmsKeyId",
            "vaultId", "encryptionKey", "licenseModel", "byolComputeCountLimit", "isAutoScalingEnabled", "isDevTier",
            "dbWorkload", "isDedicated", "autonomousContainerDatabaseId", "inMemoryPercentage",
            "isAccessControlEnabled", "whitelistedIps", "arePrimaryWhitelistedIpsUsed", "standbyWhitelistedIps",
            "isDataGuardEnabled", "isLocalDataGuardEnabled", "subnetId", "nsgIds", "privateEndpointLabel",
            "freeformTags", "definedTags", "securityAttributes", "privateEndpointIp", "dbVersion",
            "customerContacts", "isMtlsConnectionRequired", "resourcePoolLeaderId", "resourcePoolSummary",
            "autonomousMaintenanceScheduleType", "autonomousDatabaseMaintenanceWindow", "scheduledOperations",
            "isAutoScalingForStorageEnabled", "databaseEdition", "dbToolsDetails", "isBackupRetentionLocked");

    /** UpdateAutonomousDatabaseDetails fields that are also AutonomousDatabase fields, so they are echoed. */
    private static final List<String> UPDATE_ECHOED = List.of("backupRetentionPeriodInDays", "computeModel",
            "inMemoryPercentage", "localAdgAutoFailoverMaxDataLossLimit", "cpuCoreCount", "longTermBackupSchedule",
            "isDevTier", "computeCount", "ocpuCount", "dataStorageSizeInTBs", "dataStorageSizeInGBs",
            "freeformTags", "definedTags", "securityAttributes", "openMode", "permissionLevel", "dbWorkload",
            "dbVersion", "licenseModel", "byolComputeCountLimit", "isAccessControlEnabled", "whitelistedIps",
            "arePrimaryWhitelistedIpsUsed", "standbyWhitelistedIps", "isAutoScalingEnabled", "isRefreshableClone",
            "isLocalDataGuardEnabled", "isDataGuardEnabled", "peerDbId", "subnetId", "privateEndpointLabel",
            "privateEndpointIp", "nsgIds", "autoRefreshFrequencyInSeconds", "autoRefreshPointLagInSeconds",
            "timeOfAutoRefreshStart", "customerContacts", "isMtlsConnectionRequired", "resourcePoolLeaderId",
            "resourcePoolSummary", "autonomousMaintenanceScheduleType", "autonomousDatabaseMaintenanceWindow",
            "timeMaintenancePauseUntil", "scheduledOperations", "isAutoScalingForStorageEnabled",
            "databaseEdition", "dbToolsDetails", "isBackupRetentionLocked", "timeScheduledDbVersionUpgrade",
            "timeScheduledAdUpdate", "isFreeTier", "encryptionKey");

    /** Letters and digits, starting with a letter, at most 30 (Oracle's dbName rule). */
    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,29}");
    private static final DateTimeFormatter NAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    static final int MTLS_PORT = 1522;
    static final String MOCK_HOST = "localhost";
    private static final long READINESS_POLL_SECONDS = 5;
    private static final int SHUTDOWN_STOP_SECONDS = 30;
    private static final int LOG_TAIL_CHARS = 400;
    static final String INTERRUPTED = "The operation was interrupted by a restart of floci-oci";
    static final String SUPERSEDED = "The operation was superseded by another operation on the Autonomous Database";
    static final String CONTAINER_GONE = "The Autonomous Database Free container of this database is gone, and"
            + " the database with it: floci-oci never recreates a container, since a new one starts an empty"
            + " database. Delete this Autonomous Database and create a new one";
    /** The states an operation passes through; a request they refuse may succeed when retried. */
    private static final Set<String> TRANSIENT_STATES = Set.of("PROVISIONING", "STARTING", "STOPPING",
            "RESTARTING", "TERMINATING", "UPDATING");

    /** A database whose container is coming up: who owns it, since when, and whether it is its first boot. */
    record Pending(String tenancyId, String region, Instant since, boolean firstBoot) {
    }

    /** What a mutation returns: the database and the work request recorded for it. */
    public record Result(StoredAutonomousDatabase database, String workRequestId) {
    }

    private final StorageBackend<String, StoredAutonomousDatabase> databases;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;
    private final NetworkReferences networkReferences;
    private final Clock clock;
    private final AutonomousDatabaseContainerManager containers;
    private final Executor lifecycle;
    private final ExecutorService ownedLifecycle;
    private final ScheduledExecutorService readinessPoller;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Inject
    public AutonomousDatabaseService(StorageFactory storageFactory, EmulatorConfig config,
                                     ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                                     OciContext ociContext, Instance<NetworkReferences> networkReferences,
                                     AutonomousDatabaseContainerManager containers) {
        this(storageFactory.create(SERVICE, "database-autonomous-databases.json",
                        new TypeReference<Map<String, StoredAutonomousDatabase>>() {}),
                config, serviceRegistry, workRequests, ociContext, NetworkReferences.of(networkReferences),
                Clock.systemUTC(), containers, null);
    }

    /** Storage-injecting constructor for tests of the management plane: it never touches Docker. */
    AutonomousDatabaseService(StorageBackend<String, StoredAutonomousDatabase> databases, EmulatorConfig config,
                              ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                              OciContext ociContext, NetworkReferences networkReferences, Clock clock) {
        this(databases, config, serviceRegistry, workRequests, ociContext, networkReferences, clock, null, null);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param containers null for a service that never touches Docker, whatever the mock flag says
     * @param lifecycle  runs the Docker work; null for the service's own single worker thread
     */
    AutonomousDatabaseService(StorageBackend<String, StoredAutonomousDatabase> databases, EmulatorConfig config,
                              ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                              OciContext ociContext, NetworkReferences networkReferences, Clock clock,
                              AutonomousDatabaseContainerManager containers, Executor lifecycle) {
        this.databases = databases;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.networkReferences = networkReferences;
        this.clock = clock;
        this.containers = containers;
        // No thread starts until the first task is submitted, which only real mode does.
        this.ownedLifecycle = lifecycle != null ? null : Executors.newSingleThreadExecutor(daemon("adb-lifecycle"));
        this.lifecycle = lifecycle != null ? lifecycle : ownedLifecycle;
        this.readinessPoller = Executors.newSingleThreadScheduledExecutor(daemon("adb-readiness"));
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("database")
                .enabled(config.services().database().enabled())
                .storageKey("database")
                .resourceClasses(AutonomousDatabaseController.class)
                .build());
        if (realMode() && config.services().database().enabled()) {
            readinessPoller.scheduleWithFixedDelay(this::pollReadinessSafely,
                    READINESS_POLL_SECONDS, READINESS_POLL_SECONDS, TimeUnit.SECONDS);
            adoptPersistedDatabases();
        }
    }

    /**
     * Stops every container, which keeps it and its data for the next run to adopt. With
     * in-memory storage the databases die with the emulator, so their containers are removed.
     */
    @PreDestroy
    void shutdown() {
        readinessPoller.shutdownNow();
        if (ownedLifecycle != null) {
            ownedLifecycle.shutdownNow();
        }
        if (!realMode()) {
            return;
        }
        boolean ephemeral = "memory".equalsIgnoreCase(new ServiceConfigAccess(config).storageMode(SERVICE));
        for (StoredAutonomousDatabase database : allDatabases()) {
            if (database.getContainerName() == null || "TERMINATED".equals(database.getLifecycleState())) {
                continue;
            }
            try {
                if (ephemeral) {
                    containers.remove(database);
                } else {
                    containers.stopForShutdown(database, SHUTDOWN_STOP_SECONDS);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Could not stop the container of Autonomous Database {0}: {1}", database.getId(),
                        e.getMessage());
            }
        }
    }

    /**
     * Removes every container, then forgets every database. Holds the lock lifecycle steps
     * record their outcome under, so a step still running finds its database gone and removes
     * what it started.
     */
    @Override
    public synchronized void clear() {
        pending.clear();
        if (realMode()) {
            for (StoredAutonomousDatabase database : allDatabases()) {
                if (database.getContainerName() != null) {
                    removeQuietly(database);
                }
            }
        }
        databases.clear();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return TenancyAwareStorageBackend.scanTenancy(databases, tenancyId).stream()
                .filter(database -> compartmentId.equals(database.getCompartmentId()))
                .filter(database -> !"TERMINATED".equals(database.getLifecycleState()))
                .map(database -> "Autonomous Database " + database.getId())
                .toList();
    }

    @Override
    public List<String> resourcesUsing(String networkId) {
        return NetworkUsage.using(databases.scan(k -> true), networkId, StoredAutonomousDatabase::getLifecycleState,
                database -> NetworkUsage.ids(database.getAttributes().get("subnetId"),
                        database.getAttributes().get("nsgIds")),
                database -> "Autonomous Database " + database.getId()).toList();
    }

    /** The network ids a create or update body names, whether or not they are well formed. */
    private static Set<String> networkIds(Map<String, Object> details) {
        return details == null ? Set.of()
                : NetworkUsage.ids(details.get("subnetId"), details.get("nsgIds"));
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** The subnet and NSGs are claimed from their check until the database is stored. */
    public Result create(Map<String, Object> details) {
        try (NetworkClaims.Claim claim = NetworkClaims.claim(networkIds(details))) {
            return createDatabase(details);
        }
    }

    private synchronized Result createDatabase(Map<String, Object> details) {
        String compartmentId = DatabaseRequests.requireString(details, "compartmentId");
        String source = DatabaseRequests.string(details, "source");
        if (source != null && !"NONE".equals(source)) {
            throw OciException.invalidParameter("source " + source
                    + " is not supported: floci-oci creates Autonomous Databases from scratch only (source NONE)");
        }
        String adminPassword = DatabaseRequests.string(details, "adminPassword");
        if (realMode() && adminPassword == null && DatabaseRequests.string(details, "secretId") != null) {
            throw OciException.notImplemented("floci-oci cannot read the admin password of a real Autonomous"
                    + " Database from a Vault secret yet: send adminPassword instead of secretId");
        }
        if (realMode() || DatabaseRequests.string(details, "secretId") == null) {
            requireAdminPassword(DatabaseRequests.requireString(details, "adminPassword"));
        }
        networkReferences.requireSubnet(DatabaseRequests.string(details, "subnetId"));
        networkReferences.requireNsgs(DatabaseRequests.stringList(details, "nsgIds"));

        Instant now = clock.instant();
        String dbName = DatabaseRequests.string(details, "dbName");
        if (dbName != null) {
            requireDbName(dbName);
            requireUniqueDbName(dbName, null);
        } else {
            dbName = generatedDbName(now);
        }
        StoredAutonomousDatabase database = new StoredAutonomousDatabase();
        database.setId(Ocids.generate("autonomousdatabase", ociContext.realm(), ociContext.regionCode()));
        database.setCompartmentId(compartmentId);
        database.setDbName(dbName);
        String displayName = DatabaseRequests.string(details, "displayName");
        database.setDisplayName(displayName != null ? displayName : dbName);
        database.setTimeCreated(now.toString());
        database.setTenancyId(ociContext.tenancyId());
        database.setRegion(ociContext.region());

        Map<String, Object> attributes = defaults();
        for (String key : CREATE_ECHOED) {
            if (details.get(key) != null) {
                attributes.put(key, details.get(key));
            }
        }
        validateEnums(attributes);
        reconcileStorage(attributes, details);
        reconcileCompute(attributes, details, 1);
        database.setAttributes(attributes);
        database.setContainerName(containers != null ? containers.containerName(database.getId()) : null);
        refreshUrls(database);
        database.setEtag(Etags.newEtag());
        if (!realMode()) {
            refreshConnectionStrings(database);
            database.setLifecycleState("AVAILABLE");
            databases.put(database.getId(), database);
            LOG.infov("createAutonomousDatabase {0} ({1})", database.getId(), dbName);
            return new Result(database, record("CREATE_AUTONOMOUS_DATABASE", database, "CREATED"));
        }
        database.setLifecycleState("PROVISIONING");
        String workRequestId = workRequests.inProgress(SERVICE, "CREATE_AUTONOMOUS_DATABASE", compartmentId,
                resources(database, "CREATED"));
        database.setWorkRequestId(workRequestId);
        // Stored before the create is queued: the worker looks the database up by its partition.
        databases.put(database.getId(), database);
        launchCreate(database, adminPassword);
        LOG.infov("createAutonomousDatabase {0} ({1}), provisioning its container", database.getId(), dbName);
        return new Result(database, workRequestId);
    }

    /** A terminated database is still returned, as TERMINATED: Terraform polls it to see the delete finish. */
    public StoredAutonomousDatabase get(String autonomousDatabaseId) {
        return databases.get(autonomousDatabaseId).orElseThrow(() -> notFound(autonomousDatabaseId));
    }

    public List<StoredAutonomousDatabase> list(String compartmentId, ListFilter filter) {
        DatabaseRequests.requireEnum(filter.lifecycleState(), LIFECYCLE_STATES, "lifecycleState");
        DatabaseRequests.requireEnum(filter.lifecycleStateNotEqualTo(), LIFECYCLE_STATES, "lifecycleStateNotEqualTo");
        DatabaseRequests.requireEnum(filter.dbWorkload(), DB_WORKLOADS, "dbWorkload");
        DatabaseRequests.requireEnum(filter.infrastructureType(), INFRASTRUCTURE_TYPES, "infrastructureType");
        DatabaseRequests.requireEnum(filter.sortBy(), SORT_FIELDS, "sortBy");
        DatabaseRequests.requireEnum(filter.sortOrder(), SORT_ORDERS, "sortOrder");
        // TIMECREATED sorts newest first by default, DISPLAYNAME ascending, as the SDK documents.
        boolean byName = "DISPLAYNAME".equals(filter.sortBy());
        Comparator<StoredAutonomousDatabase> order = byName
                ? Comparator.comparing(StoredAutonomousDatabase::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparing(StoredAutonomousDatabase::getTimeCreated);
        order = order.thenComparing(StoredAutonomousDatabase::getId);
        boolean descending = filter.sortOrder() != null ? "DESC".equals(filter.sortOrder()) : !byName;
        if (descending) {
            order = order.reversed();
        }
        return databases.scan(k -> true).stream()
                .filter(database -> compartmentId.equals(database.getCompartmentId()))
                .filter(database -> filter.lifecycleState() == null
                        || filter.lifecycleState().equals(database.getLifecycleState()))
                .filter(database -> filter.lifecycleStateNotEqualTo() == null
                        || !filter.lifecycleStateNotEqualTo().equals(database.getLifecycleState()))
                .filter(database -> filter.displayName() == null
                        || filter.displayName().equals(database.getDisplayName()))
                .filter(database -> filter.dbWorkload() == null
                        || filter.dbWorkload().equals(database.getAttributes().get("dbWorkload")))
                .filter(database -> filter.dbVersion() == null
                        || filter.dbVersion().equals(database.getAttributes().get("dbVersion")))
                .filter(database -> filter.isFreeTier() == null
                        || filter.isFreeTier().equals(database.getAttributes().get("isFreeTier")))
                .filter(database -> filter.isDataGuardEnabled() == null
                        || filter.isDataGuardEnabled().equals(database.getAttributes().get("isDataGuardEnabled")))
                .filter(database -> filter.autonomousContainerDatabaseId() == null
                        || filter.autonomousContainerDatabaseId()
                        .equals(database.getAttributes().get("autonomousContainerDatabaseId")))
                .filter(database -> filter.infrastructureType() == null
                        || filter.infrastructureType().equals(database.getAttributes().get("infrastructureType")))
                // No database is a refreshable clone or a resource pool leader.
                .filter(database -> !Boolean.TRUE.equals(filter.isRefreshableClone()))
                .filter(database -> !Boolean.TRUE.equals(filter.isResourcePoolLeader()))
                .filter(database -> filter.resourcePoolLeaderId() == null
                        || filter.resourcePoolLeaderId().equals(database.getAttributes().get("resourcePoolLeaderId")))
                .sorted(order)
                .toList();
    }

    /** The ListAutonomousDatabases query filters. */
    public record ListFilter(String lifecycleState, String lifecycleStateNotEqualTo, String displayName,
                             String dbWorkload, String dbVersion, Boolean isFreeTier, Boolean isDataGuardEnabled,
                             Boolean isRefreshableClone, Boolean isResourcePoolLeader, String resourcePoolLeaderId,
                             String autonomousContainerDatabaseId, String infrastructureType, String sortBy,
                             String sortOrder) {
    }

    /** The subnet and NSGs are claimed from their check until the update is stored. */
    public Result update(String autonomousDatabaseId, Map<String, Object> details, String ifMatch) {
        try (NetworkClaims.Claim claim = NetworkClaims.claim(networkIds(details))) {
            return updateDatabase(autonomousDatabaseId, details, ifMatch);
        }
    }

    private synchronized Result updateDatabase(String autonomousDatabaseId, Map<String, Object> details,
                                               String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "AVAILABLE", "STOPPED");
        // Everything that can be rejected is checked before anything changes: the store hands out
        // the stored object itself, so a half-applied update would stick.
        String adminPassword = DatabaseRequests.string(details, "adminPassword");
        if (adminPassword != null) {
            requireAdminPassword(adminPassword);
        }
        String dbName = DatabaseRequests.string(details, "dbName");
        if (dbName != null) {
            requireDbName(dbName);
            requireUniqueDbName(dbName, autonomousDatabaseId);
        }
        if (realMode() && (adminPassword != null || (dbName != null && !dbName.equals(database.getDbName())))) {
            throw OciException.notImplemented("floci-oci cannot change the "
                    + (adminPassword != null ? "admin password" : "dbName")
                    + " of a real Autonomous Database: its container was created with them");
        }
        networkReferences.requireSubnet(DatabaseRequests.string(details, "subnetId"));
        networkReferences.requireNsgs(DatabaseRequests.stringList(details, "nsgIds"));
        Map<String, Object> attributes = new LinkedHashMap<>(database.getAttributes());
        for (String key : UPDATE_ECHOED) {
            if (details.containsKey(key)) {
                attributes.put(key, details.get(key));
            }
        }
        validateEnums(attributes);
        reconcileStorage(attributes, details);
        reconcileCompute(attributes, details, database.getAttributes().get("computeCount"));

        if (dbName != null) {
            database.setDbName(dbName);
        }
        String displayName = DatabaseRequests.string(details, "displayName");
        if (displayName != null) {
            database.setDisplayName(displayName);
        }
        database.setAttributes(attributes);
        refreshUrls(database);
        if (!realMode()) {
            refreshConnectionStrings(database);
        }
        touch(database);
        return new Result(database, record("UPDATE_AUTONOMOUS_DATABASE", database, "UPDATED"));
    }

    public synchronized String delete(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, LIFECYCLE_STATES.stream()
                .filter(state -> !"TERMINATING".equals(state)).toArray(String[]::new));
        if (!realMode()) {
            database.setLifecycleState("TERMINATED");
            database.setLifecycleDetails(null);
            touch(database);
            LOG.infov("deleteAutonomousDatabase {0}", autonomousDatabaseId);
            return record("DELETE_AUTONOMOUS_DATABASE", database, "DELETED");
        }
        // An operation under way is abandoned: its work request ends CANCELED, and its step is
        // skipped, or its outcome dropped, since the database has moved on to another work request.
        pending.remove(autonomousDatabaseId);
        finishWorkRequest(database, "CANCELED", null);
        String workRequestId = begin(database, "TERMINATING", "DELETE_AUTONOMOUS_DATABASE", "DELETED");
        runDelete(database);
        return workRequestId;
    }

    /**
     * Queues the removal of the container; the database then stays readable as TERMINATED. When
     * Docker cannot remove it the database is UNAVAILABLE with the error instead, keeps its
     * container and port, and can be deleted again.
     */
    private void runDelete(StoredAutonomousDatabase database) {
        runAsync(database, current -> {
            try {
                containers.remove(current);
            } catch (RuntimeException e) {
                return () -> fail(current, "The container could not be removed: " + e.getMessage(), false);
            }
            return () -> {
                current.setLifecycleState("TERMINATED");
                current.setLifecycleDetails(null);
                touch(current);
                finishWorkRequest(current, "SUCCEEDED", null);
                LOG.infov("deleteAutonomousDatabase {0}", current.getId());
            };
        });
    }

    // Every mutation holds the service lock from its state check until its step is queued, so a
    // second one sees the transient state and is refused with IncorrectState.

    /** A start retried after a failed one finds the database UNAVAILABLE, which it may also start. */
    public synchronized Result start(String autonomousDatabaseId, String ifMatch) {
        if (!realMode()) {
            StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "STOPPED");
            database.setLifecycleState("AVAILABLE");
            touch(database);
            return new Result(database, record("START_AUTONOMOUS_DATABASE", database, "UPDATED"));
        }
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "STOPPED", "UNAVAILABLE");
        String workRequestId = begin(database, "STARTING", "START_AUTONOMOUS_DATABASE", "UPDATED");
        launchStart(database, false);
        return new Result(database, workRequestId);
    }

    public synchronized Result stop(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "AVAILABLE");
        if (!realMode()) {
            database.setLifecycleState("STOPPED");
            touch(database);
            return new Result(database, record("STOP_AUTONOMOUS_DATABASE", database, "UPDATED"));
        }
        String workRequestId = begin(database, "STOPPING", "STOP_AUTONOMOUS_DATABASE", "UPDATED");
        runStop(database);
        return new Result(database, workRequestId);
    }

    private void runStop(StoredAutonomousDatabase database) {
        runAsync(database, current -> {
            try {
                containers.stop(current);
            } catch (RuntimeException e) {
                return () -> fail(current, "The container could not be stopped: " + e.getMessage(), false);
            }
            return () -> {
                current.setLifecycleState("STOPPED");
                current.setLifecycleDetails(null);
                touch(current);
                finishWorkRequest(current, "SUCCEEDED", null);
            };
        });
    }

    public synchronized Result restart(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "AVAILABLE");
        if (!realMode()) {
            touch(database);
            return new Result(database, record("RESTART_AUTONOMOUS_DATABASE", database, "UPDATED"));
        }
        String workRequestId = begin(database, "RESTARTING", "RESTART_AUTONOMOUS_DATABASE", "UPDATED");
        runAsync(database, current -> {
            try {
                containers.restart(current);
            } catch (RuntimeException e) {
                return () -> fail(current, "The container could not be restarted: " + e.getMessage(), false);
            }
            return () -> pending.put(current.getId(), new Pending(current.getTenancyId(), current.getRegion(),
                    clock.instant(), false));
        });
        return new Result(database, workRequestId);
    }

    /**
     * GenerateAutonomousDatabaseWallet: a zip of the client configuration for the database.
     * The password protects the keystores of a real wallet, so it is checked by Oracle's rule
     * even though a mock wallet carries no keystore.
     */
    public byte[] generateWallet(String autonomousDatabaseId, Map<String, Object> details) {
        StoredAutonomousDatabase database = get(autonomousDatabaseId);
        if ("TERMINATED".equals(database.getLifecycleState())) {
            throw notFound(autonomousDatabaseId);
        }
        String password = DatabaseRequests.requireString(details, "password");
        requireWalletPassword(password);
        DatabaseRequests.requireEnum(DatabaseRequests.string(details, "generateType"), Set.of("ALL", "SINGLE"),
                "generateType");
        if (!realMode()) {
            return AutonomousDatabaseWallet.zip(database.getDbName(), MOCK_HOST, MTLS_PORT);
        }
        // The container writes its wallet on its first start, which ends when it is first AVAILABLE.
        if (database.getAttributes().get("connectionStrings") == null
                || "TERMINATING".equals(database.getLifecycleState())) {
            throw new OciException("IncorrectState", "Autonomous Database " + autonomousDatabaseId + " is "
                    + database.getLifecycleState() + "; its wallet exists once it has been AVAILABLE", 409);
        }
        try {
            return containers.wallet(database, password);
        } catch (RuntimeException e) {
            if (CONTAINER_GONE.equals(e.getMessage())) {
                throw OciException.conflict(CONTAINER_GONE);
            }
            throw OciException.internalServerError("Could not generate the wallet of Autonomous Database "
                    + autonomousDatabaseId + ": " + e.getMessage());
        }
    }

    // ── Lifecycle engine ───────────────────────────────────────────────────────

    boolean realMode() {
        return containers != null && !config.services().database().mock();
    }

    /**
     * Moves the database to {@code state} and opens the work request the operation is tracked
     * by. Called under the service lock. A work request the database still carries is failed
     * first, so replacing it never leaves it IN_PROGRESS.
     */
    private String begin(StoredAutonomousDatabase database, String state, String operationType, String actionType) {
        finishWorkRequest(database, "FAILED", SUPERSEDED);
        database.setLifecycleState(state);
        database.setLifecycleDetails(null);
        String workRequestId = workRequests.inProgress(SERVICE, operationType, database.getCompartmentId(),
                resources(database, actionType));
        database.setWorkRequestId(workRequestId);
        touch(database);
        return workRequestId;
    }

    /** The Docker work of an operation, returning what to record once it is done. */
    private interface LifecycleStep {
        Runnable run(StoredAutonomousDatabase current);
    }

    /**
     * Runs {@code step} on the lifecycle worker against the database as stored at that moment,
     * found in its own partition since the worker has no request scope. The step is skipped when
     * the database has gone, or has moved on to another operation, which shows as a different
     * work request. Ownership is checked again before the outcome is recorded, under the lock
     * every mutation and reset take: an outcome that arrives too late is dropped. A skipped or
     * dropped step still finishes its own work request, so no caller is left polling IN_PROGRESS.
     */
    private void runAsync(StoredAutonomousDatabase database, LifecycleStep step) {
        String tenancyId = database.getTenancyId();
        String region = database.getRegion();
        String id = database.getId();
        String workRequestId = database.getWorkRequestId();
        lifecycle.execute(() -> {
            Optional<StoredAutonomousDatabase> current;
            synchronized (this) {
                current = owned(tenancyId, region, id, workRequestId);
                if (current.isEmpty()) {
                    LOG.debugv("Autonomous Database {0} has moved on; skipping its queued lifecycle step", id);
                    finishAbandoned(tenancyId, region, id, workRequestId);
                    return;
                }
            }
            Runnable outcome;
            try {
                outcome = step.run(current.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Lifecycle step failed for Autonomous Database {0}", id);
                outcome = () -> fail(current.get(), e.getMessage(), false);
            }
            synchronized (this) {
                if (owned(tenancyId, region, id, workRequestId).isPresent()) {
                    outcome.run();
                } else if (findIn(tenancyId, region, id).isEmpty()) {
                    // A reset forgot the database while its step ran: remove what the step started.
                    LOG.debugv("Autonomous Database {0} was reset during its lifecycle step", id);
                    removeQuietly(current.get());
                } else {
                    LOG.debugv("Autonomous Database {0} moved on during its lifecycle step", id);
                    finishAbandoned(tenancyId, region, id, workRequestId);
                }
            }
        });
    }

    /**
     * Fails the work request of a step whose database moved on without finishing it. Whoever
     * moved it on normally finished it already (a delete cancels it), and finishing a finished
     * work request changes nothing. A database that is gone was reset, with its work requests.
     */
    private void finishAbandoned(String tenancyId, String region, String id, String workRequestId) {
        if (workRequestId == null || findIn(tenancyId, region, id).isEmpty()) {
            return;
        }
        workRequests.finish(tenancyId, region, workRequestId, "FAILED",
                List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", SUPERSEDED)));
    }

    /** The database, while {@code workRequestId} is still the operation it is running. */
    private Optional<StoredAutonomousDatabase> owned(String tenancyId, String region, String id,
                                                     String workRequestId) {
        return findIn(tenancyId, region, id)
                .filter(database -> Objects.equals(workRequestId, database.getWorkRequestId()));
    }

    /**
     * Queues the first start: the image pull (about 12 GB the first time), then the container,
     * which receives the admin password in its environment. The password is not kept anywhere
     * else. The poller takes over once the container runs.
     */
    private void launchCreate(StoredAutonomousDatabase database, String adminPassword) {
        runAsync(database, current -> {
            try {
                containers.create(current, adminPassword);
            } catch (RuntimeException e) {
                return () -> fail(current, "The Autonomous Database Free container could not be started: "
                        + e.getMessage(), false);
            }
            return () -> {
                saveIn(current);
                pending.put(current.getId(), new Pending(current.getTenancyId(), current.getRegion(),
                        clock.instant(), true));
            };
        });
    }

    /** Queues a start of the existing container; the poller takes over once it runs. */
    private void launchStart(StoredAutonomousDatabase database, boolean firstBoot) {
        runAsync(database, current -> {
            try {
                containers.start(current);
            } catch (RuntimeException e) {
                return () -> fail(current, "The container could not be started: " + e.getMessage(), false);
            }
            return () -> pending.put(current.getId(), new Pending(current.getTenancyId(), current.getRegion(),
                    clock.instant(), firstBoot));
        });
    }

    private void pollReadinessSafely() {
        try {
            pollReadiness();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Autonomous Database readiness poll failed");
        }
    }

    /**
     * One pass over the databases whose container is coming up: AVAILABLE once Docker reports it
     * healthy, UNAVAILABLE when it has exited or the ready timeout has passed.
     */
    void pollReadiness() {
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            String id = entry.getKey();
            Pending waiting = entry.getValue();
            Optional<StoredAutonomousDatabase> found = findIn(waiting.tenancyId(), waiting.region(), id);
            if (found.isEmpty()) {
                pending.remove(id);
                continue;
            }
            try {
                pollOne(waiting, found.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Readiness poll failed for Autonomous Database {0}", id);
            }
        }
    }

    private void pollOne(Pending waiting, StoredAutonomousDatabase database) {
        int timeout = config.services().database().readyTimeoutSeconds();
        AutonomousDatabaseContainerManager.Health health = health(database);
        switch (health) {
            case HEALTHY -> {
                Map<String, Object> connectionStrings = connectionStrings(database);
                settle(database, () -> {
                    database.getAttributes().put("connectionStrings", connectionStrings);
                    database.setLifecycleState("AVAILABLE");
                    database.setLifecycleDetails(null);
                    touch(database);
                    finishWorkRequest(database, "SUCCEEDED", null);
                    LOG.infov("Autonomous Database {0} is AVAILABLE", database.getId());
                });
            }
            case MISSING -> settle(database, () -> fail(database, CONTAINER_GONE, false));
            case EXITED -> {
                String logs = waiting.firstBoot() ? logTail(database) : "";
                settle(database, () -> fail(database, "The Autonomous Database Free container exited before it"
                        + " was healthy" + (logs.isEmpty() ? "" : ": " + logs), waiting.firstBoot()));
            }
            case STARTING, UNHEALTHY -> {
                if (clock.instant().isAfter(waiting.since().plusSeconds(timeout))) {
                    settle(database, () -> fail(database, "The Autonomous Database Free container was not healthy"
                            + " after " + timeout + " seconds (health: " + health.name().toLowerCase(Locale.ROOT)
                            + "); raise floci-oci.services.database.ready-timeout-seconds on a slow host",
                            waiting.firstBoot()));
                }
            }
        }
    }

    /** A probe that throws means not ready yet; the timeout still applies. */
    private AutonomousDatabaseContainerManager.Health health(StoredAutonomousDatabase database) {
        try {
            return containers.health(database);
        } catch (RuntimeException e) {
            LOG.debugv("Could not inspect Autonomous Database {0}: {1}", database.getId(), e.getMessage());
            return AutonomousDatabaseContainerManager.Health.STARTING;
        }
    }

    private String logTail(StoredAutonomousDatabase database) {
        try {
            return containers.logTail(database, LOG_TAIL_CHARS);
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the log of Autonomous Database {0}: {1}", database.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * The connection strings the container's own tnsnames.ora names, at the address clients
     * reach the container at. When the file cannot be read, the aliases dbName predicts.
     */
    private Map<String, Object> connectionStrings(StoredAutonomousDatabase database) {
        EndpointInfo endpoint = containers.clientEndpoint(database);
        try {
            Map<String, String> entries = AutonomousDatabaseWallet.mtlsEntries(containers.tnsnames(database),
                    endpoint.host(), endpoint.port());
            if (!entries.isEmpty()) {
                return AutonomousDatabaseWallet.connectionStrings(entries, endpoint.host(), endpoint.port());
            }
            LOG.warnv("The tnsnames.ora of Autonomous Database {0} names no alias", database.getId());
        } catch (RuntimeException e) {
            LOG.warnv("Could not read the tnsnames.ora of Autonomous Database {0}: {1}", database.getId(),
                    e.getMessage());
        }
        return AutonomousDatabaseWallet.connectionStrings(database.getDbName(), endpoint.host(), endpoint.port());
    }

    /**
     * Records an outcome only while the database is still pending, atomically with dropping it
     * from {@link #pending}: a delete or reset that removed it during the probe wins. Holds the
     * service lock, so no mutation sees the new state before its work request has finished.
     */
    private synchronized void settle(StoredAutonomousDatabase database, Runnable outcome) {
        AtomicBoolean recorded = new AtomicBoolean();
        pending.computeIfPresent(database.getId(), (id, waiting) -> {
            outcome.run();
            recorded.set(true);
            return null;
        });
        if (!recorded.get()) {
            LOG.debugv("Autonomous Database {0} left pending before its start was settled", database.getId());
        }
    }

    /**
     * The Autonomous Database API has no FAILED state: a failed operation leaves the database
     * UNAVAILABLE, with the reason in lifecycleDetails, and fails its work request (inferred). The
     * container stays, since it holds the data, unless {@code removeContainer}: a first start that
     * failed has no data, and the admin password it would need again is gone.
     */
    private void fail(StoredAutonomousDatabase database, String message, boolean removeContainer) {
        if (removeContainer) {
            removeQuietly(database);
        }
        database.setLifecycleState("UNAVAILABLE");
        database.setLifecycleDetails(message);
        touch(database);
        finishWorkRequest(database, "FAILED", message);
        LOG.warnv("Autonomous Database {0} is UNAVAILABLE: {1}", database.getId(), message);
    }

    private void finishWorkRequest(StoredAutonomousDatabase database, String status, String error) {
        if (database.getWorkRequestId() == null) {
            return;
        }
        List<StoredWorkRequest.ErrorEntry> errors = error == null ? List.of()
                : List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", error));
        workRequests.finish(database.getTenancyId(), database.getRegion(), database.getWorkRequestId(), status,
                errors);
        database.setWorkRequestId(null);
    }

    /**
     * After a restart of floci-oci, takes over the container of every database instead of
     * creating a new one, which would start an empty database. A database whose container is gone
     * is UNAVAILABLE with {@link #CONTAINER_GONE}, unless it was being deleted, which then
     * completes. Otherwise the operation the restart interrupted carries on under its own work
     * request: a delete removes the container, a stop stops it, and anything else, including a
     * database that was AVAILABLE, starts it and waits for it to be healthy again.
     */
    void adoptPersistedDatabases() {
        for (StoredAutonomousDatabase database : allDatabases()) {
            String state = database.getLifecycleState();
            if ("TERMINATED".equals(state)) {
                continue;
            }
            if (database.getContainerName() == null) {
                database.setContainerName(containers.containerName(database.getId()));
            }
            boolean adopted = adopt(database);
            if ("TERMINATING".equals(state)) {
                runDelete(database);
            } else if (!adopted) {
                // A create interrupted before its container existed lost only the admin password.
                fail(database, "PROVISIONING".equals(state) ? INTERRUPTED : CONTAINER_GONE, false);
            } else if ("STOPPING".equals(state)) {
                runStop(database);
            } else if ("STOPPED".equals(state) || "UNAVAILABLE".equals(state)) {
                finishWorkRequest(database, "FAILED", INTERRUPTED);
                saveIn(database);
            } else {
                boolean firstBoot = "PROVISIONING".equals(state);
                if (!firstBoot) {
                    database.setLifecycleState("STARTING");
                    touch(database);
                }
                launchStart(database, firstBoot);
            }
        }
    }

    private boolean adopt(StoredAutonomousDatabase database) {
        int hostPort = database.getHostPort();
        try {
            boolean adopted = containers.adopt(database);
            if (database.getHostPort() != hostPort) {
                saveIn(database);
            }
            return adopted;
        } catch (RuntimeException e) {
            LOG.warnv("Could not look up the container of Autonomous Database {0}: {1}", database.getId(),
                    e.getMessage());
            return false;
        }
    }

    private void removeQuietly(StoredAutonomousDatabase database) {
        try {
            containers.remove(database);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the container of Autonomous Database {0}: {1}", database.getId(),
                    e.getMessage());
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────────

    private Optional<StoredAutonomousDatabase> findIn(String tenancyId, String region, String id) {
        return TenancyAwareStorageBackend.getIn(databases,
                TenancyAwareStorageBackend.regionalPartition(tenancyId, region), id);
    }

    private void saveIn(StoredAutonomousDatabase database) {
        TenancyAwareStorageBackend.putIn(databases,
                TenancyAwareStorageBackend.regionalPartition(database.getTenancyId(), database.getRegion()),
                database.getId(), database);
    }

    /** Every database of every tenancy and region, for startup, shutdown and reset. */
    private List<StoredAutonomousDatabase> allDatabases() {
        return TenancyAwareStorageBackend.scanAll(databases);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * What a database reports for fields the request left out. Each value is an inference from
     * the Serverless defaults Oracle documents, not something the SDK pins.
     */
    private static Map<String, Object> defaults() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("characterSet", "AL32UTF8");
        attributes.put("ncharacterSet", "AL16UTF16");
        attributes.put("computeModel", "ECPU");
        attributes.put("computeCount", 2);
        attributes.put("dataStorageSizeInTBs", 1);
        attributes.put("dataStorageSizeInGBs", 1024);
        attributes.put("dbWorkload", "OLTP");
        attributes.put("dbVersion", "19c");
        attributes.put("licenseModel", "LICENSE_INCLUDED");
        attributes.put("infrastructureType", "CLOUD");
        attributes.put("isFreeTier", false);
        attributes.put("isDedicated", false);
        attributes.put("isDevTier", false);
        attributes.put("isPreview", false);
        attributes.put("isAutoScalingEnabled", false);
        attributes.put("isAutoScalingForStorageEnabled", false);
        attributes.put("isMtlsConnectionRequired", true);
        attributes.put("isAccessControlEnabled", false);
        attributes.put("isDataGuardEnabled", false);
        attributes.put("isLocalDataGuardEnabled", false);
        attributes.put("isRemoteDataGuardEnabled", false);
        attributes.put("isRefreshableClone", false);
        attributes.put("isBackupRetentionLocked", false);
        attributes.put("backupRetentionPeriodInDays", 60);
        attributes.put("openMode", "READ_WRITE");
        attributes.put("permissionLevel", "UNRESTRICTED");
        attributes.put("role", "PRIMARY");
        attributes.put("dataSafeStatus", "NOT_REGISTERED");
        attributes.put("operationsInsightsStatus", "NOT_ENABLED");
        attributes.put("databaseManagementStatus", "NOT_ENABLED");
        attributes.put("autonomousMaintenanceScheduleType", "REGULAR");
        attributes.put("freeformTags", Map.of());
        attributes.put("definedTags", Map.of());
        return attributes;
    }

    private static void validateEnums(Map<String, Object> attributes) {
        requireEnum(attributes, "computeModel", COMPUTE_MODELS);
        requireEnum(attributes, "dbWorkload", DB_WORKLOADS);
        requireEnum(attributes, "licenseModel", LICENSE_MODELS);
        requireEnum(attributes, "databaseEdition", DATABASE_EDITIONS);
        requireEnum(attributes, "autonomousMaintenanceScheduleType", MAINTENANCE_SCHEDULES);
        requireEnum(attributes, "openMode", OPEN_MODES);
        requireEnum(attributes, "permissionLevel", PERMISSION_LEVELS);
    }

    private static void requireEnum(Map<String, Object> attributes, String key, Set<String> allowed) {
        Object value = attributes.get(key);
        if (value != null) {
            DatabaseRequests.requireEnum(value.toString(), allowed, key);
        }
    }

    /**
     * Keeps the two storage fields consistent: a size sent in GB sets the TB figure (rounded up),
     * a size sent in TB sets the GB figure. Neither may be below 1.
     */
    private static void reconcileStorage(Map<String, Object> attributes, Map<String, Object> details) {
        Integer gbs = DatabaseRequests.positiveInteger(details, "dataStorageSizeInGBs");
        Integer tbs = DatabaseRequests.positiveInteger(details, "dataStorageSizeInTBs");
        if (gbs != null) {
            attributes.put("dataStorageSizeInGBs", gbs);
            if (tbs == null) {
                attributes.put("dataStorageSizeInTBs", (gbs + 1023) / 1024);
            }
        } else if (tbs != null) {
            attributes.put("dataStorageSizeInGBs", tbs * 1024);
        }
    }

    /** ECPU databases are sized by computeCount, OCPU ones by ocpuCount or cpuCoreCount. */
    /**
     * An OCPU database's computeCount follows the OCPU fields of the request. A request that sends
     * none keeps {@code unchanged}: 1 on create, the saved count on update, so a rename does not
     * resize the database.
     */
    private static void reconcileCompute(Map<String, Object> attributes, Map<String, Object> details,
                                         Object unchanged) {
        DatabaseRequests.positiveNumber(details, "computeCount");
        DatabaseRequests.positiveNumber(details, "ocpuCount");
        DatabaseRequests.positiveInteger(details, "cpuCoreCount");
        if ("OCPU".equals(attributes.get("computeModel")) && details.get("computeCount") == null) {
            Object ocpus = details.get("ocpuCount") != null ? details.get("ocpuCount") : details.get("cpuCoreCount");
            attributes.put("computeCount", ocpus != null ? ocpus : unchanged);
        }
    }

    /** In mock mode the connection strings follow dbName, and nothing listens on them. */
    private static void refreshConnectionStrings(StoredAutonomousDatabase database) {
        database.getAttributes().put("connectionStrings",
                AutonomousDatabaseWallet.connectionStrings(database.getDbName(), MOCK_HOST, MTLS_PORT));
    }

    /**
     * The tool URLs. They name the container's ORDS port, which floci-oci does not publish
     * (only the mTLS listener is), so nothing answers on them in either mode.
     */
    private static void refreshUrls(StoredAutonomousDatabase database) {
        Map<String, Object> urls = new LinkedHashMap<>();
        String ords = "https://" + MOCK_HOST + ":8443/ords/";
        urls.put("sqlDevWebUrl", ords + "sql-developer");
        urls.put("apexUrl", ords + "apex");
        urls.put("ordsUrl", ords);
        urls.put("databaseTransformsUrl", ords + "_/landing");
        database.getAttributes().put("connectionUrls", urls);
        database.getAttributes().put("serviceConsoleUrl", ords + "_/landing");
    }

    /**
     * Oracle's ADMIN password rule: 12 to 30 characters with an upper-case letter, a lower-case
     * letter and a digit, no double quote, and not containing "admin".
     */
    static void requireAdminPassword(String password) {
        boolean valid = password.length() >= 12 && password.length() <= 30
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.indexOf('"') < 0
                && !password.toLowerCase(Locale.ROOT).contains("admin");
        if (!valid) {
            throw OciException.invalidParameter("adminPassword must be 12 to 30 characters long, contain an"
                    + " upper-case letter, a lower-case letter and a number, and must not contain a double"
                    + " quote or the word admin");
        }
    }

    /** Oracle's wallet password rule: at least 8 characters, with a letter and a digit or special character. */
    static void requireWalletPassword(String password) {
        boolean valid = password.length() >= 8 && password.length() <= 60
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(c -> !Character.isLetter(c));
        if (!valid) {
            throw OciException.invalidParameter("The wallet password must be at least 8 characters long and"
                    + " contain a letter and a number or a special character");
        }
    }

    private static String requireDbName(String dbName) {
        if (!DB_NAME.matcher(dbName).matches()) {
            throw OciException.invalidParameter("dbName must start with a letter and contain only letters and"
                    + " numbers, at most 30 characters: " + dbName);
        }
        return dbName;
    }

    /** {@code DB<timestamp>}, with a counter when another database already took that name. */
    private String generatedDbName(Instant now) {
        String base = "DB" + NAME_STAMP.format(now);
        String candidate = base;
        for (int suffix = 2; isTaken(candidate, null); suffix++) {
            candidate = base + suffix;
        }
        return candidate;
    }

    /**
     * The CLI documents that "the database name must be unique in the tenancy". Oracle names are
     * case-insensitive, and a terminated database frees its name (inferred).
     */
    private void requireUniqueDbName(String dbName, String exceptId) {
        if (isTaken(dbName, exceptId)) {
            throw OciException.invalidParameter("An Autonomous Database named " + dbName
                    + " already exists in this tenancy; dbName must be unique in the tenancy");
        }
    }

    private boolean isTaken(String dbName, String exceptId) {
        return TenancyAwareStorageBackend.scanTenancy(databases, ociContext.tenancyId()).stream()
                .filter(database -> !database.getId().equals(exceptId))
                .filter(database -> !"TERMINATED".equals(database.getLifecycleState()))
                .anyMatch(database -> database.getDbName().equalsIgnoreCase(dbName));
    }

    private StoredAutonomousDatabase mutable(String autonomousDatabaseId, String ifMatch, String... states) {
        StoredAutonomousDatabase database = get(autonomousDatabaseId);
        if ("TERMINATED".equals(database.getLifecycleState())) {
            throw notFound(autonomousDatabaseId);
        }
        Etags.checkIfMatch(ifMatch, database.getEtag());
        if (!List.of(states).contains(database.getLifecycleState())) {
            // While an operation is under way the condition clears by itself: IncorrectState, which
            // SDKs retry. In a settled state it never does: Conflict, which they do not.
            String message = "Autonomous Database " + autonomousDatabaseId + " is "
                    + database.getLifecycleState() + "; the operation needs it " + String.join(" or ", states);
            throw TRANSIENT_STATES.contains(database.getLifecycleState())
                    ? new OciException("IncorrectState", message, 409) : OciException.conflict(message);
        }
        return database;
    }

    /** A new etag, and the database saved in its own partition, which a worker thread needs. */
    private void touch(StoredAutonomousDatabase database) {
        database.setEtag(Etags.newEtag());
        saveIn(database);
    }

    private String record(String operationType, StoredAutonomousDatabase database, String actionType) {
        return workRequests.succeeded(SERVICE, operationType, database.getCompartmentId(),
                resources(database, actionType));
    }

    private static List<StoredWorkRequest.Resource> resources(StoredAutonomousDatabase database, String actionType) {
        return List.of(WorkRequestService.resource(ENTITY_TYPE, actionType, database.getId(),
                BASE_PATH + database.getId()));
    }

    private static OciException notFound(String autonomousDatabaseId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: Autonomous Database " + autonomousDatabaseId);
    }
}
