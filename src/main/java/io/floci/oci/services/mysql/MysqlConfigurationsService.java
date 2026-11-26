package io.floci.oci.services.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.mysql.model.StoredMysqlConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * MySQL configurations: the read-only defaults of the catalog, two per shape, and custom ones a
 * tenancy creates from a parent. Every call is synchronous. Custom configurations are regional,
 * like DB systems; floci-oci stores their options and variables but does not apply them to the server.
 */
@ApplicationScoped
public class MysqlConfigurationsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(MysqlConfigurationsService.class);

    static final Set<String> TYPES = Set.of("DEFAULT", "CUSTOM");
    static final Set<String> LIFECYCLE_STATES = Set.of("ACTIVE", "DELETED");
    static final Set<String> SORT_BY = Set.of("displayName", "shapeName", "timeCreated", "timeUpdated");
    static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    /** When the defaults "were created": a fixed instant, so their bodies never change. */
    private static final String DEFAULTS_CREATED = "2020-01-01T00:00:00Z";
    private static final DateTimeFormatter NAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final StorageBackend<String, StoredMysqlConfiguration> configurations;
    private final OciContext ociContext;
    private final Clock clock;

    @Inject
    public MysqlConfigurationsService(StorageFactory storageFactory, OciContext ociContext) {
        this(storageFactory.create("mysql", "mysql-configurations.json",
                new TypeReference<Map<String, StoredMysqlConfiguration>>() {}), ociContext, Clock.systemUTC());
    }

    MysqlConfigurationsService(StorageBackend<String, StoredMysqlConfiguration> configurations,
                               OciContext ociContext, Clock clock) {
        this.configurations = configurations;
        this.ociContext = ociContext;
        this.clock = clock;
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(configurations, tenancyId, compartmentId,
                StoredMysqlConfiguration::getCompartmentId, StoredMysqlConfiguration::getLifecycleState,
                configuration -> "MySQL configuration " + configuration.getId()).toList();
    }

    // ── Custom configurations ──────────────────────────────────────────────────

    public synchronized StoredMysqlConfiguration createConfiguration(Map<String, Object> details) {
        String compartmentId = MysqlRequests.requireString(details, "compartmentId");
        String shapeName = MysqlCatalog.requireShape(MysqlRequests.requireString(details, "shapeName")).name();
        String parentId = MysqlRequests.string(details, "parentConfigurationId");
        Map<String, Object> variables = new LinkedHashMap<>();
        if (parentId != null) {
            Map<String, Object> parent = configurationWire(parentId);
            if (!shapeName.equals(parent.get("shapeName"))) {
                throw OciException.invalidParameter("Parent configuration " + parentId + " is for shape "
                        + parent.get("shapeName") + ", not " + shapeName);
            }
            variables.putAll(asMap(parent.get("variables")));
        }
        Map<String, Object> requested = MysqlRequests.map(details, "variables");
        if (requested != null) {
            variables.putAll(requested);
        }
        Instant now = clock.instant();
        StoredMysqlConfiguration configuration = new StoredMysqlConfiguration();
        configuration.setId(Ocids.generate("mysqlconfiguration", ociContext.realm(), ociContext.regionCode()));
        String displayName = MysqlRequests.string(details, "displayName");
        configuration.setDisplayName(displayName != null ? displayName : "mysqlconfiguration" + NAME_STAMP.format(now));
        configuration.setDescription(MysqlRequests.string(details, "description"));
        configuration.setCompartmentId(compartmentId);
        configuration.setShapeName(shapeName);
        configuration.setParentConfigurationId(parentId);
        configuration.setOptions(options(details));
        configuration.setVariables(variables);
        configuration.setInitVariables(MysqlRequests.map(details, "initVariables"));
        configuration.setTimeCreated(now.toString());
        configuration.setTimeUpdated(now.toString());
        configuration.setLifecycleState("ACTIVE");
        configuration.setFreeformTags(MysqlRequests.stringMap(details, "freeformTags"));
        configuration.setDefinedTags(MysqlRequests.definedTags(details));
        configuration.setEtag(Etags.newEtag());
        configurations.put(configuration.getId(), configuration);
        LOG.infov("createConfiguration {0} for {1}", configuration.getId(), shapeName);
        return configuration;
    }

    public synchronized StoredMysqlConfiguration updateConfiguration(String configurationId,
                                                                     Map<String, Object> details, String ifMatch) {
        StoredMysqlConfiguration configuration = custom(configurationId);
        Etags.checkIfMatch(ifMatch, configuration.getEtag());
        if (MysqlRequests.string(details, "displayName") != null) {
            configuration.setDisplayName(MysqlRequests.string(details, "displayName"));
        }
        if (details.containsKey("description")) {
            configuration.setDescription(MysqlRequests.string(details, "description"));
        }
        if (MysqlRequests.stringMap(details, "freeformTags") != null) {
            configuration.setFreeformTags(MysqlRequests.stringMap(details, "freeformTags"));
        }
        if (MysqlRequests.definedTags(details) != null) {
            configuration.setDefinedTags(MysqlRequests.definedTags(details));
        }
        touch(configuration);
        return configuration;
    }

    /** {@code inUse} tells whether a DB system that is not deleted still uses the configuration. */
    public synchronized void deleteConfiguration(String configurationId, String ifMatch, Predicate<String> inUse) {
        StoredMysqlConfiguration configuration = custom(configurationId);
        Etags.checkIfMatch(ifMatch, configuration.getEtag());
        if (inUse.test(configurationId)) {
            throw OciException.conflict("Configuration " + configurationId + " is used by a DB system");
        }
        configuration.setLifecycleState("DELETED");
        touch(configuration);
        LOG.infov("deleteConfiguration {0}", configurationId);
    }

    /**
     * Runs {@code attach}, which checks a configuration and records a DB system using it, under the
     * lock {@link #deleteConfiguration} holds, so a delete sees either the finished DB system or
     * none.
     */
    public synchronized <T> T attachingConfiguration(Supplier<T> attach) {
        return attach.get();
    }

    // ── Reads, defaults and custom alike ───────────────────────────────────────

    /** A Configuration body, default or custom; a deleted custom one is still returned, as DELETED. */
    public Map<String, Object> configurationWire(String configurationId) {
        Optional<StoredMysqlConfiguration> stored = configurations.get(configurationId);
        if (stored.isPresent()) {
            return stored.get().toWire();
        }
        return MysqlCatalog.findDefaultConfiguration(configurationId, ociContext.realm(), ociContext.tenancyId(),
                        DEFAULTS_CREATED)
                .orElseThrow(() -> notFound(configurationId));
    }

    /** The etag of a custom configuration; defaults never change, so they have none. */
    public Optional<String> etag(String configurationId) {
        return configurations.get(configurationId).map(StoredMysqlConfiguration::getEtag);
    }

    /**
     * The configuration a DB system of {@code shapeName} may use: it must exist, be active and be
     * for that shape.
     */
    public void requireUsable(String configurationId, String shapeName) {
        Map<String, Object> configuration;
        try {
            configuration = configurationWire(configurationId);
        } catch (OciException e) {
            throw OciException.relatedResourceNotAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: configuration " + configurationId);
        }
        if (!"ACTIVE".equals(configuration.get("lifecycleState"))) {
            throw OciException.relatedResourceNotAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: configuration " + configurationId);
        }
        if (!shapeName.equals(configuration.get("shapeName"))) {
            throw OciException.invalidParameter("Configuration " + configurationId + " is for shape "
                    + configuration.get("shapeName") + ", not " + shapeName);
        }
    }

    /** ListConfigurations: the summaries of the defaults and of the compartment's custom ones. */
    public List<Map<String, Object>> listConfigurations(String compartmentId, String configurationId,
                                                        String lifecycleState, List<String> types, String displayName,
                                                        String shapeName, String sortBy, String sortOrder) {
        MysqlRequests.requireEnum(lifecycleState, LIFECYCLE_STATES, "lifecycleState");
        MysqlRequests.requireEnum(sortBy, SORT_BY, "sortBy");
        MysqlRequests.requireEnum(sortOrder, SORT_ORDERS, "sortOrder");
        for (String type : types) {
            MysqlRequests.requireEnum(type, TYPES, "type");
        }
        List<Map<String, Object>> all = new ArrayList<>();
        if (types.isEmpty() || types.contains("DEFAULT")) {
            all.addAll(MysqlCatalog.defaultConfigurations(ociContext.realm(), ociContext.tenancyId(), DEFAULTS_CREATED));
        }
        if (types.isEmpty() || types.contains("CUSTOM")) {
            configurations.scan(k -> true).stream()
                    .filter(c -> compartmentId.equals(c.getCompartmentId()))
                    .map(StoredMysqlConfiguration::toSummaryWire)
                    .forEach(all::add);
        }
        String field = sortBy != null ? sortBy : "displayName";
        Comparator<Map<String, Object>> order = Comparator.comparing(c -> String.valueOf(c.get(field)));
        order = order.thenComparing(c -> String.valueOf(c.get("id")));
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return all.stream()
                .filter(c -> configurationId == null || configurationId.equals(c.get("id")))
                .filter(c -> lifecycleState == null || lifecycleState.equals(c.get("lifecycleState")))
                .filter(c -> displayName == null || displayName.equals(c.get("displayName")))
                .filter(c -> shapeName == null || shapeName.equals(c.get("shapeName")))
                .map(MysqlConfigurationsService::summary)
                .sorted(order)
                .toList();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** ConfigurationSummary has no options or variables. */
    private static Map<String, Object> summary(Map<String, Object> configuration) {
        Map<String, Object> summary = new LinkedHashMap<>(configuration);
        summary.remove("options");
        summary.remove("variables");
        summary.remove("initVariables");
        summary.remove("parentConfigurationId");
        return summary;
    }

    /**
     * The {@code options} of a CreateConfigurationDetails, as sent: a list of Option bodies, each
     * with a {@code name} and an optional string {@code value}. An empty value is kept, since
     * Terraform reads each option it set back by name and compares the value.
     */
    private static List<Map<String, String>> options(Map<String, Object> details) {
        Object value = details.get("options");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw OciException.invalidParameter("Invalid options: expected a list of options");
        }
        List<Map<String, String>> options = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> option)) {
                throw OciException.invalidParameter("Invalid options: expected a list of options");
            }
            if (!(option.get("name") instanceof String name) || name.isBlank()) {
                throw OciException.invalidParameter("Invalid options: every option needs a name");
            }
            Map<String, String> stored = new LinkedHashMap<>();
            stored.put("name", name);
            Object optionValue = option.get("value");
            if (optionValue instanceof String text) {
                stored.put("value", text);
            } else if (optionValue != null) {
                throw OciException.invalidParameter("Invalid options: the value of option " + name
                        + " must be a string");
            }
            options.add(stored);
        }
        return options;
    }

    private StoredMysqlConfiguration custom(String configurationId) {
        StoredMysqlConfiguration configuration = configurations.get(configurationId)
                .orElseThrow(() -> MysqlCatalog.findDefaultConfiguration(configurationId, ociContext.realm(),
                        ociContext.tenancyId(), DEFAULTS_CREATED).isPresent()
                        ? OciException.invalidParameter("Default configuration " + configurationId
                                + " cannot be changed")
                        : notFound(configurationId));
        if ("DELETED".equals(configuration.getLifecycleState())) {
            throw notFound(configurationId);
        }
        return configuration;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void touch(StoredMysqlConfiguration configuration) {
        configuration.setTimeUpdated(clock.instant().toString());
        configuration.setEtag(Etags.newEtag());
        configurations.put(configuration.getId(), configuration);
    }

    private static OciException notFound(String configurationId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: configuration " + configurationId);
    }
}
