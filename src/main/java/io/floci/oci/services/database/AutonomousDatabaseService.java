package io.floci.oci.services.database;

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
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import io.quarkus.runtime.StartupEvent;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Autonomous Databases (Serverless) on the Database service, API version {@code 20160918}
 * (oci-go-sdk/database DatabaseClient). Every operation is synchronous here: in mock mode a
 * database is AVAILABLE as soon as it is created, and each mutation records a SUCCEEDED work
 * request with an {@code autonomousDatabase} resource, which Terraform reads to confirm the
 * create. Without mock mode CreateAutonomousDatabase answers 501, since the Autonomous Database
 * Free sidecar is not part of floci-oci yet.
 */
@ApplicationScoped
public class AutonomousDatabaseService implements Resettable, CompartmentUsage {

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

    @Inject
    public AutonomousDatabaseService(StorageFactory storageFactory, EmulatorConfig config,
                                     ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                                     OciContext ociContext, Instance<NetworkReferences> networkReferences) {
        this(storageFactory.create(SERVICE, "database-autonomous-databases.json",
                        new TypeReference<Map<String, StoredAutonomousDatabase>>() {}),
                config, serviceRegistry, workRequests, ociContext, NetworkReferences.of(networkReferences),
                Clock.systemUTC());
    }

    /** Storage-injecting constructor for tests. */
    AutonomousDatabaseService(StorageBackend<String, StoredAutonomousDatabase> databases, EmulatorConfig config,
                              ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                              OciContext ociContext, NetworkReferences networkReferences, Clock clock) {
        this.databases = databases;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.networkReferences = networkReferences;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("database")
                .enabled(config.services().database().enabled())
                .storageKey("database")
                .resourceClasses(AutonomousDatabaseController.class)
                .build());
    }

    @Override
    public synchronized void clear() {
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

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public synchronized Result create(Map<String, Object> details) {
        String compartmentId = DatabaseRequests.requireString(details, "compartmentId");
        String source = DatabaseRequests.string(details, "source");
        if (source != null && !"NONE".equals(source)) {
            throw OciException.invalidParameter("source " + source
                    + " is not supported: floci-oci creates Autonomous Databases from scratch only (source NONE)");
        }
        if (!config.services().database().mock()) {
            throw OciException.notImplemented("Autonomous Databases need the Autonomous Database Free sidecar,"
                    + " which floci-oci does not run yet. Set FLOCI_OCI_SERVICES_DATABASE_MOCK=true to emulate"
                    + " the management plane only");
        }
        if (DatabaseRequests.string(details, "secretId") == null) {
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
        reconcileCompute(attributes, details);
        database.setAttributes(attributes);
        refreshEndpoints(database);
        database.setLifecycleState("AVAILABLE");
        database.setEtag(Etags.newEtag());
        databases.put(database.getId(), database);
        LOG.infov("createAutonomousDatabase {0} ({1})", database.getId(), dbName);
        return new Result(database, record("CREATE_AUTONOMOUS_DATABASE", database, "CREATED"));
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
                // No database is a refreshable clone or a resource pool member.
                .filter(database -> !Boolean.TRUE.equals(filter.isRefreshableClone()))
                .filter(database -> !Boolean.TRUE.equals(filter.isResourcePoolLeader()))
                .filter(database -> filter.resourcePoolLeaderId() == null)
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

    public synchronized Result update(String autonomousDatabaseId, Map<String, Object> details, String ifMatch) {
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
        reconcileCompute(attributes, details);

        if (dbName != null) {
            database.setDbName(dbName);
        }
        String displayName = DatabaseRequests.string(details, "displayName");
        if (displayName != null) {
            database.setDisplayName(displayName);
        }
        database.setAttributes(attributes);
        refreshEndpoints(database);
        touch(database);
        return new Result(database, record("UPDATE_AUTONOMOUS_DATABASE", database, "UPDATED"));
    }

    public synchronized String delete(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, LIFECYCLE_STATES.toArray(String[]::new));
        database.setLifecycleState("TERMINATED");
        database.setLifecycleDetails(null);
        touch(database);
        LOG.infov("deleteAutonomousDatabase {0}", autonomousDatabaseId);
        return record("DELETE_AUTONOMOUS_DATABASE", database, "DELETED");
    }

    public synchronized Result start(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "STOPPED");
        database.setLifecycleState("AVAILABLE");
        touch(database);
        return new Result(database, record("START_AUTONOMOUS_DATABASE", database, "UPDATED"));
    }

    public synchronized Result stop(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "AVAILABLE");
        database.setLifecycleState("STOPPED");
        touch(database);
        return new Result(database, record("STOP_AUTONOMOUS_DATABASE", database, "UPDATED"));
    }

    public synchronized Result restart(String autonomousDatabaseId, String ifMatch) {
        StoredAutonomousDatabase database = mutable(autonomousDatabaseId, ifMatch, "AVAILABLE");
        touch(database);
        return new Result(database, record("RESTART_AUTONOMOUS_DATABASE", database, "UPDATED"));
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
        requireWalletPassword(DatabaseRequests.requireString(details, "password"));
        DatabaseRequests.requireEnum(DatabaseRequests.string(details, "generateType"), Set.of("ALL", "SINGLE"),
                "generateType");
        return AutonomousDatabaseWallet.zip(database.getDbName(), MOCK_HOST, MTLS_PORT);
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
    private static void reconcileCompute(Map<String, Object> attributes, Map<String, Object> details) {
        DatabaseRequests.positiveNumber(details, "computeCount");
        DatabaseRequests.positiveNumber(details, "ocpuCount");
        DatabaseRequests.positiveInteger(details, "cpuCoreCount");
        if ("OCPU".equals(attributes.get("computeModel")) && details.get("computeCount") == null) {
            Object ocpus = details.get("ocpuCount") != null ? details.get("ocpuCount") : details.get("cpuCoreCount");
            attributes.put("computeCount", ocpus != null ? ocpus : 1);
        }
    }

    /** The connection strings and URLs follow dbName; in mock mode nothing listens on them. */
    private static void refreshEndpoints(StoredAutonomousDatabase database) {
        database.getAttributes().put("connectionStrings",
                AutonomousDatabaseWallet.connectionStrings(database.getDbName(), MOCK_HOST, MTLS_PORT));
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
            // Nothing changes a mock database's state behind the caller's back, so the condition
            // never clears by itself: Conflict, which SDKs do not retry, rather than IncorrectState.
            throw OciException.conflict("Autonomous Database " + autonomousDatabaseId + " is "
                    + database.getLifecycleState() + "; the operation needs it " + String.join(" or ", states));
        }
        return database;
    }

    private void touch(StoredAutonomousDatabase database) {
        database.setEtag(Etags.newEtag());
        databases.put(database.getId(), database);
    }

    private String record(String operationType, StoredAutonomousDatabase database, String actionType) {
        List<StoredWorkRequest.Resource> resources = List.of(WorkRequestService.resource(ENTITY_TYPE, actionType,
                database.getId(), BASE_PATH + database.getId()));
        return workRequests.succeeded(SERVICE, operationType, database.getCompartmentId(), resources);
    }

    private static OciException notFound(String autonomousDatabaseId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: Autonomous Database " + autonomousDatabaseId);
    }
}
