package io.floci.oci.services.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.mysql.model.StoredMysqlDbSystem;
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

/**
 * MySQL HeatWave DB systems, API version {@code 20190415}, each served by a real MySQL container
 * unless the service runs in mock mode. Shapes follow oci-go-sdk/mysql; the fields Terraform reads
 * back and the states it waits on follow terraform-provider-oci/internal/service/mysql.
 *
 * <p>Create, start, restart, a version change and delete run on one worker thread; a poller
 * moves a starting system to ACTIVE once MySQL answers on TCP, or to FAILED when the container
 * exits or the ready timeout passes. Every outcome finishes the work request the CLI waits on.
 */
@ApplicationScoped
public class MysqlService implements Resettable, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(MysqlService.class);

    static final String WR_SERVICE = "mysql";
    static final String API_PREFIX = "/20190415";
    private static final String ENTITY_TYPE = "dbsystem";
    private static final String BASE_PATH = API_PREFIX + "/dbSystems/";
    private static final long READINESS_POLL_SECONDS = 2;
    static final String INTERRUPTED = "The operation was interrupted by a restart of floci-oci";
    static final String SUPERSEDED = "The operation was superseded by another operation on the DB system";

    static final Set<String> LIFECYCLE_STATES = Set.of("CREATING", "ACTIVE", "INACTIVE", "UPDATING", "DELETING",
            "DELETED", "FAILED");
    /**
     * The fields a FAILED system still takes: none reaches the server, and turning delete
     * protection off is the only way to delete a protected system whose container never started.
     */
    private static final Set<String> FAILED_UPDATABLE = Set.of("deletionPolicy", "displayName", "description",
            "freeformTags", "definedTags");
    private static final Set<String> TRANSIENT_STATES = Set.of("CREATING", "UPDATING", "DELETING");
    static final Set<String> SHUTDOWN_TYPES = Set.of("IMMEDIATE", "FAST", "SLOW");
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Set<String> SORT_FIELDS = Set.of("displayName", "timeCreated");
    private static final Set<String> FEATURE_STATES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> DATABASE_MODES = Set.of("READ_WRITE", "READ_ONLY");
    private static final Set<String> ACCESS_MODES = Set.of("UNRESTRICTED", "RESTRICTED");
    private static final Set<String> BACKUP_RETENTIONS = Set.of("DELETE", "RETAIN");
    private static final Set<String> FINAL_BACKUPS = Set.of("SKIP_FINAL_BACKUP", "REQUIRE_FINAL_BACKUP");
    private static final Set<String> CERTIFICATE_TYPES = Set.of("SYSTEM", "BYOC");

    static final int DEFAULT_PORT = 3306;
    static final int DEFAULT_PORT_X = 33060;
    static final int DEFAULT_STORAGE_GBS = 50;
    /** The most a DB system's storage may grow to, 128 TB, which floci-oci reports as its limit. */
    static final int STORAGE_LIMIT_GBS = 131072;
    private static final String DEFAULT_FAULT_DOMAIN = "FAULT-DOMAIN-1";
    private static final String DEFAULT_MAINTENANCE_WINDOW = "SUN 00:00";
    private static final String DEFAULT_BACKUP_WINDOW = "00:00";
    private static final int DEFAULT_BACKUP_RETENTION_DAYS = 7;
    private static final DateTimeFormatter NAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** A system whose container is starting: who owns it, when it began, and where it ends up. */
    record Pending(String tenancyId, String region, Instant since, String targetState) {
    }

    /** What CreateDbSystem returns: the body and the work request to poll. */
    public record Created(StoredMysqlDbSystem system, String workRequestId) {
    }

    private final StorageBackend<String, StoredMysqlDbSystem> systems;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final MysqlContainerManager containers;
    private final MysqlConfigurationsService configurations;
    private final OciContext ociContext;
    private final NetworkReferences networkReferences;
    private final Clock clock;
    private final Executor lifecycle;
    private final ExecutorService ownedLifecycle;
    private final ScheduledExecutorService readinessPoller;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Inject
    public MysqlService(StorageFactory storageFactory, EmulatorConfig config, ServiceRegistry serviceRegistry,
                        WorkRequestService workRequests, MysqlContainerManager containers,
                        MysqlConfigurationsService configurations, OciContext ociContext,
                        Instance<NetworkReferences> networkReferences) {
        this(storageFactory.create("mysql", "mysql-dbsystems.json",
                        new TypeReference<Map<String, StoredMysqlDbSystem>>() {}),
                config, serviceRegistry, workRequests, containers, configurations, ociContext,
                NetworkReferences.of(networkReferences), Clock.systemUTC(), null);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param containers null for a service that never touches Docker, whatever the mock flag says
     * @param lifecycle  runs the Docker work; null for the service's own single worker thread
     */
    MysqlService(StorageBackend<String, StoredMysqlDbSystem> systems, EmulatorConfig config,
                 ServiceRegistry serviceRegistry, WorkRequestService workRequests, MysqlContainerManager containers,
                 MysqlConfigurationsService configurations, OciContext ociContext,
                 NetworkReferences networkReferences, Clock clock, Executor lifecycle) {
        this.systems = systems;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.containers = containers;
        this.configurations = configurations;
        this.ociContext = ociContext;
        this.networkReferences = networkReferences;
        this.clock = clock;
        // No thread starts until the first task is submitted, which only real mode does.
        this.ownedLifecycle = lifecycle != null ? null : Executors.newSingleThreadExecutor(daemon("mysql-lifecycle"));
        this.lifecycle = lifecycle != null ? lifecycle : ownedLifecycle;
        this.readinessPoller = Executors.newSingleThreadScheduledExecutor(daemon("mysql-readiness"));
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("mysql")
                .enabled(config.services().mysql().enabled())
                .storageKey("mysql")
                .resourceClasses(MysqlController.class, MysqlCatalogController.class)
                .build());
        if (realMode() && config.services().mysql().enabled()) {
            readinessPoller.scheduleWithFixedDelay(this::pollReadinessSafely,
                    READINESS_POLL_SECONDS, READINESS_POLL_SECONDS, TimeUnit.SECONDS);
            restartPersistedSystems();
        }
    }

    @PreDestroy
    void shutdown() {
        readinessPoller.shutdownNow();
        if (ownedLifecycle != null) {
            ownedLifecycle.shutdownNow();
        }
        if (realMode()) {
            for (StoredMysqlDbSystem system : allSystems()) {
                if (system.getContainerName() != null) {
                    stopQuietly(system);
                }
            }
        }
    }

    /**
     * Removes every container and volume, then forgets every DB system. Holds the lock lifecycle
     * steps record their outcome under, so a step still running finds its system gone and removes
     * what it started.
     */
    @Override
    public synchronized void clear() {
        pending.clear();
        if (realMode()) {
            for (StoredMysqlDbSystem system : allSystems()) {
                if (system.getContainerName() != null) {
                    purgeQuietly(system);
                }
            }
        }
        systems.clear();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(systems, tenancyId, compartmentId, StoredMysqlDbSystem::getCompartmentId,
                StoredMysqlDbSystem::getLifecycleState, system -> "MySQL DB system " + system.getId()).toList();
    }

    // ── DB systems ─────────────────────────────────────────────────────────────

    public Created createDbSystem(Map<String, Object> details) {
        return configurations.attachingConfiguration(() -> create(details));
    }

    private Created create(Map<String, Object> details) {
        String compartmentId = MysqlRequests.requireString(details, "compartmentId");
        MysqlCatalog.Shape shape = MysqlCatalog.requireShape(MysqlRequests.requireString(details, "shapeName"));
        String subnetId = MysqlRequests.requireString(details, "subnetId");
        List<String> nsgIds = MysqlRequests.stringList(details, "nsgIds");
        networkReferences.requireSubnet(subnetId);
        networkReferences.requireNsgs(nsgIds);
        String version = MysqlCatalog.resolveVersion(MysqlRequests.string(details, "mysqlVersion"));
        boolean highlyAvailable = Boolean.TRUE.equals(MysqlRequests.bool(details, "isHighlyAvailable"));
        String configurationId = MysqlRequests.string(details, "configurationId");
        if (configurationId != null) {
            configurations.requireUsable(configurationId, shape.name());
        } else {
            configurationId = MysqlCatalog.defaultConfigurationId(
                    MysqlCatalog.defaultConfigurationName(shape.name(), highlyAvailable), ociContext.realm());
        }
        requireSource(MysqlRequests.map(details, "source"));
        String adminUsername = MysqlRequests.string(details, "adminUsername");
        String adminPassword = MysqlRequests.string(details, "adminPassword");
        if (realMode() && (adminUsername == null || adminPassword == null)) {
            throw OciException.missingParameter("Missing required parameter: "
                    + (adminUsername == null ? "adminUsername" : "adminPassword"));
        }

        Instant now = clock.instant();
        StoredMysqlDbSystem system = new StoredMysqlDbSystem();
        system.setId(Ocids.generate("mysqldbsystem", ociContext.realm(), ociContext.regionCode()));
        String displayName = MysqlRequests.string(details, "displayName");
        system.setDisplayName(displayName != null ? displayName : "mysqldbsystem" + NAME_STAMP.format(now));
        system.setDescription(MysqlRequests.string(details, "description"));
        system.setCompartmentId(compartmentId);
        system.setSubnetId(subnetId);
        system.setNsgIds(nsgIds);
        system.setHighlyAvailable(highlyAvailable);
        system.setAvailabilityDomain(MysqlRequests.string(details, "availabilityDomain"));
        String faultDomain = MysqlRequests.string(details, "faultDomain");
        system.setFaultDomain(faultDomain != null ? faultDomain : DEFAULT_FAULT_DOMAIN);
        system.setShapeName(shape.name());
        system.setMysqlVersion(version);
        system.setConfigurationId(configurationId);
        int storage = positive(details, "dataStorageSizeInGBs", DEFAULT_STORAGE_GBS);
        system.setDataStorageSizeInGBs(storage);
        system.setDataStorage(dataStorage(storage, MysqlRequests.map(details, "dataStorage"), null));
        system.setHostnameLabel(MysqlRequests.string(details, "hostnameLabel"));
        system.setIpAddress(MysqlRequests.string(details, "ipAddress"));
        system.setPort(positive(details, "port", DEFAULT_PORT));
        system.setPortX(positive(details, "portX", DEFAULT_PORT_X));
        system.setBackupPolicy(backupPolicy(MysqlRequests.map(details, "backupPolicy"), null));
        system.setMaintenance(maintenance(MysqlRequests.map(details, "maintenance"), null));
        system.setDeletionPolicy(deletionPolicy(MysqlRequests.map(details, "deletionPolicy"), null));
        system.setCrashRecovery(feature(details, "crashRecovery", "ENABLED"));
        system.setDatabaseManagement(feature(details, "databaseManagement", "DISABLED"));
        system.setDatabaseMode(choice(details, "databaseMode", DATABASE_MODES, "READ_WRITE"));
        system.setAccessMode(choice(details, "accessMode", ACCESS_MODES, "UNRESTRICTED"));
        system.setSecureConnections(secureConnections(MysqlRequests.map(details, "secureConnections"), null));
        system.setFreeformTags(MysqlRequests.stringMap(details, "freeformTags"));
        system.setDefinedTags(MysqlRequests.definedTags(details));
        system.setAdminUsername(adminUsername);
        system.setTimeCreated(now.toString());
        system.setTimeUpdated(now.toString());
        system.setEtag(Etags.newEtag());
        system.setTenancyId(ociContext.tenancyId());
        system.setRegion(ociContext.region());
        system.setContainerName(containers != null ? containers.containerName(system.getId()) : null);
        system.setVolumeName(containers != null ? containers.volumeName(system.getId()) : null);

        if (!realMode()) {
            system.setLifecycleState("ACTIVE");
            system.setEndpointHost("localhost");
            system.setEndpointPort(config.services().mysql().basePort());
            systems.put(system.getId(), system);
            String workRequestId = workRequests.succeeded(WR_SERVICE, "CREATE_DBSYSTEM", compartmentId,
                    resources(system, "CREATED"));
            return new Created(system, workRequestId);
        }
        system.setLifecycleState("CREATING");
        String workRequestId = workRequests.inProgress(WR_SERVICE, "CREATE_DBSYSTEM", compartmentId,
                resources(system, "CREATED"));
        system.setWorkRequestId(workRequestId);
        // Stored before the start is queued: the worker looks the system up by its partition.
        systems.put(system.getId(), system);
        launchStart(system, adminPassword, "ACTIVE");
        LOG.infov("createDbSystem {0} ({1}), MySQL {2}", system.getId(), system.getDisplayName(), version);
        return new Created(system, workRequestId);
    }

    /** A deleted DB system is still returned, as DELETED: Terraform polls it to see the delete finish. */
    public StoredMysqlDbSystem getDbSystem(String dbSystemId) {
        return systems.get(dbSystemId).orElseThrow(() -> notFound(dbSystemId));
    }

    public List<StoredMysqlDbSystem> listDbSystems(String compartmentId, Boolean heatWaveAttached, String dbSystemId,
                                                   String displayName, String lifecycleState, String configurationId,
                                                   List<String> databaseManagement, String sortBy, String sortOrder) {
        MysqlRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        MysqlRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        MysqlRequests.requireEnum(sortBy, SORT_FIELDS, "sortBy");
        for (String state : databaseManagement) {
            MysqlRequests.requireEnum(state, FEATURE_STATES, "databaseManagement");
        }
        // timeCreated sorts newest first by default, displayName ascending, as OCI lists do.
        boolean byName = "displayName".equals(sortBy);
        Comparator<StoredMysqlDbSystem> order = byName
                ? Comparator.comparing(StoredMysqlDbSystem::getDisplayName)
                : Comparator.comparing(StoredMysqlDbSystem::getTimeCreated);
        order = order.thenComparing(StoredMysqlDbSystem::getId);
        boolean descending = sortOrder != null ? "DESC".equals(sortOrder) : !byName;
        if (descending) {
            order = order.reversed();
        }
        return systems.scan(k -> true).stream()
                .filter(s -> compartmentId.equals(s.getCompartmentId()))
                // No DB system has a HeatWave cluster: asking for those finds none.
                .filter(s -> !Boolean.TRUE.equals(heatWaveAttached))
                .filter(s -> dbSystemId == null || dbSystemId.equals(s.getId()))
                .filter(s -> displayName == null || displayName.equals(s.getDisplayName()))
                .filter(s -> lifecycleState == null || lifecycleState.equals(s.getLifecycleState()))
                .filter(s -> configurationId == null || configurationId.equals(s.getConfigurationId()))
                .filter(s -> databaseManagement.isEmpty() || databaseManagement.contains(s.getDatabaseManagement()))
                .sorted(order)
                .toList();
    }

    /**
     * Applies the editable fields. Only a new {@code mysqlVersion} reaches the running server, by
     * restarting it on the new image; every other field is a management-plane fact, so its work
     * request is finished at once. A new admin password is not applied to the server.
     */
    public String updateDbSystem(String dbSystemId, Map<String, Object> details, String ifMatch) {
        return configurations.attachingConfiguration(() -> update(dbSystemId, details, ifMatch));
    }

    private synchronized String update(String dbSystemId, Map<String, Object> details, String ifMatch) {
        StoredMysqlDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE", "INACTIVE", "FAILED");
        if ("FAILED".equals(system.getLifecycleState()) && !FAILED_UPDATABLE.containsAll(details.keySet())) {
            throw OciException.conflict("DB system " + dbSystemId
                    + " is FAILED: only " + String.join(", ", FAILED_UPDATABLE.stream().sorted().toList())
                    + " can be changed");
        }
        // Everything that can be rejected is checked before anything changes: the store hands out
        // the stored object itself, so a half-applied update would stick.
        String subnetId = MysqlRequests.string(details, "subnetId");
        List<String> nsgIds = MysqlRequests.stringList(details, "nsgIds");
        networkReferences.requireSubnet(subnetId);
        networkReferences.requireNsgs(nsgIds);
        String shapeName = MysqlRequests.string(details, "shapeName") != null
                ? MysqlCatalog.requireShape(MysqlRequests.string(details, "shapeName")).name() : system.getShapeName();
        String version = MysqlRequests.string(details, "mysqlVersion") != null
                ? MysqlCatalog.resolveVersion(MysqlRequests.string(details, "mysqlVersion")) : null;
        String configurationId = MysqlRequests.string(details, "configurationId");
        if (configurationId != null) {
            configurations.requireUsable(configurationId, shapeName);
        } else if (!shapeName.equals(system.getShapeName())) {
            throw OciException.invalidParameter("A new shapeName needs a configurationId for that shape");
        }
        Integer storage = MysqlRequests.integer(details, "dataStorageSizeInGBs");
        if (storage != null && storage < system.getDataStorageSizeInGBs()) {
            throw OciException.invalidParameter("dataStorageSizeInGBs cannot shrink from "
                    + system.getDataStorageSizeInGBs() + " to " + storage);
        }
        int newStorage = storage != null ? storage : system.getDataStorageSizeInGBs();
        Map<String, Object> dataStorage = dataStorage(newStorage, MysqlRequests.map(details, "dataStorage"),
                system.getDataStorage());
        Map<String, Object> backupPolicy = MysqlRequests.map(details, "backupPolicy") != null
                ? backupPolicy(MysqlRequests.map(details, "backupPolicy"), system.getBackupPolicy()) : null;
        Map<String, Object> maintenance = MysqlRequests.map(details, "maintenance") != null
                ? maintenance(MysqlRequests.map(details, "maintenance"), system.getMaintenance()) : null;
        Map<String, Object> deletionPolicy = MysqlRequests.map(details, "deletionPolicy") != null
                ? deletionPolicy(MysqlRequests.map(details, "deletionPolicy"), system.getDeletionPolicy()) : null;
        Map<String, Object> secureConnections = MysqlRequests.map(details, "secureConnections") != null
                ? secureConnections(MysqlRequests.map(details, "secureConnections"), system.getSecureConnections())
                : null;
        String crashRecovery = feature(details, "crashRecovery", null);
        String databaseManagement = feature(details, "databaseManagement", null);
        String databaseMode = choice(details, "databaseMode", DATABASE_MODES, null);
        String accessMode = choice(details, "accessMode", ACCESS_MODES, null);
        Integer port = MysqlRequests.integer(details, "port") != null ? positive(details, "port", DEFAULT_PORT) : null;
        Integer portX = MysqlRequests.integer(details, "portX") != null
                ? positive(details, "portX", DEFAULT_PORT_X) : null;
        Boolean highlyAvailable = MysqlRequests.bool(details, "isHighlyAvailable");

        if (MysqlRequests.string(details, "displayName") != null) {
            system.setDisplayName(MysqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            system.setDescription(MysqlRequests.string(details, "description"));
        }
        if (subnetId != null) {
            system.setSubnetId(subnetId);
        }
        if (nsgIds != null) {
            system.setNsgIds(nsgIds);
        }
        if (highlyAvailable != null) {
            system.setHighlyAvailable(highlyAvailable);
        }
        if (MysqlRequests.string(details, "availabilityDomain") != null) {
            system.setAvailabilityDomain(MysqlRequests.string(details, "availabilityDomain"));
        }
        if (MysqlRequests.string(details, "faultDomain") != null) {
            system.setFaultDomain(MysqlRequests.string(details, "faultDomain"));
        }
        system.setShapeName(shapeName);
        if (configurationId != null) {
            system.setConfigurationId(configurationId);
        }
        if (MysqlRequests.string(details, "adminUsername") != null) {
            system.setAdminUsername(MysqlRequests.string(details, "adminUsername"));
        }
        system.setDataStorageSizeInGBs(newStorage);
        system.setDataStorage(dataStorage);
        if (MysqlRequests.string(details, "hostnameLabel") != null) {
            system.setHostnameLabel(MysqlRequests.string(details, "hostnameLabel"));
        }
        if (MysqlRequests.string(details, "ipAddress") != null) {
            system.setIpAddress(MysqlRequests.string(details, "ipAddress"));
        }
        if (port != null) {
            system.setPort(port);
        }
        if (portX != null) {
            system.setPortX(portX);
        }
        if (backupPolicy != null) {
            system.setBackupPolicy(backupPolicy);
        }
        if (maintenance != null) {
            system.setMaintenance(maintenance);
        }
        if (deletionPolicy != null) {
            system.setDeletionPolicy(deletionPolicy);
        }
        if (secureConnections != null) {
            system.setSecureConnections(secureConnections);
        }
        if (crashRecovery != null) {
            system.setCrashRecovery(crashRecovery);
        }
        if (databaseManagement != null) {
            system.setDatabaseManagement(databaseManagement);
        }
        if (databaseMode != null) {
            system.setDatabaseMode(databaseMode);
        }
        if (accessMode != null) {
            system.setAccessMode(accessMode);
        }
        if (MysqlRequests.stringMap(details, "freeformTags") != null) {
            system.setFreeformTags(MysqlRequests.stringMap(details, "freeformTags"));
        }
        if (MysqlRequests.definedTags(details) != null) {
            system.setDefinedTags(MysqlRequests.definedTags(details));
        }
        boolean newVersion = version != null && !version.equals(system.getMysqlVersion());
        if (version != null) {
            system.setMysqlVersion(version);
        }
        if (newVersion && realMode() && "ACTIVE".equals(system.getLifecycleState())) {
            String workRequestId = begin(system, "UPDATING", "UPDATE_DBSYSTEM", "UPDATED");
            launchStart(system, null, "ACTIVE");
            return workRequestId;
        }
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_DBSYSTEM", system.getCompartmentId(),
                resources(system, "UPDATED"));
    }

    /** Whether a DB system that is not deleted uses the configuration, which then cannot be deleted. */
    public boolean usesConfiguration(String configurationId) {
        return systems.scan(k -> true).stream()
                .filter(system -> !"DELETED".equals(system.getLifecycleState()))
                .anyMatch(system -> configurationId.equals(system.getConfigurationId()));
    }

    public synchronized String deleteDbSystem(String dbSystemId, String ifMatch) {
        StoredMysqlDbSystem system = getDbSystem(dbSystemId);
        Etags.checkIfMatch(ifMatch, system.getEtag());
        if ("DELETED".equals(system.getLifecycleState()) || "DELETING".equals(system.getLifecycleState())) {
            throw incorrectState(system, "deleted");
        }
        if (Boolean.TRUE.equals(system.getDeletionPolicy().get("isDeleteProtected"))) {
            throw OciException.conflict("DB system " + dbSystemId
                    + " is delete protected; turn deletionPolicy.isDeleteProtected off first");
        }
        // An operation still under way is abandoned: its work request ends CANCELED, and its step
        // is skipped, or its outcome dropped, because the system has moved on to another work
        // request. The lock keeps a step from recording its outcome in between.
        pending.remove(dbSystemId);
        finishWorkRequest(system, "CANCELED", null);
        if (!realMode()) {
            markDeleted(system);
            systems.put(system.getId(), system);
            return workRequests.succeeded(WR_SERVICE, "DELETE_DBSYSTEM", system.getCompartmentId(),
                    resources(system, "DELETED"));
        }
        String workRequestId = begin(system, "DELETING", "DELETE_DBSYSTEM", "DELETED");
        runDelete(system);
        return workRequestId;
    }

    /** Queues the removal of the container; the system then stays readable as DELETED. */
    private void runDelete(StoredMysqlDbSystem system) {
        runAsync(system, current -> {
            containers.remove(current);
            return () -> {
                markDeleted(current);
                saveIn(current);
                finishWorkRequest(current, "SUCCEEDED", null);
                LOG.infov("deleteDbSystem {0}", current.getId());
            };
        });
    }

    // ── Runtime actions ────────────────────────────────────────────────────────
    //
    // Every mutation holds the service lock from its state check until its step is queued, so a
    // second one sees the system UPDATING or DELETING and is refused with IncorrectState.

    public synchronized String startDbSystem(String dbSystemId, String ifMatch) {
        StoredMysqlDbSystem system = mutable(dbSystemId, ifMatch, "INACTIVE");
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE", "START_DBSYSTEM");
        }
        String workRequestId = begin(system, "UPDATING", "START_DBSYSTEM", "UPDATED");
        launchStart(system, null, "ACTIVE");
        return workRequestId;
    }

    public synchronized String stopDbSystem(String dbSystemId, Map<String, Object> details, String ifMatch) {
        requireShutdownType(details);
        StoredMysqlDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        if (!realMode()) {
            return finishAtOnce(system, "INACTIVE", "STOP_DBSYSTEM");
        }
        String workRequestId = begin(system, "UPDATING", "STOP_DBSYSTEM", "UPDATED");
        runAsync(system, current -> {
            containers.stop(current);
            return () -> {
                current.setLifecycleState("INACTIVE");
                touch(current);
                saveIn(current);
                finishWorkRequest(current, "SUCCEEDED", null);
            };
        });
        return workRequestId;
    }

    public synchronized String restartDbSystem(String dbSystemId, Map<String, Object> details, String ifMatch) {
        requireShutdownType(details);
        StoredMysqlDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE", "RESTART_DBSYSTEM");
        }
        String workRequestId = begin(system, "UPDATING", "RESTART_DBSYSTEM", "UPDATED");
        launchStart(system, null, "ACTIVE");
        return workRequestId;
    }

    private static void requireShutdownType(Map<String, Object> details) {
        MysqlRequests.requireEnum(MysqlRequests.requireString(details, "shutdownType"), SHUTDOWN_TYPES,
                "shutdownType");
    }

    // ── Lifecycle engine ───────────────────────────────────────────────────────

    boolean realMode() {
        return containers != null && !config.services().mysql().mock();
    }

    /**
     * Moves the system to {@code state} and opens the work request the operation is tracked by.
     * Called under the service lock. A work request the system still carries is failed first, so
     * replacing it never leaves it IN_PROGRESS.
     */
    private String begin(StoredMysqlDbSystem system, String state, String operationType, String actionType) {
        finishWorkRequest(system, "FAILED", SUPERSEDED);
        system.setLifecycleState(state);
        system.setLifecycleDetails(null);
        touch(system);
        String workRequestId = workRequests.inProgress(WR_SERVICE, operationType, system.getCompartmentId(),
                resources(system, actionType));
        system.setWorkRequestId(workRequestId);
        systems.put(system.getId(), system);
        return workRequestId;
    }

    /** Mock mode: the transition and its work request complete on the spot. */
    private String finishAtOnce(StoredMysqlDbSystem system, String state, String operationType) {
        system.setLifecycleState(state);
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, operationType, system.getCompartmentId(),
                resources(system, "UPDATED"));
    }

    /** The Docker work of an operation, returning what to record once it is done. */
    private interface LifecycleStep {
        Runnable run(StoredMysqlDbSystem current);
    }

    /**
     * Runs {@code step} on the lifecycle worker against the system as stored at that moment,
     * found in its own partition since the worker has no request scope. The step is skipped when
     * the system has gone, or has moved on to another operation, which shows as a different work
     * request. Ownership is checked again before the outcome is recorded, under the lock every
     * mutation and reset take: an outcome that arrives too late is dropped. A skipped or dropped
     * step still finishes its own work request, so no caller is left polling IN_PROGRESS.
     */
    private void runAsync(StoredMysqlDbSystem system, LifecycleStep step) {
        String tenancyId = system.getTenancyId();
        String region = system.getRegion();
        String id = system.getId();
        String workRequestId = system.getWorkRequestId();
        lifecycle.execute(() -> {
            Optional<StoredMysqlDbSystem> current;
            synchronized (this) {
                current = owned(tenancyId, region, id, workRequestId);
                if (current.isEmpty()) {
                    LOG.debugv("DB system {0} has moved on; skipping its queued lifecycle step", id);
                    finishAbandoned(tenancyId, region, id, workRequestId);
                    return;
                }
            }
            Runnable outcome;
            try {
                outcome = step.run(current.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Lifecycle step failed for DB system {0}", id);
                outcome = () -> fail(current.get(), e.getMessage());
            }
            synchronized (this) {
                if (owned(tenancyId, region, id, workRequestId).isPresent()) {
                    outcome.run();
                } else if (findIn(tenancyId, region, id).isEmpty()) {
                    // A reset forgot the system while its step ran: remove what the step started.
                    LOG.debugv("DB system {0} was reset during its lifecycle step", id);
                    purgeQuietly(current.get());
                } else {
                    LOG.debugv("DB system {0} moved on during its lifecycle step", id);
                    finishAbandoned(tenancyId, region, id, workRequestId);
                }
            }
        });
    }

    /**
     * Fails the work request of a step whose system moved on without finishing it. Whoever moved
     * the system on normally finished it already (a delete cancels it), and finishing a finished
     * work request changes nothing. A system that is gone was reset, along with its work requests.
     */
    private void finishAbandoned(String tenancyId, String region, String id, String workRequestId) {
        if (workRequestId == null || findIn(tenancyId, region, id).isEmpty()) {
            return;
        }
        workRequests.finish(tenancyId, region, workRequestId, "FAILED",
                List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", SUPERSEDED)));
    }

    /** The system, while {@code workRequestId} is still the operation it is running. */
    private Optional<StoredMysqlDbSystem> owned(String tenancyId, String region, String id, String workRequestId) {
        return findIn(tenancyId, region, id)
                .filter(system -> Objects.equals(workRequestId, system.getWorkRequestId()));
    }

    /**
     * Queues a (re)start of the container; the poller takes over once it is running. A null
     * password means the data volume is expected to exist already.
     */
    private void launchStart(StoredMysqlDbSystem system, String adminPassword, String targetState) {
        runAsync(system, current -> {
            try {
                containers.stop(current);
                containers.start(current, adminPassword);
            } catch (RuntimeException e) {
                return () -> fail(current, "The MySQL container could not be started: " + e.getMessage());
            }
            return () -> {
                saveIn(current);
                pending.put(current.getId(), new Pending(current.getTenancyId(), current.getRegion(),
                        clock.instant(), targetState));
            };
        });
    }

    private void pollReadinessSafely() {
        try {
            pollReadiness();
        } catch (RuntimeException e) {
            LOG.warnv(e, "MySQL readiness poll failed");
        }
    }

    /**
     * One pass over the starting systems: the target state once MySQL answers on TCP, FAILED when
     * the container has exited or the ready timeout has passed.
     */
    void pollReadiness() {
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            String id = entry.getKey();
            Pending waiting = entry.getValue();
            Optional<StoredMysqlDbSystem> found = findIn(waiting.tenancyId(), waiting.region(), id);
            if (found.isEmpty()) {
                pending.remove(id);
                continue;
            }
            try {
                pollOne(waiting, found.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "MySQL readiness poll failed for DB system {0}", id);
            }
        }
    }

    private void pollOne(Pending waiting, StoredMysqlDbSystem system) {
        int timeout = config.services().mysql().readyTimeoutSeconds();
        if (probeReady(system)) {
            settle(system, () -> {
                EndpointInfo endpoint = containers.clientEndpoint(system);
                system.setEndpointHost(endpoint.host());
                system.setEndpointPort(endpoint.port());
                system.setLifecycleState(waiting.targetState());
                system.setLifecycleDetails(null);
                touch(system);
                saveIn(system);
                finishWorkRequest(system, "SUCCEEDED", null);
                LOG.infov("DB system {0} is {1} at {2}", system.getId(), waiting.targetState(), endpoint);
            });
        } else if (!containers.isRunning(system)) {
            settle(system, () -> fail(system, "The MySQL container exited before it accepted connections"));
        } else if (clock.instant().isAfter(waiting.since().plusSeconds(timeout))) {
            settle(system, () -> fail(system, "MySQL did not accept connections after " + timeout + " seconds"));
        }
    }

    /** A probe that throws means not ready yet; the checks after it notice an exit or the timeout. */
    private boolean probeReady(StoredMysqlDbSystem system) {
        try {
            return containers.probeReady(system);
        } catch (RuntimeException e) {
            LOG.debugv("Could not probe DB system {0}: {1}", system.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Records an outcome only while the system is still pending, atomically with dropping it from
     * {@link #pending}: a delete or reset that removed it during the probe wins. Holds the service
     * lock, so no mutation sees the new state before the outcome has finished its work request.
     */
    private synchronized void settle(StoredMysqlDbSystem system, Runnable outcome) {
        AtomicBoolean recorded = new AtomicBoolean();
        pending.computeIfPresent(system.getId(), (id, waiting) -> {
            outcome.run();
            recorded.set(true);
            return null;
        });
        if (!recorded.get()) {
            LOG.debugv("DB system {0} left pending before its start was settled", system.getId());
        }
    }

    /** MySQL has no NEEDS_ATTENTION state: any failed operation leaves the system FAILED. */
    private void fail(StoredMysqlDbSystem system, String message) {
        stopQuietly(system);
        system.setLifecycleState("FAILED");
        system.setLifecycleDetails(message);
        touch(system);
        saveIn(system);
        finishWorkRequest(system, "FAILED", message);
        LOG.warnv("DB system {0} is FAILED: {1}", system.getId(), message);
    }

    private void finishWorkRequest(StoredMysqlDbSystem system, String status, String error) {
        if (system.getWorkRequestId() == null) {
            return;
        }
        List<StoredWorkRequest.ErrorEntry> errors = error == null ? List.of()
                : List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", error));
        workRequests.finish(system.getTenancyId(), system.getRegion(), system.getWorkRequestId(), status, errors);
        system.setWorkRequestId(null);
    }

    /**
     * After a restart of the emulator, settles the operations the restart interrupted and brings
     * back the container of every system that was up. A delete is finished. A create fails, since
     * the admin password it carried is gone. Any other operation fails its work request, and the
     * system comes back ACTIVE as it was.
     */
    void restartPersistedSystems() {
        for (StoredMysqlDbSystem system : allSystems()) {
            if (system.getWorkRequestId() != null) {
                switch (system.getLifecycleState()) {
                    case "DELETING" -> {
                        runDelete(system);
                        continue;
                    }
                    case "CREATING" -> {
                        fail(system, INTERRUPTED);
                        continue;
                    }
                    default -> finishWorkRequest(system, "FAILED", INTERRUPTED);
                }
            }
            if (Set.of("ACTIVE", "UPDATING").contains(system.getLifecycleState())) {
                system.setLifecycleState("UPDATING");
                saveIn(system);
                launchStart(system, null, "ACTIVE");
            }
        }
    }

    private void stopQuietly(StoredMysqlDbSystem system) {
        try {
            containers.stop(system);
        } catch (RuntimeException e) {
            LOG.warnv("Could not stop the sidecar of DB system {0}: {1}", system.getId(), e.getMessage());
        }
    }

    private void purgeQuietly(StoredMysqlDbSystem system) {
        try {
            containers.purge(system);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the sidecar of DB system {0}: {1}", system.getId(), e.getMessage());
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────────

    private static String partition(String tenancyId, String region) {
        return TenancyAwareStorageBackend.regionalPartition(tenancyId, region);
    }

    private Optional<StoredMysqlDbSystem> findIn(String tenancyId, String region, String id) {
        return TenancyAwareStorageBackend.getIn(systems, partition(tenancyId, region), id);
    }

    private void saveIn(StoredMysqlDbSystem system) {
        TenancyAwareStorageBackend.putIn(systems, partition(system.getTenancyId(), system.getRegion()),
                system.getId(), system);
    }

    /** Every DB system of every tenancy and region, for startup, shutdown and reset. */
    private List<StoredMysqlDbSystem> allSystems() {
        return TenancyAwareStorageBackend.scanAll(systems);
    }

    // ── Request details ────────────────────────────────────────────────────────

    /** Only a fresh DB system is emulated: a source other than NONE is rejected. */
    private static void requireSource(Map<String, Object> source) {
        if (source == null) {
            return;
        }
        String sourceType = MysqlRequests.requireString(source, "sourceType");
        if (!"NONE".equals(sourceType)) {
            throw OciException.invalidParameter("Unsupported source.sourceType: " + sourceType
                    + "; floci-oci only creates empty DB systems");
        }
    }

    private static Map<String, Object> dataStorage(int sizeInGBs, Map<String, Object> requested,
                                                   Map<String, Object> current) {
        Map<String, Object> storage = new LinkedHashMap<>();
        Boolean autoExpand = requested != null ? MysqlRequests.bool(requested, "isAutoExpandStorageEnabled") : null;
        Integer maxSize = requested != null ? MysqlRequests.integer(requested, "maxStorageSizeInGBs") : null;
        storage.put("isAutoExpandStorageEnabled", autoExpand != null ? autoExpand
                : current != null ? current.get("isAutoExpandStorageEnabled") : Boolean.FALSE);
        Object max = maxSize != null ? maxSize : current != null ? current.get("maxStorageSizeInGBs") : null;
        if (max != null) {
            storage.put("maxStorageSizeInGBs", max);
        }
        storage.put("allocatedStorageSizeInGBs", sizeInGBs);
        storage.put("dataStorageSizeInGBs", sizeInGBs);
        storage.put("dataStorageSizeLimitInGBs", STORAGE_LIMIT_GBS);
        return storage;
    }

    private static Map<String, Object> backupPolicy(Map<String, Object> requested, Map<String, Object> current) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if (current != null) {
            policy.putAll(current);
        } else {
            policy.put("isEnabled", true);
            policy.put("windowStartTime", DEFAULT_BACKUP_WINDOW);
            policy.put("retentionInDays", DEFAULT_BACKUP_RETENTION_DAYS);
            policy.put("pitrPolicy", Map.of("isEnabled", false));
            policy.put("copyPolicies", List.of());
            policy.put("freeformTags", Map.of());
            policy.put("definedTags", Map.of());
        }
        if (requested == null) {
            return policy;
        }
        Boolean enabled = MysqlRequests.bool(requested, "isEnabled");
        if (enabled != null) {
            policy.put("isEnabled", enabled);
        }
        if (MysqlRequests.string(requested, "windowStartTime") != null) {
            policy.put("windowStartTime", MysqlRequests.string(requested, "windowStartTime"));
        }
        Integer retention = MysqlRequests.integer(requested, "retentionInDays");
        if (retention != null) {
            if (retention < 1) {
                throw OciException.invalidParameter("backupPolicy.retentionInDays must be at least 1");
            }
            policy.put("retentionInDays", retention);
        }
        Map<String, Object> pitr = MysqlRequests.map(requested, "pitrPolicy");
        if (pitr != null) {
            policy.put("pitrPolicy", Map.of("isEnabled", Boolean.TRUE.equals(MysqlRequests.bool(pitr, "isEnabled"))));
        }
        if (requested.get("copyPolicies") instanceof List<?> copies) {
            policy.put("copyPolicies", copies);
        }
        if (MysqlRequests.stringMap(requested, "freeformTags") != null) {
            policy.put("freeformTags", MysqlRequests.stringMap(requested, "freeformTags"));
        }
        if (MysqlRequests.definedTags(requested) != null) {
            policy.put("definedTags", MysqlRequests.definedTags(requested));
        }
        return policy;
    }

    /** The maintenance window, kept exactly as sent: Terraform compares it as a plain string. */
    private static Map<String, Object> maintenance(Map<String, Object> requested, Map<String, Object> current) {
        Map<String, Object> maintenance = new LinkedHashMap<>();
        if (current != null) {
            maintenance.putAll(current);
        } else {
            maintenance.put("windowStartTime", DEFAULT_MAINTENANCE_WINDOW);
        }
        if (requested != null) {
            String window = current == null
                    ? MysqlRequests.requireString(requested, "windowStartTime")
                    : MysqlRequests.string(requested, "windowStartTime");
            if (window != null) {
                maintenance.put("windowStartTime", window);
            }
        }
        return maintenance;
    }

    private static Map<String, Object> deletionPolicy(Map<String, Object> requested, Map<String, Object> current) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if (current != null) {
            policy.putAll(current);
        } else {
            policy.put("automaticBackupRetention", "DELETE");
            policy.put("finalBackup", "SKIP_FINAL_BACKUP");
            policy.put("isDeleteProtected", false);
        }
        if (requested == null) {
            return policy;
        }
        String retention = MysqlRequests.string(requested, "automaticBackupRetention");
        MysqlRequests.requireEnum(retention, BACKUP_RETENTIONS, "deletionPolicy.automaticBackupRetention");
        if (retention != null) {
            policy.put("automaticBackupRetention", retention);
        }
        String finalBackup = MysqlRequests.string(requested, "finalBackup");
        MysqlRequests.requireEnum(finalBackup, FINAL_BACKUPS, "deletionPolicy.finalBackup");
        if (finalBackup != null) {
            policy.put("finalBackup", finalBackup);
        }
        Boolean protectedFromDelete = MysqlRequests.bool(requested, "isDeleteProtected");
        if (protectedFromDelete != null) {
            policy.put("isDeleteProtected", protectedFromDelete);
        }
        return policy;
    }

    private static Map<String, Object> secureConnections(Map<String, Object> requested, Map<String, Object> current) {
        if (requested == null) {
            return current != null ? current : Map.of("certificateGenerationType", "SYSTEM");
        }
        String type = MysqlRequests.requireString(requested, "certificateGenerationType");
        MysqlRequests.requireEnum(type, CERTIFICATE_TYPES, "secureConnections.certificateGenerationType");
        Map<String, Object> connections = new LinkedHashMap<>();
        connections.put("certificateGenerationType", type);
        if (MysqlRequests.string(requested, "certificateId") != null) {
            connections.put("certificateId", MysqlRequests.string(requested, "certificateId"));
        }
        return connections;
    }

    private static String feature(Map<String, Object> details, String key, String fallback) {
        return choice(details, key, FEATURE_STATES, fallback);
    }

    private static String choice(Map<String, Object> details, String key, Set<String> allowed, String fallback) {
        String value = MysqlRequests.string(details, key);
        MysqlRequests.requireEnum(value, allowed, key);
        return value != null ? value : fallback;
    }

    private static int positive(Map<String, Object> details, String key, int fallback) {
        Integer value = MysqlRequests.integer(details, key);
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw OciException.invalidParameter(key + " must be at least 1");
        }
        return value;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The system, when it may be changed: its etag matches and it is in one of {@code states}. */
    private StoredMysqlDbSystem mutable(String dbSystemId, String ifMatch, String... states) {
        StoredMysqlDbSystem system = getDbSystem(dbSystemId);
        Etags.checkIfMatch(ifMatch, system.getEtag());
        if ("DELETED".equals(system.getLifecycleState())) {
            throw notFound(dbSystemId);
        }
        if (!List.of(states).contains(system.getLifecycleState())) {
            throw incorrectState(system, "changed");
        }
        return system;
    }

    private void markDeleted(StoredMysqlDbSystem system) {
        system.setLifecycleState("DELETED");
        system.setLifecycleDetails(null);
        system.setEndpointHost(null);
        touch(system);
    }

    private void touch(StoredMysqlDbSystem system) {
        system.setTimeUpdated(clock.instant().toString());
        system.setEtag(Etags.newEtag());
    }

    private List<StoredWorkRequest.Resource> resources(StoredMysqlDbSystem system, String actionType) {
        return List.of(WorkRequestService.resource(ENTITY_TYPE, actionType, system.getId(),
                BASE_PATH + system.getId()));
    }

    private static OciException notFound(String dbSystemId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: DB system " + dbSystemId);
    }

    /**
     * A request the system's lifecycle state refuses. While the state is one an operation passes
     * through, the refusal is {@code 409 IncorrectState}, which OCI SDKs retry; in a settled state
     * a retry cannot succeed, so it is {@code 409 Conflict}, which they do not.
     */
    private static OciException incorrectState(StoredMysqlDbSystem system, String action) {
        String message = "DB system " + system.getId() + " is " + system.getLifecycleState()
                + " and cannot be " + action;
        return TRANSIENT_STATES.contains(system.getLifecycleState())
                ? new OciException("IncorrectState", message, 409) : OciException.conflict(message);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
