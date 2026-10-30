package io.floci.oci.services.psql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.psql.model.StoredConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Custom configurations for OCI Database with PostgreSQL: setting overrides for one major
 * version and shape that DB systems can use. Every call is synchronous, as in the SDK (no
 * operation returns a work request). Configurations are regional, like DB systems.
 */
@ApplicationScoped
public class PsqlConfigurationsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(PsqlConfigurationsService.class);

    /** Oracle's service limit: configurations per tenancy per region. */
    static final int LIMIT = 20;
    static final Set<String> LIFECYCLE_STATES = Set.of("ACTIVE", "DELETING", "DELETED", "FAILED");
    static final Set<String> CONFIG_TYPES = Set.of("DEFAULT", "CUSTOM", "COPIED");
    static final Set<String> SORT_BY = Set.of("timeCreated", "displayName");
    static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final String FLEXIBLE_SHAPE = "PostgreSQL";

    private final StorageBackend<String, StoredConfiguration> configurations;
    private final OciContext ociContext;
    private final Clock clock;

    @Inject
    public PsqlConfigurationsService(StorageFactory storageFactory, OciContext ociContext) {
        this(storageFactory.create("psql", "psql-configurations.json",
                new TypeReference<Map<String, StoredConfiguration>>() {}), ociContext, Clock.systemUTC());
    }

    PsqlConfigurationsService(StorageBackend<String, StoredConfiguration> configurations, OciContext ociContext,
                              Clock clock) {
        this.configurations = configurations;
        this.ociContext = ociContext;
        this.clock = clock;
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(configurations, tenancyId, compartmentId,
                StoredConfiguration::getCompartmentId, StoredConfiguration::getLifecycleState,
                configuration -> "configuration " + configuration.getId()).toList();
    }

    public StoredConfiguration createConfiguration(Map<String, Object> details) {
        String displayName = PsqlRequests.requireString(details, "displayName");
        String compartmentId = PsqlRequests.requireString(details, "compartmentId");
        String major = PsqlCatalog.major(PsqlRequests.requireString(details, "dbVersion"));
        Map<String, String> overrides = overrides(PsqlRequests.requireMap(details, "dbConfigurationOverrides"));

        // A configuration with no shape, or shape "PostgreSQL...", fits every shape and reports
        // "PostgreSQL" (the SDK's multi-shape case); a flexible one for a named shape keeps that
        // shape with sizes of 0; a fixed one has a shape and a size.
        String requestedShape = PsqlRequests.string(details, "shape");
        boolean multiShape = requestedShape == null || requestedShape.equals(FLEXIBLE_SHAPE)
                || requestedShape.startsWith(FLEXIBLE_SHAPE + ".X86");
        boolean flexible = multiShape || Boolean.TRUE.equals(PsqlRequests.bool(details, "isFlexible"));
        String shape;
        int ocpuCount;
        int memorySizeInGBs;
        List<String> compatibleShapes = PsqlRequests.stringList(details, "compatibleShapes");
        if (multiShape) {
            shape = FLEXIBLE_SHAPE;
            ocpuCount = 0;
            memorySizeInGBs = 0;
        } else if (flexible) {
            shape = PsqlCatalog.parseShape(requestedShape).name();
            ocpuCount = 0;
            memorySizeInGBs = 0;
        } else {
            PsqlCatalog.Shape parsed = PsqlCatalog.parseShape(requestedShape);
            shape = parsed.name();
            ocpuCount = firstNonNull(parsed.ocpuCount(), PsqlRequests.integer(details, "instanceOcpuCount"));
            memorySizeInGBs = firstNonNull(parsed.memorySizeInGBs(),
                    PsqlRequests.integer(details, "instanceMemorySizeInGBs"));
        }
        for (String compatible : compatibleShapes != null ? compatibleShapes : List.<String>of()) {
            PsqlCatalog.parseShape(compatible);
        }

        synchronized (this) {
            long live = configurations.scan(k -> true).stream()
                    .filter(c -> !"DELETED".equals(c.getLifecycleState()))
                    .count();
            if (live >= LIMIT) {
                throw OciException.limitExceeded("The tenancy already has " + LIMIT
                        + " PostgreSQL configurations in this region, the service limit");
            }
            String now = clock.instant().toString();
            StoredConfiguration configuration = new StoredConfiguration();
            configuration.setId(Ocids.generate("postgresqlconfiguration", ociContext.realm(),
                    ociContext.regionCode()));
            configuration.setDisplayName(displayName);
            configuration.setDescription(PsqlRequests.string(details, "description"));
            configuration.setCompartmentId(compartmentId);
            configuration.setTimeCreated(now);
            configuration.setLifecycleState("ACTIVE");
            configuration.setDbVersion(major);
            configuration.setShape(shape);
            configuration.setInstanceOcpuCount(ocpuCount);
            configuration.setInstanceMemorySizeInGBs(memorySizeInGBs);
            configuration.setIsFlexible(flexible);
            configuration.setCompatibleShapes(compatibleShapes != null ? compatibleShapes
                    : multiShape ? PsqlCatalog.SHAPES : List.of(shape));
            configuration.setDefaultConfigId(PsqlCatalog.defaultConfigurationId(major, null, ociContext.realm(),
                    ociContext.regionCode()));
            configuration.setOverrides(overrides);
            configuration.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
            configuration.setDefinedTags(PsqlRequests.definedTags(details));
            configuration.setEtag(Etags.newEtag());
            configurations.put(configuration.getId(), configuration);
            LOG.infov("createConfiguration {0} ({1}), PostgreSQL {2}, {3} overrides",
                    configuration.getId(), displayName, major, overrides.size());
            return configuration;
        }
    }

    public StoredConfiguration getConfiguration(String configurationId) {
        return find(configurationId).orElseThrow(() -> notFound(configurationId));
    }

    /** The configuration in the caller's tenancy and region, whatever its state. */
    public Optional<StoredConfiguration> find(String configurationId) {
        return configurations.get(configurationId);
    }

    public List<StoredConfiguration> listConfigurations(String compartmentId, String lifecycleState, String configType,
                                                        String displayName, String dbVersion, String shape,
                                                        String configurationId, Integer instanceOcpuCount,
                                                        Integer instanceMemorySizeInGBs, String sortBy,
                                                        String sortOrder) {
        PsqlRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        PsqlRequests.requireEnum(configType, CONFIG_TYPES, "configType");
        PsqlRequests.requireEnum(sortBy, SORT_BY, "sortBy");
        PsqlRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        if ("DEFAULT".equals(configType) || "COPIED".equals(configType)) {
            return List.of();
        }
        boolean byName = "displayName".equals(sortBy);
        Comparator<StoredConfiguration> order = byName
                ? Comparator.comparing(StoredConfiguration::getDisplayName)
                : Comparator.comparing(StoredConfiguration::getTimeCreated);
        order = order.thenComparing(StoredConfiguration::getId);
        String direction = sortOrder != null ? sortOrder : (byName ? "ASC" : "DESC");
        if ("DESC".equals(direction)) {
            order = order.reversed();
        }
        Predicate<StoredConfiguration> versionMatches = c -> dbVersion == null
                || dbVersion.equals(c.getDbVersion()) || dbVersion.startsWith(c.getDbVersion() + ".");
        return configurations.scan(k -> true).stream()
                .filter(c -> compartmentId == null || compartmentId.equals(c.getCompartmentId()))
                .filter(c -> lifecycleState == null || lifecycleState.equals(c.getLifecycleState()))
                .filter(c -> displayName == null || displayName.equals(c.getDisplayName()))
                .filter(versionMatches)
                .filter(c -> shape == null || shape.equals(c.getShape()))
                .filter(c -> configurationId == null || configurationId.equals(c.getId()))
                .filter(c -> instanceOcpuCount == null || instanceOcpuCount.equals(c.getInstanceOcpuCount()))
                .filter(c -> instanceMemorySizeInGBs == null
                        || instanceMemorySizeInGBs.equals(c.getInstanceMemorySizeInGBs()))
                .sorted(order)
                .toList();
    }

    /** Only the names, description, compatible shapes and tags can change, as in the SDK. */
    public synchronized StoredConfiguration updateConfiguration(String configurationId, Map<String, Object> details,
                                                                String ifMatch) {
        StoredConfiguration configuration = active(configurationId, "updated");
        Etags.checkIfMatch(ifMatch, configuration.getEtag());
        List<String> compatibleShapes = PsqlRequests.stringList(details, "compatibleShapes");
        for (String compatible : compatibleShapes != null ? compatibleShapes : List.<String>of()) {
            PsqlCatalog.parseShape(compatible);
        }
        if (PsqlRequests.string(details, "displayName") != null) {
            configuration.setDisplayName(PsqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            configuration.setDescription(PsqlRequests.string(details, "description"));
        }
        if (compatibleShapes != null) {
            configuration.setCompatibleShapes(compatibleShapes);
        }
        if (details.containsKey("freeformTags")) {
            configuration.setFreeformTags(PsqlRequests.stringMap(details, "freeformTags"));
        }
        if (details.containsKey("definedTags")) {
            configuration.setDefinedTags(PsqlRequests.definedTags(details));
        }
        touch(configuration);
        return configuration;
    }

    /**
     * Deletes a configuration no live DB system uses. The record stays readable as
     * {@code DELETED}, which Terraform and the CLI poll for.
     *
     * @param inUse whether a DB system that is not DELETED uses the configuration
     */
    public synchronized void deleteConfiguration(String configurationId, String ifMatch, Predicate<String> inUse) {
        StoredConfiguration configuration = active(configurationId, "deleted");
        Etags.checkIfMatch(ifMatch, configuration.getEtag());
        if (inUse.test(configurationId)) {
            throw OciException.conflict("Configuration " + configurationId + " is used by a DB system");
        }
        configuration.setLifecycleState("DELETED");
        touch(configuration);
        LOG.infov("deleteConfiguration {0}", configurationId);
    }

    /**
     * Runs {@code attach}, which checks a configuration and records a DB system using it, under
     * the lock {@link #deleteConfiguration} holds, so a delete sees either the finished DB system
     * or none, and the check sees the configuration as the delete left it.
     */
    public synchronized <T> T attachingConfiguration(Supplier<T> attach) {
        return attach.get();
    }

    public synchronized void changeCompartment(String configurationId, String compartmentId, String ifMatch) {
        StoredConfiguration configuration = active(configurationId, "moved");
        Etags.checkIfMatch(ifMatch, configuration.getEtag());
        configuration.setCompartmentId(compartmentId);
        touch(configuration);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * {@code dbConfigurationOverrides.items}: each {@code configKey} must be a setting the catalog
     * lets a configuration override, given once.
     */
    private static Map<String, String> overrides(Map<String, Object> collection) {
        List<Map<String, Object>> items = PsqlRequests.mapList(collection, "items");
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = PsqlRequests.requireString(item, "configKey");
            String value = PsqlRequests.requireString(item, "overridenConfigValue");
            if (PsqlCatalog.parameter(key).isEmpty()) {
                throw OciException.invalidParameter("Configuration parameter " + key + " cannot be overridden");
            }
            if (overrides.put(key, value) != null) {
                throw OciException.invalidParameter("Configuration parameter " + key + " is given twice");
            }
        }
        return overrides;
    }

    private StoredConfiguration active(String configurationId, String action) {
        StoredConfiguration configuration = getConfiguration(configurationId);
        if (!"ACTIVE".equals(configuration.getLifecycleState())) {
            throw OciException.conflict("Configuration " + configurationId + " is "
                    + configuration.getLifecycleState() + " and cannot be " + action);
        }
        return configuration;
    }

    private void touch(StoredConfiguration configuration) {
        configuration.setTimeUpdated(clock.instant().toString());
        configuration.setEtag(Etags.newEtag());
        configurations.put(configuration.getId(), configuration);
    }

    private static int firstNonNull(Integer first, Integer second) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        throw OciException.missingParameter(
                "A fixed-shape configuration needs instanceOcpuCount and instanceMemorySizeInGBs");
    }

    private static OciException notFound(String configurationId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: configuration " + configurationId);
    }
}
