package io.floci.oci.services.psql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
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
import io.floci.oci.core.tls.CertificateGenerator;
import io.floci.oci.core.tls.CertificateGenerator.GeneratedCertificate;
import io.floci.oci.core.tls.KeyAlgorithm;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.psql.model.StoredDbInstance;
import io.floci.oci.services.psql.model.StoredBackup;
import io.floci.oci.services.psql.model.StoredConfiguration;
import io.floci.oci.services.psql.model.StoredDbSystem;
import io.floci.oci.services.vault.VaultSecretsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OCI Database with PostgreSQL: the DB system management plane, and the lifecycle of the
 * PostgreSQL container behind each DB system.
 *
 * <p>Nothing here waits for a container on a request thread. A mutation records the new state
 * and an {@code IN_PROGRESS} work request and returns; one worker thread does the Docker work
 * in order, and a poller moves a starting system to its target state once Postgres answers.
 * With {@code mock} on, the same transitions complete at once and no container exists.
 */
@ApplicationScoped
public class PsqlService implements Resettable, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(PsqlService.class);

    static final String WR_SERVICE = "psql";
    /** Terraform finds the DB system in a work request by an entity type containing "dbsystem". */
    static final Set<String> APPLY_CONFIG = Set.of("RESTART", "RELOAD");
    static final String ENTITY_TYPE = "postgresqlDbSystem";

    private static final String BASE_PATH = "/20220915/dbSystems/";
    private static final long READINESS_POLL_SECONDS = 2;
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Set<String> SORT_FIELDS = Set.of("timeCreated", "displayName");
    private static final Set<String> RESTART_TYPES = Set.of("NORMAL", "NODE_REBOOT");
    private static final Set<String> LIFECYCLE_STATES = Set.of("CREATING", "UPDATING", "ACTIVE",
            "INACTIVE", "DELETING", "DELETED", "FAILED", "NEEDS_ATTENTION");
    private static final long DEFAULT_IOPS = 75000L;
    private static final String DEFAULT_MAINTENANCE_WINDOW = "SUN 00:00";
    static final String INTERRUPTED = "The operation was interrupted by a restart of floci-oci";

    /** A system whose container is starting: who owns it, when it began, and where it ends up. */
    record Pending(String tenancyId, String region, Instant since, String targetState, String failureState) {
    }

    /** What CreateDbSystem returns: the body and the work request to poll. */
    public record Created(StoredDbSystem system, String workRequestId) {
    }

    private final StorageBackend<String, StoredDbSystem> systems;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final PsqlContainerManager containers;
    private final OciContext ociContext;
    private final CertificateGenerator certificates;
    private final VaultSecretsService vaultSecrets;
    private final PsqlConfigurationsService configurations;
    private final PsqlBackupsService backups;
    private final Clock clock;
    private final Executor lifecycle;
    private final ExecutorService ownedLifecycle;
    private final ScheduledExecutorService readinessPoller;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Inject
    public PsqlService(StorageFactory storageFactory, EmulatorConfig config,
                       ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                       PsqlContainerManager containers, OciContext ociContext,
                       CertificateGenerator certificates, VaultSecretsService vaultSecrets,
                       PsqlConfigurationsService configurations, PsqlBackupsService backups) {
        this(storageFactory.create("psql", "psql-dbsystems.json",
                        new TypeReference<Map<String, StoredDbSystem>>() {}),
                config, serviceRegistry, workRequests, containers, ociContext, certificates,
                vaultSecrets, configurations, backups, Clock.systemUTC(), null);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param lifecycle runs the Docker work; null for the service's own single worker thread
     */
    PsqlService(StorageBackend<String, StoredDbSystem> systems, EmulatorConfig config,
                ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                PsqlContainerManager containers, OciContext ociContext,
                CertificateGenerator certificates, VaultSecretsService vaultSecrets,
                PsqlConfigurationsService configurations, PsqlBackupsService backups, Clock clock,
                Executor lifecycle) {
        this.systems = systems;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.containers = containers;
        this.ociContext = ociContext;
        this.certificates = certificates;
        this.vaultSecrets = vaultSecrets;
        this.configurations = configurations;
        this.backups = backups;
        this.clock = clock;
        // No thread starts until the first task is submitted, which only real mode does.
        this.ownedLifecycle = lifecycle != null ? null : Executors.newSingleThreadExecutor(daemon("psql-lifecycle"));
        this.lifecycle = lifecycle != null ? lifecycle : ownedLifecycle;
        this.readinessPoller = Executors.newSingleThreadScheduledExecutor(daemon("psql-readiness"));
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("psql")
                .enabled(config.services().psql().enabled())
                .storageKey("psql")
                .resourceClasses(PsqlController.class, PsqlConfigurationsController.class,
                        PsqlBackupsController.class)
                .build());
        if (realMode() && config.services().psql().enabled()) {
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
            for (StoredDbSystem system : allSystems()) {
                if (system.getContainerName() != null) {
                    stopQuietly(system);
                }
            }
        }
    }

    /**
     * Removes every container and volume, whatever {@code prune-volumes-on-delete} says, since
     * nothing could reach the volumes once their systems are forgotten; then forgets every DB
     * system. Holds the lock lifecycle steps record their outcome under, so a step still running
     * finds its system gone and removes what it started.
     */
    @Override
    public synchronized void clear() {
        pending.clear();
        if (realMode()) {
            for (StoredDbSystem system : allSystems()) {
                if (system.getContainerName() != null) {
                    purgeQuietly(system);
                }
            }
            try {
                containers.removeBackups();
            } catch (RuntimeException e) {
                LOG.warnv("Could not remove the PostgreSQL backups: {0}", e.getMessage());
            }
        }
        systems.clear();
    }

    private void purgeQuietly(StoredDbSystem system) {
        try {
            containers.purge(system);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the sidecar of DB system {0}: {1}", system.getId(), e.getMessage());
        }
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(systems, tenancyId, compartmentId, StoredDbSystem::getCompartmentId,
                StoredDbSystem::getLifecycleState, system -> "DB system " + system.getId()).toList();
    }

    // ── DB systems ─────────────────────────────────────────────────────────────

    public Created createDbSystem(Map<String, Object> details) {
        return configurations.attachingConfiguration(() -> create(details));
    }

    private Created create(Map<String, Object> details) {
        String displayName = PsqlRequests.requireString(details, "displayName");
        String compartmentId = PsqlRequests.requireString(details, "compartmentId");
        String major = PsqlCatalog.major(PsqlRequests.requireString(details, "dbVersion"));
        PsqlCatalog.Shape shape = PsqlCatalog.parseShape(PsqlRequests.requireString(details, "shape"));
        Map<String, Object> storage = storageDetails(PsqlRequests.requireMap(details, "storageDetails"));
        Map<String, Object> network = networkDetails(PsqlRequests.requireMap(details, "networkDetails"));
        RestoreSource source = sourceDetails(PsqlRequests.map(details, "source"), major);
        Map<String, Object> credentials = PsqlRequests.requireMap(details, "credentials");
        String adminUsername = PsqlRequests.requireString(credentials, "username");
        String masterPassword = resolvePassword(PsqlRequests.requireMap(credentials, "passwordDetails"));
        String systemType = PsqlRequests.string(details, "systemType");
        if (systemType != null && !PsqlCatalog.SYSTEM_TYPE.equals(systemType)) {
            throw OciException.invalidParameter("Unsupported systemType: " + systemType);
        }
        String configId = PsqlRequests.string(details, "configId");
        String defaultConfigId = PsqlCatalog.defaultConfigurationId(major, shape, ociContext.realm(),
                ociContext.regionCode());
        Map<String, String> overrides = configId != null ? requireConfiguration(configId, major) : Map.of();
        int instanceCount = PsqlRequests.positive(details, "instanceCount", 1);
        List<Map<String, Object>> instancesDetails = PsqlRequests.mapList(details, "instancesDetails");
        if (!instancesDetails.isEmpty() && instancesDetails.size() != instanceCount) {
            throw OciException.invalidParameter("instancesDetails must have one entry per instance: expected "
                    + instanceCount + ", got " + instancesDetails.size());
        }

        String now = now();
        StoredDbSystem system = new StoredDbSystem();
        system.setId(Ocids.generate("postgresqldbsystem", ociContext.realm(), ociContext.regionCode()));
        system.setDisplayName(displayName);
        system.setDescription(PsqlRequests.string(details, "description"));
        system.setCompartmentId(compartmentId);
        system.setTimeCreated(now);
        system.setSystemType(PsqlCatalog.SYSTEM_TYPE);
        system.setSystemRole("STANDALONE_DB_SYSTEM");
        system.setDbVersion(major);
        system.setShape(shape.name());
        system.setInstanceOcpuCount(firstNonNull(shape.ocpuCount(), PsqlRequests.integer(details, "instanceOcpuCount"),
                PsqlCatalog.DEFAULT_OCPU_COUNT));
        system.setInstanceMemorySizeInGBs(firstNonNull(shape.memorySizeInGBs(),
                PsqlRequests.integer(details, "instanceMemorySizeInGBs"), PsqlCatalog.DEFAULT_MEMORY_GBS));
        system.setInstanceCount(instanceCount);
        system.setStorageDetails(storage);
        system.setNetworkDetails(network);
        system.setManagementPolicy(managementPolicy(PsqlRequests.map(details, "managementPolicy"), null));
        system.setSource(source.wire());
        system.setRestoreBackupId(source.backupId());
        system.setConfigId(configId != null ? configId : defaultConfigId);
        system.setConfigOverrides(overrides);
        system.setAdminUsername(adminUsername);
        system.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
        system.setDefinedTags(PsqlRequests.definedTags(details));
        system.setInstances(newInstances(instanceCount, instancesDetails, storage, now));
        system.setEtag(Etags.newEtag());
        system.setTenancyId(ociContext.tenancyId());
        system.setRegion(ociContext.region());
        system.setContainerName(containerName(system.getId()));
        system.setVolumeName(volumeName(system.getId()));
        GeneratedCertificate certificate = certificates.generateSelfSignedCertificate(
                system.getContainerName(), certificateNames(system), KeyAlgorithm.RSA_2048);
        system.setCertificatePem(certificate.certificatePem());
        system.setPrivateKeyPem(certificate.privateKeyPem());

        if (!realMode()) {
            system.setLifecycleState("ACTIVE");
            setInstanceStates(system, "ACTIVE");
            system.setEndpointHost("localhost");
            system.setEndpointPort(config.services().psql().basePort());
            systems.put(system.getId(), system);
            String workRequestId = workRequests.succeeded(WR_SERVICE, "CREATE_POSTGRESQL_DB_SYSTEM",
                    compartmentId, resources(system, "CREATED"));
            return new Created(system, workRequestId);
        }
        system.setLifecycleState("CREATING");
        setInstanceStates(system, "CREATING");
        String workRequestId = workRequests.inProgress(WR_SERVICE, "CREATE_POSTGRESQL_DB_SYSTEM",
                compartmentId, resources(system, "CREATED"));
        system.setWorkRequestId(workRequestId);
        // Stored before the start is queued: the worker looks the system up by its partition.
        systems.put(system.getId(), system);
        launchStart(system, masterPassword, "ACTIVE", "FAILED");
        LOG.infov("createDbSystem {0} ({1}), PostgreSQL {2}", system.getId(), displayName, major);
        return new Created(system, workRequestId);
    }

    /** A deleted DB system is still returned, as DELETED: clients poll it to see the delete finish. */
    public StoredDbSystem getDbSystem(String dbSystemId) {
        return systems.get(dbSystemId).orElseThrow(() -> notFound(dbSystemId));
    }

    public List<StoredDbSystem> listDbSystems(String compartmentId, String lifecycleState, String displayName,
                                              String id, String sortBy, String sortOrder) {
        if (lifecycleState != null && !LIFECYCLE_STATES.contains(lifecycleState)) {
            throw OciException.invalidParameter("Invalid lifecycleState: " + lifecycleState);
        }
        if (sortOrder != null && !SORT_ORDERS.contains(sortOrder)) {
            throw OciException.invalidParameter("Invalid sortOrder: " + sortOrder);
        }
        if (sortBy != null && !SORT_FIELDS.contains(sortBy)) {
            throw OciException.invalidParameter("Invalid sortBy: " + sortBy);
        }
        // timeCreated sorts newest first by default, displayName ascending, as OCI lists do.
        boolean byName = "displayName".equals(sortBy);
        Comparator<StoredDbSystem> order = byName
                ? Comparator.comparing(StoredDbSystem::getDisplayName)
                : Comparator.comparing(StoredDbSystem::getTimeCreated);
        order = order.thenComparing(StoredDbSystem::getId);
        boolean descending = sortOrder != null ? "DESC".equals(sortOrder) : !byName;
        if (descending) {
            order = order.reversed();
        }
        return systems.scan(k -> true).stream()
                .filter(s -> compartmentId == null || compartmentId.equals(s.getCompartmentId()))
                .filter(s -> lifecycleState == null || lifecycleState.equals(s.getLifecycleState()))
                .filter(s -> displayName == null || displayName.equals(s.getDisplayName()))
                .filter(s -> id == null || id.equals(s.getId()))
                .sorted(order)
                .toList();
    }

    /**
     * Applies the editable fields. None of them reaches the running Postgres (shape, storage
     * and policy are management-plane facts here), so the work request is already finished.
     */
    public String updateDbSystem(String dbSystemId, Map<String, Object> details, String ifMatch) {
        return configurations.attachingConfiguration(() -> update(dbSystemId, details, ifMatch));
    }

    private String update(String dbSystemId, Map<String, Object> details, String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE", "INACTIVE", "NEEDS_ATTENTION");
        // Everything that can be rejected is checked before anything changes: the store hands
        // out the stored object itself, so a half-applied update would stick.
        PsqlCatalog.Shape shape = PsqlRequests.string(details, "shape") != null
                ? PsqlCatalog.parseShape(PsqlRequests.string(details, "shape")) : null;
        Integer ocpuCount = PsqlRequests.integer(details, "instanceOcpuCount") != null
                ? PsqlRequests.positive(details, "instanceOcpuCount", 1) : null;
        Integer memorySizeInGBs = PsqlRequests.integer(details, "instanceMemorySizeInGBs") != null
                ? PsqlRequests.positive(details, "instanceMemorySizeInGBs", 1) : null;
        Map<String, Object> storage = PsqlRequests.map(details, "storageDetails");
        Object iops = storage != null ? storage.get("iops") : null;
        if (iops != null && !(iops instanceof Number)) {
            throw OciException.invalidParameter("storageDetails.iops must be a number: " + iops);
        }
        Map<String, Object> configParams = PsqlRequests.map(details, "dbConfigurationParams");
        String configId = configParams != null ? PsqlRequests.requireString(configParams, "configId") : null;
        String applyConfig = configParams != null ? PsqlRequests.string(configParams, "applyConfig") : null;
        PsqlRequests.requireEnum(applyConfig, APPLY_CONFIG, "applyConfig");
        Map<String, String> overrides = configId != null
                ? requireConfiguration(configId, PsqlCatalog.major(system.getDbVersion())) : null;

        if (PsqlRequests.string(details, "displayName") != null) {
            system.setDisplayName(PsqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            system.setDescription(PsqlRequests.string(details, "description"));
        }
        if (shape != null) {
            system.setShape(shape.name());
            if (shape.ocpuCount() != null) {
                system.setInstanceOcpuCount(shape.ocpuCount());
                system.setInstanceMemorySizeInGBs(shape.memorySizeInGBs());
            }
        }
        if (ocpuCount != null) {
            system.setInstanceOcpuCount(ocpuCount);
        }
        if (memorySizeInGBs != null) {
            system.setInstanceMemorySizeInGBs(memorySizeInGBs);
        }
        if (PsqlRequests.map(details, "managementPolicy") != null) {
            system.setManagementPolicy(managementPolicy(PsqlRequests.map(details, "managementPolicy"), system.getManagementPolicy()));
        }
        if (iops instanceof Number number) {
            Map<String, Object> updated = new LinkedHashMap<>(system.getStorageDetails());
            updated.put("iops", number.longValue());
            system.setStorageDetails(updated);
        }
        Map<String, Object> network = PsqlRequests.map(details, "networkDetails");
        if (network != null) {
            Map<String, Object> updated = new LinkedHashMap<>(system.getNetworkDetails());
            if (network.get("nsgIds") != null) {
                updated.put("nsgIds", network.get("nsgIds"));
            }
            if (network.get("isReaderEndpointEnabled") != null) {
                updated.put("isReaderEndpointEnabled", network.get("isReaderEndpointEnabled"));
            }
            system.setNetworkDetails(updated);
        }
        String previousConfigId = system.getConfigId();
        Map<String, String> previousOverrides = system.getConfigOverrides();
        if (configId != null) {
            system.setConfigId(configId);
            system.setConfigOverrides(overrides);
        }
        if (details.containsKey("freeformTags")) {
            system.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
        }
        if (details.containsKey("definedTags")) {
            system.setDefinedTags(PsqlRequests.definedTags(details));
        }
        if (configId != null && realMode()) {
            // A system that needs attention has no running server to reload: the new
            // configuration is its way back.
            if ("NEEDS_ATTENTION".equals(system.getLifecycleState())
                    || ("ACTIVE".equals(system.getLifecycleState()) && "RESTART".equals(applyConfig))) {
                return restartWithConfiguration(system);
            }
            if ("ACTIVE".equals(system.getLifecycleState())) {
                return reloadConfiguration(system, previousConfigId, previousOverrides);
            }
        }
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_POSTGRESQL_DB_SYSTEM",
                system.getCompartmentId(), resources(system, "UPDATED"));
    }

    /** RESTART relaunches the container, which applies every setting. */
    private String restartWithConfiguration(StoredDbSystem system) {
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        setInstanceStates(system, "UPDATING");
        systems.put(system.getId(), system);
        launchStart(system, null, "ACTIVE", "NEEDS_ATTENTION");
        return workRequestId;
    }

    /**
     * RELOAD (Terraform's default) applies the settings that need no restart and leaves the others
     * for the next start, as PostgreSQL itself does. The server keeps running either way: a value
     * it rejects fails the work request and the previous configuration stays in effect.
     */
    private String reloadConfiguration(StoredDbSystem system, String previousConfigId,
                                       Map<String, String> previousOverrides) {
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        setInstanceStates(system, "UPDATING");
        system.setReplacedConfigId(previousConfigId);
        systems.put(system.getId(), system);
        runAsync(system, current -> {
            try {
                containers.applySettings(current);
            } catch (RuntimeException e) {
                return restoreConfiguration(current, previousConfigId, previousOverrides,
                        "The configuration could not be applied: " + e.getMessage());
            }
            return () -> {
                current.setReplacedConfigId(null);
                finishReload(current, "SUCCEEDED", null);
            };
        });
        return workRequestId;
    }

    /**
     * Puts the previous settings back. They are applied again, not just recorded: ALTER SYSTEM
     * cannot run in a transaction, so the settings before the rejected one have already changed.
     */
    private Runnable restoreConfiguration(StoredDbSystem system, String previousConfigId,
                                          Map<String, String> previousOverrides, String reason) {
        system.setConfigId(previousConfigId);
        system.setConfigOverrides(previousOverrides);
        system.setReplacedConfigId(null);
        try {
            containers.applySettings(system);
        } catch (RuntimeException e) {
            return () -> fail(system, "NEEDS_ATTENTION",
                    reason + "; restoring the previous one failed too: " + e.getMessage());
        }
        return () -> {
            finishReload(system, "FAILED", reason + "; the previous one stays in effect");
            LOG.warnv("DB system {0} keeps its previous configuration: {1}", system.getId(), reason);
        };
    }

    private void finishReload(StoredDbSystem system, String status, String error) {
        system.setLifecycleState("ACTIVE");
        setInstanceStates(system, "ACTIVE");
        touch(system);
        saveIn(system);
        finishWorkRequest(system, status, error);
    }

    public String changeCompartment(String dbSystemId, String compartmentId, String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE", "INACTIVE");
        system.setCompartmentId(compartmentId);
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, "MOVE_POSTGRESQL_DB_SYSTEM",
                compartmentId, resources(system, "UPDATED"));
    }

    public String deleteDbSystem(String dbSystemId, String ifMatch) {
        StoredDbSystem system = getDbSystem(dbSystemId);
        Etags.checkIfMatch(ifMatch, system.getEtag());
        if ("DELETED".equals(system.getLifecycleState()) || "DELETING".equals(system.getLifecycleState())) {
            throw incorrectState(system, "deleted");
        }
        String workRequestId;
        // An operation still under way is abandoned: its work request ends CANCELED, and its
        // step is skipped, or its outcome dropped, because the system has moved on to another
        // work request. The lock keeps a step from recording its outcome in between.
        synchronized (this) {
            pending.remove(dbSystemId);
            finishWorkRequest(system, "CANCELED", null);
            if (!realMode()) {
                markDeleted(system);
                systems.put(system.getId(), system);
                return workRequests.succeeded(WR_SERVICE, "DELETE_POSTGRESQL_DB_SYSTEM",
                        system.getCompartmentId(), resources(system, "DELETED"));
            }
            workRequestId = begin(system, "DELETING", "DELETE_POSTGRESQL_DB_SYSTEM", "DELETED");
        }
        runDelete(system);
        return workRequestId;
    }

    /** Queues the removal of the container; the system then stays readable as DELETED. */
    private void runDelete(StoredDbSystem system) {
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

    // ── Backups and restore ────────────────────────────────────────────────────

    /** The DB system to back up: it must be ACTIVE, with its server running in real mode. */
    public StoredDbSystem backupSource(String dbSystemId) {
        StoredDbSystem system = getDbSystem(dbSystemId);
        if (!"ACTIVE".equals(system.getLifecycleState())) {
            throw incorrectState(system, "backed up");
        }
        return system;
    }

    /**
     * Restores a backup over the running system's data, in place: the dump drops and recreates
     * what it contains and keeps the admin password. {@code timeToRestore} picks the backup at or
     * before it; otherwise {@code backupId} names it. Returns the work request id.
     */
    public String restoreDbSystem(String dbSystemId, Map<String, Object> details, String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        String timeToRestore = PsqlRequests.string(details, "timeToRestore");
        String backupId = PsqlRequests.string(details, "backupId");
        StoredBackup backup;
        if (timeToRestore != null) {
            backup = backups.requireAtOrBefore(dbSystemId, timeToRestore);
        } else if (backupId != null) {
            backup = backups.requireRestorable(backupId);
        } else {
            throw OciException.missingParameter("Missing required parameter: backupId or timeToRestore");
        }
        String major = PsqlCatalog.major(system.getDbVersion());
        if (!major.equals(backup.getDbSystemDetails().get("dbVersion"))) {
            throw OciException.invalidParameter("Backup " + backup.getId() + " is of PostgreSQL "
                    + backup.getDbSystemDetails().get("dbVersion") + ", not " + major);
        }
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE");
        }
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        setInstanceStates(system, "UPDATING");
        systems.put(system.getId(), system);
        String dumpId = backup.getDumpId();
        runAsync(system, current -> {
            try {
                containers.restore(current, dumpId);
            } catch (RuntimeException e) {
                return () -> fail(current, "NEEDS_ATTENTION", "The backup could not be restored: " + e.getMessage());
            }
            return () -> {
                current.setLifecycleState("ACTIVE");
                setInstanceStates(current, "ACTIVE");
                touch(current);
                saveIn(current);
                finishWorkRequest(current, "SUCCEEDED", null);
                LOG.infov("restoreDbSystem {0} from backup {1}", current.getId(), dumpId);
            };
        });
        return workRequestId;
    }

    public Map<String, Object> pitrDetails(String dbSystemId) {
        return backups.pitrDetails(getDbSystem(dbSystemId));
    }

    // ── Runtime actions ────────────────────────────────────────────────────────

    public String stopDbSystem(String dbSystemId, String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        if (!realMode()) {
            return finishAtOnce(system, "INACTIVE");
        }
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        runAsync(system, current -> {
            containers.stop(current);
            return () -> {
                current.setLifecycleState("INACTIVE");
                setInstanceStates(current, "INACTIVE");
                touch(current);
                saveIn(current);
                finishWorkRequest(current, "SUCCEEDED", null);
            };
        });
        return workRequestId;
    }

    public String startDbSystem(String dbSystemId, String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "INACTIVE", "NEEDS_ATTENTION");
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE");
        }
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        launchStart(system, null, "ACTIVE", "NEEDS_ATTENTION");
        return workRequestId;
    }

    public String restartDbInstance(String dbSystemId, Map<String, Object> details, String ifMatch) {
        String dbInstanceId = PsqlRequests.requireString(details, "dbInstanceId");
        String restartType = PsqlRequests.requireString(details, "restartType");
        if (!RESTART_TYPES.contains(restartType)) {
            throw OciException.invalidParameter("Invalid restartType: " + restartType);
        }
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        instance(system, dbInstanceId);
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE");
        }
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        launchStart(system, null, "ACTIVE", "NEEDS_ATTENTION");
        return workRequestId;
    }

    public String resetMasterUserPassword(String dbSystemId, Map<String, Object> details, String ifMatch) {
        String newPassword = resolvePassword(PsqlRequests.requireMap(details, "passwordDetails"));
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE");
        if (!realMode()) {
            return finishAtOnce(system, "ACTIVE");
        }
        String workRequestId = begin(system, "UPDATING", "UPDATE_POSTGRESQL_DB_SYSTEM", "UPDATED");
        runAsync(system, current -> {
            String error = null;
            try {
                containers.resetPassword(current, newPassword);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            String failure = error;
            // The database keeps running either way; only the work request tells them apart.
            return () -> {
                current.setLifecycleState("ACTIVE");
                touch(current);
                saveIn(current);
                finishWorkRequest(current, failure == null ? "SUCCEEDED" : "FAILED", failure);
            };
        });
        return workRequestId;
    }

    public String updateDbInstance(String dbSystemId, String dbInstanceId, Map<String, Object> details,
                                   String ifMatch) {
        StoredDbSystem system = mutable(dbSystemId, ifMatch, "ACTIVE", "INACTIVE");
        StoredDbInstance instance = instance(system, dbInstanceId);
        if (PsqlRequests.string(details, "displayName") != null) {
            instance.setDisplayName(PsqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            instance.setDescription(PsqlRequests.string(details, "description"));
        }
        instance.setTimeUpdated(now());
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_POSTGRESQL_DB_SYSTEM",
                system.getCompartmentId(), resources(system, "UPDATED"));
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    /**
     * How to reach the database: the certificate to trust, and the host and port PostgreSQL
     * answers on. Every instance shares the one endpoint, since one container serves them all.
     */
    public Map<String, Object> connectionDetails(String dbSystemId) {
        StoredDbSystem system = getDbSystem(dbSystemId);
        if (system.getEndpointHost() == null) {
            throw incorrectState(system, "connected to: its endpoint is not available yet");
        }
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("fqdn", system.getEndpointHost());
        Object privateIp = system.getNetworkDetails().get("primaryDbEndpointPrivateIp");
        if (privateIp != null) {
            endpoint.put("ipAddress", privateIp);
        }
        endpoint.put("port", system.getEndpointPort());

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("caCertificate", system.getCertificatePem());
        wire.put("primaryDbEndpoint", endpoint);
        wire.put("instanceEndpoints", system.getInstances().stream()
                .map(instance -> Map.of("dbInstanceId", instance.getId(), "endpoint", endpoint))
                .toList());
        if (Boolean.TRUE.equals(system.getNetworkDetails().get("isReaderEndpointEnabled"))) {
            wire.put("readerEndpoint", endpoint);
        }
        return wire;
    }

    public Map<String, Object> primaryDbInstance(String dbSystemId) {
        StoredDbSystem system = getDbSystem(dbSystemId);
        return Map.of("dbInstanceId", system.getInstances().getFirst().getId());
    }

    // ── Lifecycle engine ───────────────────────────────────────────────────────

    boolean realMode() {
        return containers != null && !config.services().psql().mock();
    }

    /** Moves the system to {@code state} and opens the work request the operation is tracked by. */
    private String begin(StoredDbSystem system, String state, String operationType, String actionType) {
        system.setLifecycleState(state);
        system.setLifecycleDetails(null);
        touch(system);
        String workRequestId = workRequests.inProgress(WR_SERVICE, operationType,
                system.getCompartmentId(), resources(system, actionType));
        system.setWorkRequestId(workRequestId);
        systems.put(system.getId(), system);
        return workRequestId;
    }

    /** Mock mode: the transition and its work request complete on the spot. */
    private String finishAtOnce(StoredDbSystem system, String state) {
        system.setLifecycleState(state);
        setInstanceStates(system, state);
        touch(system);
        systems.put(system.getId(), system);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_POSTGRESQL_DB_SYSTEM",
                system.getCompartmentId(), resources(system, "UPDATED"));
    }

    /** The Docker work of an operation, returning what to record once it is done. */
    private interface LifecycleStep {
        Runnable run(StoredDbSystem current);
    }

    /**
     * Runs {@code step} on the lifecycle worker against the system as stored at that moment,
     * found in its own partition since the worker has no request scope. The step is skipped
     * when the system has gone, or has moved on to another operation (a delete that overtook a
     * start, for example), which shows as a different work request. The Docker work can take a
     * while, so ownership is checked again before its outcome is recorded, under the lock that
     * delete and reset take: an outcome that arrives too late is dropped.
     */
    private void runAsync(StoredDbSystem system, LifecycleStep step) {
        String tenancyId = system.getTenancyId();
        String region = system.getRegion();
        String id = system.getId();
        String workRequestId = system.getWorkRequestId();
        lifecycle.execute(() -> {
            Optional<StoredDbSystem> current = owned(tenancyId, region, id, workRequestId);
            if (current.isEmpty()) {
                LOG.debugv("DB system {0} has moved on; skipping its queued lifecycle step", id);
                return;
            }
            Runnable outcome;
            try {
                outcome = step.run(current.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Lifecycle step failed for DB system {0}", id);
                outcome = () -> fail(current.get(), "NEEDS_ATTENTION", e.getMessage());
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
                }
            }
        });
    }

    /** The system, while {@code workRequestId} is still the operation it is running. */
    private Optional<StoredDbSystem> owned(String tenancyId, String region, String id, String workRequestId) {
        return findIn(tenancyId, region, id)
                .filter(system -> Objects.equals(workRequestId, system.getWorkRequestId()));
    }

    /**
     * Queues a (re)start of the container; the poller takes over once it is running. A null
     * password means the data volume is expected to exist already. If it does not, Postgres
     * initialises with a password nobody knows and ResetMasterUserPassword sets a real one.
     */
    private void launchStart(StoredDbSystem system, String masterPassword, String targetState,
                             String failureState) {
        String password = masterPassword != null ? masterPassword : UUID.randomUUID().toString();
        runAsync(system, current -> {
            try {
                containers.stop(current);
                containers.start(current, password);
            } catch (RuntimeException e) {
                return () -> fail(current, failureState,
                        "The PostgreSQL container could not be started: " + e.getMessage());
            }
            return () -> {
                saveIn(current);
                pending.put(current.getId(), new Pending(current.getTenancyId(), current.getRegion(),
                        clock.instant(), targetState, failureState));
            };
        });
    }

    private void pollReadinessSafely() {
        try {
            pollReadiness();
        } catch (RuntimeException e) {
            LOG.warnv(e, "PostgreSQL readiness poll failed");
        }
    }

    /**
     * One pass over the starting systems: the target state once Postgres accepts connections,
     * the failure state when the container has exited or the ready timeout has passed. Each
     * outcome also finishes the work request, which is what Terraform and the CLI wait on.
     */
    void pollReadiness() {
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            String id = entry.getKey();
            Pending waiting = entry.getValue();
            Optional<StoredDbSystem> found = findIn(waiting.tenancyId(), waiting.region(), id);
            if (found.isEmpty()) {
                pending.remove(id);
                continue;
            }
            try {
                pollOne(waiting, found.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "PostgreSQL readiness poll failed for DB system {0}", id);
            }
        }
    }

    private void pollOne(Pending waiting, StoredDbSystem system) {
        int timeout = config.services().psql().readyTimeoutSeconds();
        if (probeReady(system)) {
            try {
                containers.applySettings(system);
            } catch (RuntimeException e) {
                settle(system, () -> fail(system, waiting.failureState(),
                        "The configuration could not be applied: " + e.getMessage()));
                return;
            }
            if (system.getRestoreBackupId() != null) {
                try {
                    containers.restore(system, system.getRestoreBackupId());
                } catch (RuntimeException e) {
                    settle(system, () -> fail(system, waiting.failureState(),
                            "The backup could not be restored: " + e.getMessage()));
                    return;
                }
                system.setRestoreBackupId(null);
            }
            settle(system, () -> {
                containers.serverVersion(system).ifPresent(system::setDbVersion);
                EndpointInfo endpoint = containers.clientEndpoint(system);
                system.setEndpointHost(endpoint.host());
                system.setEndpointPort(endpoint.port());
                system.setLifecycleState(waiting.targetState());
                system.setLifecycleDetails(null);
                setInstanceStates(system, waiting.targetState());
                touch(system);
                saveIn(system);
                finishWorkRequest(system, "SUCCEEDED", null);
                LOG.infov("DB system {0} is {1} at {2}", system.getId(), waiting.targetState(), endpoint);
            });
        } else if (!containers.isRunning(system)) {
            settle(system, () -> fail(system, waiting.failureState(),
                    "The PostgreSQL container exited before it accepted connections"));
        } else if (clock.instant().isAfter(waiting.since().plusSeconds(timeout))) {
            settle(system, () -> fail(system, waiting.failureState(),
                    "PostgreSQL did not accept connections after " + timeout + " seconds"));
        }
    }

    /**
     * A probe that throws means not ready yet. Docker refuses an exec in a container that has
     * exited, and the checks after the probe are what notice that exit, or the timeout.
     */
    private boolean probeReady(StoredDbSystem system) {
        try {
            return containers.probeReady(system);
        } catch (RuntimeException e) {
            LOG.debugv("Could not probe DB system {0}: {1}", system.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Records an outcome only while the system is still pending, atomically with dropping it
     * from {@link #pending}: a delete or reset that removed it during the probe wins.
     */
    private void settle(StoredDbSystem system, Runnable outcome) {
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

    private void fail(StoredDbSystem system, String state, String message) {
        stopQuietly(system);
        system.setLifecycleState(state);
        system.setLifecycleDetails(message);
        setInstanceStates(system, "FAILED");
        touch(system);
        saveIn(system);
        finishWorkRequest(system, "FAILED", message);
        LOG.warnv("DB system {0} is {1}: {2}", system.getId(), state, message);
    }

    private void finishWorkRequest(StoredDbSystem system, String status, String error) {
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
     * the master password it carried is gone. Any other operation (stop, restart, password reset)
     * cannot be resumed either: its work request fails and the system comes back ACTIVE as it was.
     */
    void restartPersistedSystems() {
        backups.settleInterruptedBackups();
        for (StoredDbSystem system : allSystems()) {
            if (system.getWorkRequestId() != null) {
                switch (system.getLifecycleState()) {
                    case "DELETING" -> {
                        runDelete(system);
                        continue;
                    }
                    case "CREATING" -> {
                        fail(system, "FAILED", INTERRUPTED);
                        continue;
                    }
                    default -> finishWorkRequest(system, "FAILED", INTERRUPTED);
                }
            }
            if (Set.of("ACTIVE", "UPDATING").contains(system.getLifecycleState())) {
                system.setLifecycleState("UPDATING");
                saveIn(system);
                launchStart(system, null, "ACTIVE", "NEEDS_ATTENTION");
            }
        }
    }

    private void stopQuietly(StoredDbSystem system) {
        try {
            containers.stop(system);
        } catch (RuntimeException e) {
            LOG.warnv("Could not stop the sidecar of DB system {0}: {1}", system.getId(), e.getMessage());
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────────

    private static String partition(String tenancyId, String region) {
        return TenancyAwareStorageBackend.regionalPartition(tenancyId, region);
    }

    private Optional<StoredDbSystem> findIn(String tenancyId, String region, String id) {
        return TenancyAwareStorageBackend.getIn(systems, partition(tenancyId, region), id);
    }

    private void saveIn(StoredDbSystem system) {
        TenancyAwareStorageBackend.putIn(systems, partition(system.getTenancyId(), system.getRegion()),
                system.getId(), system);
    }

    /** Every DB system of every tenancy and region, for startup, shutdown and reset. */
    private List<StoredDbSystem> allSystems() {
        return TenancyAwareStorageBackend.scanAll(systems);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The system, when it may be changed: its etag matches and it is in one of {@code states}. */
    private StoredDbSystem mutable(String dbSystemId, String ifMatch, String... states) {
        StoredDbSystem system = getDbSystem(dbSystemId);
        Etags.checkIfMatch(ifMatch, system.getEtag());
        if ("DELETED".equals(system.getLifecycleState())) {
            throw notFound(dbSystemId);
        }
        if (!List.of(states).contains(system.getLifecycleState())) {
            throw incorrectState(system, "changed");
        }
        return system;
    }

    private static StoredDbInstance instance(StoredDbSystem system, String dbInstanceId) {
        return system.getInstances().stream()
                .filter(instance -> instance.getId().equals(dbInstanceId))
                .findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: DB instance " + dbInstanceId));
    }

    /** A configuration must be one of the catalog's defaults, for the DB system's own version. */
    /**
     * The settings {@code configId} overrides, once it is checked to be a default configuration
     * or an ACTIVE custom one in this tenancy and region, for {@code major}.
     */
    private Map<String, String> requireConfiguration(String configId, String major) {
        Optional<String> defaultMajor = PsqlCatalog.defaultConfigurationMajor(
                configId, ociContext.realm(), ociContext.regionCode());
        Optional<StoredConfiguration> custom = defaultMajor.isPresent() ? Optional.empty()
                : configurations.find(configId).filter(c -> "ACTIVE".equals(c.getLifecycleState()));
        if (defaultMajor.isEmpty() && custom.isEmpty()) {
            throw new OciException("RelatedResourceNotAuthorizedOrNotFound",
                    "Authorization failed or requested resource not found: configuration " + configId, 400);
        }
        String configurationMajor = defaultMajor.orElseGet(() -> custom.get().getDbVersion());
        if (!configurationMajor.equals(major)) {
            throw OciException.invalidParameter("Configuration " + configId + " is for PostgreSQL "
                    + configurationMajor + ", not " + major);
        }
        return custom.map(c -> Map.copyOf(c.getOverrides())).orElse(Map.of());
    }

    /**
     * Whether a DB system that is not DELETED uses {@code configId}, in the caller's tenancy and
     * region, or could go back to it because a reload that replaces it has not finished.
     */
    public boolean usesConfiguration(String configId) {
        return systems.scan(k -> true).stream()
                .filter(s -> !"DELETED".equals(s.getLifecycleState()))
                .anyMatch(s -> configId.equals(s.getConfigId()) || configId.equals(s.getReplacedConfigId()));
    }

    private void markDeleted(StoredDbSystem system) {
        system.setLifecycleState("DELETED");
        system.setLifecycleDetails(null);
        system.setEndpointHost(null);
        setInstanceStates(system, "DELETED");
        touch(system);
    }

    private void touch(StoredDbSystem system) {
        system.setTimeUpdated(now());
        system.setEtag(Etags.newEtag());
    }

    private static void setInstanceStates(StoredDbSystem system, String state) {
        // DbInstance has no NEEDS_ATTENTION state.
        String instanceState = "NEEDS_ATTENTION".equals(state) ? "FAILED" : state;
        system.getInstances().forEach(instance -> instance.setLifecycleState(instanceState));
    }

    private List<StoredDbInstance> newInstances(int count, List<Map<String, Object>> details,
                                                Map<String, Object> storage, String now) {
        List<StoredDbInstance> instances = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StoredDbInstance instance = new StoredDbInstance();
            instance.setId(Ocids.generate("postgresqldbinstance", ociContext.realm(), ociContext.regionCode()));
            if (!details.isEmpty()) {
                instance.setDisplayName(PsqlRequests.string(details.get(i), "displayName"));
                instance.setDescription(PsqlRequests.string(details.get(i), "description"));
            }
            Object availabilityDomain = storage.get("availabilityDomain");
            instance.setAvailabilityDomain(availabilityDomain != null ? availabilityDomain.toString()
                    : ociContext.region().toUpperCase(Locale.ROOT) + "-AD-" + (i % 3 + 1));
            instance.setTimeCreated(now);
            instances.add(instance);
        }
        return instances;
    }

    /** Every name the endpoint can be reached by, so verify-full works against any of them. */
    private List<String> certificateNames(StoredDbSystem system) {
        List<String> names = new ArrayList<>(List.of(system.getContainerName(), "localhost", "127.0.0.1",
                "localhost.floci.io"));
        config.hostname().filter(hostname -> !hostname.isBlank()).ifPresent(names::add);
        // A remote Docker daemon (DOCKER_HOST=tcp://...) is where published ports are reached.
        String daemonHost = containers != null ? containers.daemonHost() : null;
        if (daemonHost != null && !names.contains(daemonHost)) {
            names.add(daemonHost);
        }
        return names;
    }

    private String containerName(String dbSystemId) {
        return containers != null ? containers.containerName(dbSystemId) : "psql-" + dbSystemId;
    }

    private String volumeName(String dbSystemId) {
        return containers != null ? containers.volumeName(dbSystemId) : "psql-vol-" + dbSystemId;
    }

    private List<StoredWorkRequest.Resource> resources(StoredDbSystem system, String actionType) {
        return List.of(WorkRequestService.resource(ENTITY_TYPE, actionType, system.getId(),
                BASE_PATH + system.getId()));
    }

    private static Map<String, Object> storageDetails(Map<String, Object> requested) {
        String systemType = PsqlRequests.requireString(requested, "systemType");
        if (!PsqlCatalog.SYSTEM_TYPE.equals(systemType)) {
            throw OciException.invalidParameter("Unsupported storageDetails.systemType: " + systemType);
        }
        boolean regionallyDurable = Boolean.TRUE.equals(requested.get("isRegionallyDurable"));
        String availabilityDomain = PsqlRequests.string(requested, "availabilityDomain");
        if (regionallyDurable && availabilityDomain != null) {
            throw OciException.invalidParameter(
                    "availabilityDomain must not be specified when isRegionallyDurable is true");
        }
        if (!regionallyDurable && availabilityDomain == null) {
            throw OciException.invalidParameter(
                    "availabilityDomain must be specified when isRegionallyDurable is false");
        }
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("systemType", systemType);
        storage.put("isRegionallyDurable", regionallyDurable);
        if (availabilityDomain != null) {
            storage.put("availabilityDomain", availabilityDomain);
        }
        if (PsqlRequests.string(requested, "kmsKeyId") != null) {
            storage.put("kmsKeyId", PsqlRequests.string(requested, "kmsKeyId"));
        }
        Object iops = requested.get("iops");
        storage.put("iops", iops instanceof Number number ? number.longValue() : DEFAULT_IOPS);
        return storage;
    }

    private static Map<String, Object> networkDetails(Map<String, Object> requested) {
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("subnetId", PsqlRequests.requireString(requested, "subnetId"));
        if (PsqlRequests.string(requested, "primaryDbEndpointPrivateIp") != null) {
            network.put("primaryDbEndpointPrivateIp", PsqlRequests.string(requested, "primaryDbEndpointPrivateIp"));
        }
        if (requested.get("nsgIds") != null) {
            network.put("nsgIds", requested.get("nsgIds"));
        }
        if (requested.get("isReaderEndpointEnabled") != null) {
            network.put("isReaderEndpointEnabled", requested.get("isReaderEndpointEnabled"));
        }
        return network;
    }

    /** Only a fresh DB system is supported; creating from a backup or another system is not. */
    /** A create's {@code source}, as echoed back on GET, and the backup it restores, if any. */
    private record RestoreSource(Map<String, Object> wire, String backupId) {
    }

    /**
     * The {@code source} of a create: none, a backup, or a point in time of another DB system
     * (the backup at or before it). The echo carries exactly the fields the SDK defines for the
     * source type, since Terraform plans a replacement if any of them read back differently.
     */
    private RestoreSource sourceDetails(Map<String, Object> requested, String major) {
        String sourceType = requested == null ? "NONE" : PsqlRequests.requireString(requested, "sourceType");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("sourceType", sourceType);
        StoredBackup backup;
        switch (sourceType) {
            case "NONE" -> {
                return new RestoreSource(wire, null);
            }
            case "BACKUP" -> {
                String backupId = PsqlRequests.requireString(requested, "backupId");
                backup = backups.requireRestorable(backupId);
                wire.put("backupId", backupId);
                if (requested.get("isHavingRestoreConfigOverrides") instanceof Boolean overrides) {
                    wire.put("isHavingRestoreConfigOverrides", overrides);
                }
            }
            case "POINT_IN_TIME_DB_SYSTEM" -> {
                String dbSystemId = PsqlRequests.requireString(requested, "dbSystemId");
                String timeToRestore = PsqlRequests.requireString(requested, "timeToRestore");
                backup = backups.requireAtOrBefore(dbSystemId, timeToRestore);
                wire.put("dbSystemId", dbSystemId);
                wire.put("timeToRestore", timeToRestore);
            }
            default -> throw OciException.notImplemented("Creating a DB system from source " + sourceType
                    + " is not supported by floci-oci");
        }
        Object backupMajor = backup.getDbSystemDetails().get("dbVersion");
        if (!major.equals(backupMajor)) {
            throw OciException.invalidParameter("Backup " + backup.getId() + " is of PostgreSQL " + backupMajor
                    + ", not " + major);
        }
        return new RestoreSource(wire, backup.getDumpId());
    }

    /** The response always carries a maintenance window and a backup policy; fill in what is missing. */
    private static Map<String, Object> managementPolicy(Map<String, Object> requested, Map<String, Object> current) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maintenanceWindowStart", DEFAULT_MAINTENANCE_WINDOW);
        policy.put("backupPolicy", Map.of("kind", "NONE"));
        if (current != null) {
            policy.putAll(current);
        }
        if (requested != null) {
            requested.forEach((key, value) -> {
                if (value != null) {
                    policy.put(key, value);
                }
            });
        }
        return policy;
    }

    /** The master password: given in the request, or read from a secret in this emulator's Vault. */
    private String resolvePassword(Map<String, Object> passwordDetails) {
        String passwordType = PsqlRequests.requireString(passwordDetails, "passwordType");
        return switch (passwordType) {
            case "PLAIN_TEXT" -> PsqlRequests.requireString(passwordDetails, "password");
            case "VAULT_SECRET" -> {
                String secretId = PsqlRequests.requireString(passwordDetails, "secretId");
                String secretVersion = PsqlRequests.requireString(passwordDetails, "secretVersion");
                try {
                    String content = vaultSecrets.getBundle(secretId, Long.valueOf(secretVersion), null)
                            .version().getContent();
                    yield new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
                } catch (NumberFormatException e) {
                    throw OciException.invalidParameter("secretVersion must be a number: " + secretVersion);
                } catch (OciException e) {
                    throw new OciException("RelatedResourceNotAuthorizedOrNotFound",
                            "Authorization failed or requested resource not found: secret " + secretId
                                    + " version " + secretVersion, 400);
                }
            }
            default -> throw OciException.invalidParameter("Invalid passwordType: " + passwordType);
        };
    }

    private String now() {
        return clock.instant().toString();
    }

    private static OciException notFound(String dbSystemId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: DB system " + dbSystemId);
    }

    private static OciException incorrectState(StoredDbSystem system, String action) {
        return new OciException("IncorrectState", "DB system " + system.getId() + " is "
                + system.getLifecycleState() + " and cannot be " + action, 409);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
