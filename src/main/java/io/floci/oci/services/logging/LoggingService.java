package io.floci.oci.services.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.logging.model.StoredLogEntries;
import io.floci.oci.services.logging.model.StoredLoggingResource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * OCI Logging: log groups, logs and saved searches ({@code /20200531}, oci-go-sdk logging/),
 * ingestion ({@code /20200831}, loggingingestion/) and search ({@code /20190909}, loggingsearch/).
 *
 * <p>Log group and log mutations answer {@code 202} with an {@code opc-work-request-id} and finish at
 * once, so the work request is already {@code SUCCEEDED} when the client polls it; Terraform reads
 * the new resource's OCID from the work request's resources.
 */
@ApplicationScoped
public class LoggingService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(LoggingService.class);

    static final String WR_SERVICE = "logging";
    static final Set<Integer> RETENTION_DAYS = Set.of(30, 60, 90, 120, 150, 180);
    /** Entries kept per log, newest kept: floci-oci's bound, the API states none. */
    static final int MAX_ENTRIES_PER_LOG = 100_000;

    private final StorageBackend<String, StoredLoggingResource> resources;
    private final StorageBackend<String, StoredLogEntries> entries;
    private final WorkRequestService workRequests;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final ObjectMapper mapper;

    @Inject
    public LoggingService(StorageFactory storageFactory, WorkRequestService workRequests, EmulatorConfig config,
                          OciContext ociContext, ServiceRegistry serviceRegistry, ObjectMapper mapper) {
        this(storageFactory.create("logging", "logging-resources.json",
                        new TypeReference<Map<String, StoredLoggingResource>>() {}),
                storageFactory.create("logging", "logging-entries.json",
                        new TypeReference<Map<String, StoredLogEntries>>() {}),
                workRequests, config, ociContext, serviceRegistry, mapper);
    }

    LoggingService(StorageBackend<String, StoredLoggingResource> resources, StorageBackend<String, StoredLogEntries> entries,
                   WorkRequestService workRequests, EmulatorConfig config, OciContext ociContext,
                   ServiceRegistry serviceRegistry, ObjectMapper mapper) {
        this.resources = resources;
        this.entries = entries;
        this.workRequests = workRequests;
        this.config = config;
        this.ociContext = ociContext != null ? ociContext : OciContext.fromConfig(config);
        this.serviceRegistry = serviceRegistry;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("logging")
                .enabled(config.services().logging().enabled())
                .storageKey("logging")
                .resourceClasses(LoggingController.class, LoggingIngestionController.class,
                        LoggingSearchController.class)
                .build());
    }

    String tenancyId() {
        return ociContext.tenancyId();
    }

    // ── Log groups ─────────────────────────────────────────────────────────────

    public synchronized String createLogGroup(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String compartmentId = requireString(b, "compartmentId");
        String displayName = requireString(b, "displayName");
        uniqueName("loggroup", compartmentId, null, displayName, null);
        StoredLoggingResource group = newResource("loggroup", compartmentId);
        group.getSpec().put("displayName", displayName);
        putIfPresent(group.getSpec(), b, "description");
        group.getSpec().put("freeformTags", tags(b.get("freeformTags")));
        group.getSpec().put("definedTags", tags(b.get("definedTags")));
        resources.put(group.getId(), group);
        LOG.infov("createLogGroup {0} ({1})", displayName, group.getId());
        return workRequest("CREATE_LOG_GROUP", compartmentId, "loggroup", "CREATED", group.getId());
    }

    public StoredLoggingResource getLogGroup(String logGroupId) {
        return resource("loggroup", logGroupId);
    }

    public List<StoredLoggingResource> listLogGroups(String compartmentId, Boolean inSubtree, String displayName,
                                                     String sortBy, String sortOrder) {
        boolean all = Boolean.TRUE.equals(inSubtree) && compartmentId.equals(ociContext.tenancyId());
        return sorted(resources.scan(k -> true).stream()
                .filter(r -> "loggroup".equals(r.getKind()))
                .filter(r -> all || compartmentId.equals(r.getCompartmentId()))
                .filter(r -> displayName == null || displayName.equals(r.getSpec().get("displayName"))), sortBy, sortOrder);
    }

    public synchronized String updateLogGroup(String logGroupId, Map<String, Object> body, String ifMatch) {
        StoredLoggingResource group = getLogGroup(logGroupId);
        Etags.checkIfMatch(ifMatch, group.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        String displayName = str(b, "displayName");
        if (displayName != null) {
            uniqueName("loggroup", group.getCompartmentId(), null, displayName, logGroupId);
            group.getSpec().put("displayName", displayName);
        }
        if (b.containsKey("description")) {
            putIfPresent(group.getSpec(), b, "description");
        }
        updateTags(group, b);
        touch(group);
        resources.put(logGroupId, group);
        return workRequest("UPDATE_LOG_GROUP", group.getCompartmentId(), "loggroup", "UPDATED", logGroupId);
    }

    public synchronized String deleteLogGroup(String logGroupId, String ifMatch) {
        StoredLoggingResource group = getLogGroup(logGroupId);
        Etags.checkIfMatch(ifMatch, group.getEtag());
        if (!logsIn(logGroupId).isEmpty()) {
            throw OciException.conflict("Log group " + logGroupId + " still contains logs; delete them first");
        }
        resources.delete(logGroupId);
        return workRequest("DELETE_LOG_GROUP", group.getCompartmentId(), "loggroup", "DELETED", logGroupId);
    }

    public synchronized String changeLogGroupCompartment(String logGroupId, String compartmentId, String ifMatch) {
        StoredLoggingResource group = getLogGroup(logGroupId);
        Etags.checkIfMatch(ifMatch, group.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        uniqueName("loggroup", compartmentId, null, (String) group.getSpec().get("displayName"), logGroupId);
        group.setCompartmentId(compartmentId);
        touch(group);
        resources.put(logGroupId, group);
        for (StoredLoggingResource log : logsIn(logGroupId)) {
            log.setCompartmentId(compartmentId);
            resources.put(log.getId(), log);
        }
        return workRequest("MOVE_LOG_GROUP", compartmentId, "loggroup", "UPDATED", logGroupId);
    }

    // ── Logs ───────────────────────────────────────────────────────────────────

    public synchronized String createLog(String logGroupId, Map<String, Object> body) {
        StoredLoggingResource group = getLogGroup(logGroupId);
        Map<String, Object> b = body == null ? Map.of() : body;
        String displayName = requireString(b, "displayName");
        String logType = requireString(b, "logType");
        if (!Set.of("CUSTOM", "SERVICE").contains(logType)) {
            throw OciException.invalidParameter("logType must be CUSTOM or SERVICE, got " + logType);
        }
        uniqueName("log", null, logGroupId, displayName, null);
        StoredLoggingResource log = newResource("log", group.getCompartmentId());
        log.setLogGroupId(logGroupId);
        Map<String, Object> spec = log.getSpec();
        spec.put("displayName", displayName);
        spec.put("logType", logType);
        spec.put("isEnabled", !(b.get("isEnabled") instanceof Boolean enabled) || enabled);
        spec.put("retentionDuration", retention(b.get("retentionDuration"), 30));
        Map<String, Object> configuration = configuration(b.get("configuration"), logType, group.getCompartmentId());
        if (configuration != null) {
            spec.put("configuration", configuration);
        }
        spec.put("freeformTags", tags(b.get("freeformTags")));
        spec.put("definedTags", tags(b.get("definedTags")));
        resources.put(log.getId(), log);
        LOG.infov("createLog {0} {1} in {2} ({3})", logType, displayName, logGroupId, log.getId());
        return workRequest("CREATE_LOG", group.getCompartmentId(), "log", "CREATED", log.getId());
    }

    public StoredLoggingResource getLog(String logGroupId, String logId) {
        StoredLoggingResource log = resource("log", logId);
        if (!log.getLogGroupId().equals(logGroupId)) {
            throw notFound("log", logId);
        }
        return log;
    }

    public List<StoredLoggingResource> listLogs(String logGroupId, String logType, String sourceService,
                                                String sourceResource, String displayName, String lifecycleState,
                                                String sortBy, String sortOrder) {
        getLogGroup(logGroupId);
        return sorted(logsIn(logGroupId).stream()
                .filter(l -> logType == null || logType.equals(l.getSpec().get("logType")))
                .filter(l -> displayName == null || displayName.equals(l.getSpec().get("displayName")))
                .filter(l -> lifecycleState == null || lifecycleState.equals(l.getLifecycleState()))
                .filter(l -> sourceService == null || sourceService.equals(source(l).get("service")))
                .filter(l -> sourceResource == null || sourceResource.equals(source(l).get("resource"))),
                sortBy, sortOrder);
    }

    public synchronized String updateLog(String logGroupId, String logId, Map<String, Object> body, String ifMatch) {
        StoredLoggingResource log = getLog(logGroupId, logId);
        Etags.checkIfMatch(ifMatch, log.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        String displayName = str(b, "displayName");
        Integer retention = b.containsKey("retentionDuration") ? retention(b.get("retentionDuration"), 30) : null;
        if (displayName != null) {
            uniqueName("log", null, logGroupId, displayName, logId);
            log.getSpec().put("displayName", displayName);
        }
        if (b.get("isEnabled") instanceof Boolean enabled) {
            log.getSpec().put("isEnabled", enabled);
        }
        if (retention != null) {
            log.getSpec().put("retentionDuration", retention);
        }
        if (b.get("configuration") instanceof Map<?, ?> update
                && log.getSpec().get("configuration") instanceof Map<?, ?> current
                && update.get("archiving") instanceof Map<?, ?> archiving) {
            Map<String, Object> merged = new LinkedHashMap<>(cast(current));
            merged.put("archiving", archiving);
            log.getSpec().put("configuration", merged);
        }
        updateTags(log, b);
        touch(log);
        resources.put(logId, log);
        return workRequest("UPDATE_LOG", log.getCompartmentId(), "log", "UPDATED", logId);
    }

    public synchronized String deleteLog(String logGroupId, String logId, String ifMatch) {
        StoredLoggingResource log = getLog(logGroupId, logId);
        Etags.checkIfMatch(ifMatch, log.getEtag());
        resources.delete(logId);
        entries.delete(logId);
        return workRequest("DELETE_LOG", log.getCompartmentId(), "log", "DELETED", logId);
    }

    public synchronized String changeLogLogGroup(String logGroupId, String logId, String targetLogGroupId, String ifMatch) {
        StoredLoggingResource log = getLog(logGroupId, logId);
        Etags.checkIfMatch(ifMatch, log.getEtag());
        if (targetLogGroupId == null) {
            throw OciException.missingParameter("targetLogGroupId is required");
        }
        StoredLoggingResource target = resources.get(targetLogGroupId).filter(r -> "loggroup".equals(r.getKind()))
                .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: log group " + targetLogGroupId));
        uniqueName("log", null, targetLogGroupId, (String) log.getSpec().get("displayName"), logId);
        log.setLogGroupId(targetLogGroupId);
        log.setCompartmentId(target.getCompartmentId());
        touch(log);
        resources.put(logId, log);
        return workRequest("MOVE_LOG", target.getCompartmentId(), "log", "UPDATED", logId);
    }

    // ── Saved searches ─────────────────────────────────────────────────────────

    public synchronized StoredLoggingResource createSavedSearch(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String compartmentId = requireString(b, "compartmentId");
        String name = requireString(b, "name");
        String query = requireString(b, "query");
        LogQuery.parse(query);
        StoredLoggingResource search = newResource("logsavedsearch", compartmentId);
        search.getSpec().put("name", name);
        search.getSpec().put("query", query);
        putIfPresent(search.getSpec(), b, "description");
        search.getSpec().put("freeformTags", tags(b.get("freeformTags")));
        search.getSpec().put("definedTags", tags(b.get("definedTags")));
        resources.put(search.getId(), search);
        return search;
    }

    public StoredLoggingResource getSavedSearch(String id) {
        return resource("logsavedsearch", id);
    }

    public List<StoredLoggingResource> listSavedSearches(String compartmentId, String id, String name, String sortBy,
                                                         String sortOrder) {
        return sorted(resources.scan(k -> true).stream()
                .filter(r -> "logsavedsearch".equals(r.getKind()))
                .filter(r -> compartmentId.equals(r.getCompartmentId()))
                .filter(r -> id == null || id.equals(r.getId()))
                .filter(r -> name == null || name.equals(r.getSpec().get("name"))), sortBy, sortOrder);
    }

    public synchronized StoredLoggingResource updateSavedSearch(String id, Map<String, Object> body, String ifMatch) {
        StoredLoggingResource search = getSavedSearch(id);
        Etags.checkIfMatch(ifMatch, search.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        String query = str(b, "query");
        if (query != null) {
            LogQuery.parse(query);
            search.getSpec().put("query", query);
        }
        if (str(b, "name") != null) {
            search.getSpec().put("name", str(b, "name"));
        }
        if (b.containsKey("description")) {
            putIfPresent(search.getSpec(), b, "description");
        }
        updateTags(search, b);
        touch(search);
        resources.put(id, search);
        return search;
    }

    public synchronized void deleteSavedSearch(String id, String ifMatch) {
        StoredLoggingResource search = getSavedSearch(id);
        Etags.checkIfMatch(ifMatch, search.getEtag());
        resources.delete(id);
    }

    public synchronized void changeSavedSearchCompartment(String id, String compartmentId, String ifMatch) {
        StoredLoggingResource search = getSavedSearch(id);
        Etags.checkIfMatch(ifMatch, search.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        search.setCompartmentId(compartmentId);
        touch(search);
        resources.put(id, search);
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    public StoredWorkRequest getWorkRequest(String id) {
        return workRequests.get(WR_SERVICE, id);
    }

    public List<StoredWorkRequest> listWorkRequests(String compartmentId, String status, String id) {
        return workRequests.list(WR_SERVICE, compartmentId).stream()
                .filter(w -> status == null || status.equals(w.getStatus()))
                .filter(w -> id == null || id.equals(w.getId()))
                .toList();
    }

    /** Cancelling: every floci-oci work request has already finished, so there is nothing to cancel. */
    public void cancelWorkRequest(String id) {
        StoredWorkRequest wr = getWorkRequest(id);
        throw OciException.conflict("Work request " + wr.getId() + " has already finished with status " + wr.getStatus());
    }

    // ── Ingestion ──────────────────────────────────────────────────────────────

    /** PutLogs: appends the batches to a CUSTOM log; a disabled log drops what it receives. */
    public synchronized void putLogs(String logId, Map<String, Object> body) {
        StoredLoggingResource log = resources.get(logId).filter(r -> "log".equals(r.getKind()))
                .orElseThrow(() -> notFound("log", logId));
        Map<String, Object> b = body == null ? Map.of() : body;
        if (!"1.0".equals(b.get("specversion"))) {
            throw OciException.invalidParameter("specversion must be \"1.0\"");
        }
        if (!(b.get("logEntryBatches") instanceof List<?> batches) || batches.isEmpty()) {
            throw OciException.missingParameter("logEntryBatches is required");
        }
        String ingested = Instant.now().toString();
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object rawBatch : batches) {
            Map<String, Object> batch = cast(rawBatch);
            String source = requireString(batch, "source");
            String type = requireString(batch, "type");
            Instant defaultTime = time(requireString(batch, "defaultlogentrytime"), "defaultlogentrytime");
            if (!(batch.get("entries") instanceof List<?> items) || items.isEmpty()) {
                throw OciException.missingParameter("logEntryBatches[].entries is required");
            }
            for (Object rawEntry : items) {
                Map<String, Object> entry = cast(rawEntry);
                String data = requireString(entry, "data");
                String id = requireString(entry, "id");
                Instant at = entry.get("time") instanceof String t ? time(t, "time") : defaultTime;
                Map<String, Object> oracle = new LinkedHashMap<>();
                oracle.put("compartmentid", log.getCompartmentId());
                oracle.put("ingestedtime", ingested);
                oracle.put("loggroupid", log.getLogGroupId());
                oracle.put("logid", logId);
                oracle.put("tenantid", ociContext.tenancyId());
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("data", payload(data));
                envelope.put("id", id);
                envelope.put("oracle", oracle);
                envelope.put("source", source);
                envelope.put("specversion", "1.0");
                if (str(batch, "subject") != null) {
                    envelope.put("subject", str(batch, "subject"));
                }
                envelope.put("time", at.toString());
                envelope.put("type", type);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("datetime", at.toEpochMilli());
                row.put("logContent", envelope);
                accepted.add(row);
            }
        }
        if (!Boolean.TRUE.equals(log.getSpec().get("isEnabled"))) {
            LOG.debugv("Log {0} is disabled; dropped {1} entries", logId, accepted.size());
            return;
        }
        // Copy on write: search reads the stored list without this lock, so it is replaced, never changed.
        List<Map<String, Object>> rows = new ArrayList<>(
                entries.get(logId).map(StoredLogEntries::getEntries).orElse(List.of()));
        rows.addAll(accepted);
        long cutoff = retentionCutoff(log);
        rows.removeIf(e -> ((Number) e.get("datetime")).longValue() < cutoff);
        rows.sort(Comparator.comparingLong(e -> ((Number) e.get("datetime")).longValue()));
        if (rows.size() > MAX_ENTRIES_PER_LOG) {
            rows.subList(0, rows.size() - MAX_ENTRIES_PER_LOG).clear();
        }
        StoredLogEntries stored = new StoredLogEntries();
        stored.setLogId(logId);
        stored.setEntries(rows);
        entries.put(logId, stored);
    }

    /** Entries older than the log's {@code retentionDuration} (days, default 30) are gone. */
    private static long retentionCutoff(StoredLoggingResource log) {
        int retentionDays = log.getSpec().get("retentionDuration") instanceof Number n ? n.intValue() : 30;
        return Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
    }

    /** A JSON object payload is stored as an object so {@code data.<field>} queries reach it; anything else as text. */
    private Object payload(String data) {
        String trimmed = data.trim();
        if (trimmed.startsWith("{")) {
            try {
                return mapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                LOG.debugv("Log entry data is not a JSON object, stored as text: {0}", e.getOriginalMessage());
            }
        }
        return data;
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> search(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Instant start = time(requireString(b, "timeStart"), "timeStart");
        Instant end = time(requireString(b, "timeEnd"), "timeEnd");
        if (!start.isBefore(end)) {
            throw OciException.invalidParameter("timeStart must be before timeEnd");
        }
        LogQuery.Parsed query = LogQuery.parse(requireString(b, "searchQuery"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StoredLoggingResource log : logsInScopes(query.scopes())) {
            // Retention applies on read too, so a log that stopped receiving entries still ages them out.
            long from = Math.max(start.toEpochMilli(), retentionCutoff(log));
            for (Map<String, Object> row : entries.get(log.getId()).map(StoredLogEntries::getEntries).orElse(List.of())) {
                long at = ((Number) row.get("datetime")).longValue();
                if (at >= from && at < end.toEpochMilli()) {
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparingLong(r -> ((Number) r.get("datetime")).longValue()));
        return query.run(rows);
    }

    private List<StoredLoggingResource> logsInScopes(List<LogQuery.Scope> scopes) {
        List<StoredLoggingResource> logs = new ArrayList<>();
        List<StoredLoggingResource> all = resources.scan(k -> true);
        for (LogQuery.Scope scope : scopes) {
            boolean tenancy = scope.compartmentId().equals(ociContext.tenancyId());
            for (StoredLoggingResource log : all) {
                if (!"log".equals(log.getKind()) || logs.contains(log)) {
                    continue;
                }
                if (!tenancy && !scope.compartmentId().equals(log.getCompartmentId())) {
                    continue;
                }
                if (scope.logGroup() != null && !matchesGroup(scope.logGroup(), log.getLogGroupId(), all)) {
                    continue;
                }
                if (scope.log() != null && !scope.log().equals(log.getId())
                        && !scope.log().equals(log.getSpec().get("displayName"))) {
                    continue;
                }
                logs.add(log);
            }
        }
        return logs;
    }

    private static boolean matchesGroup(String nameOrId, String logGroupId, List<StoredLoggingResource> all) {
        if (nameOrId.equals(logGroupId)) {
            return true;
        }
        return all.stream().anyMatch(r -> r.getId().equals(logGroupId) && nameOrId.equals(r.getSpec().get("displayName")));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private StoredLoggingResource newResource(String kind, String compartmentId) {
        StoredLoggingResource resource = new StoredLoggingResource();
        resource.setKind(kind);
        resource.setId(Ocids.generate(kind, ociContext.realm(), ociContext.regionCode()));
        resource.setCompartmentId(compartmentId);
        resource.setLifecycleState("ACTIVE");
        String now = Instant.now().toString();
        resource.setTimeCreated(now);
        resource.setTimeLastModified(now);
        resource.setEtag(Etags.newEtag());
        return resource;
    }

    private StoredLoggingResource resource(String kind, String id) {
        return resources.get(id).filter(r -> kind.equals(r.getKind())).orElseThrow(() -> notFound(kind, id));
    }

    private List<StoredLoggingResource> logsIn(String logGroupId) {
        return resources.scan(k -> true).stream()
                .filter(r -> "log".equals(r.getKind()) && logGroupId.equals(r.getLogGroupId())).toList();
    }

    /** "This must be unique within the enclosing resource" (logging/create_log_group_details.go). */
    private void uniqueName(String kind, String compartmentId, String logGroupId, String displayName, String self) {
        boolean taken = resources.scan(k -> true).stream()
                .filter(r -> kind.equals(r.getKind()) && !r.getId().equals(self))
                .filter(r -> compartmentId == null || compartmentId.equals(r.getCompartmentId()))
                .filter(r -> logGroupId == null || logGroupId.equals(r.getLogGroupId()))
                .anyMatch(r -> displayName.equals(r.getSpec().get("displayName")));
        if (taken) {
            throw OciException.conflict("A " + ("log".equals(kind) ? "log" : "log group") + " named " + displayName
                    + " already exists here");
        }
    }

    private String workRequest(String operation, String compartmentId, String entityType, String action, String id) {
        return workRequests.succeeded(WR_SERVICE, operation, compartmentId,
                List.of(WorkRequestService.resource(entityType, action, id, null)));
    }

    private static List<StoredLoggingResource> sorted(Stream<StoredLoggingResource> stream, String sortBy,
                                                      String sortOrder) {
        Comparator<StoredLoggingResource> order = "displayName".equals(sortBy)
                ? Comparator.comparing(r -> String.valueOf(r.getSpec().getOrDefault("displayName", r.getSpec().get("name"))))
                : Comparator.comparing(StoredLoggingResource::getTimeCreated);
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return stream.sorted(order).toList();
    }

    private static Map<String, Object> source(StoredLoggingResource log) {
        return log.getSpec().get("configuration") instanceof Map<?, ?> configuration
                && configuration.get("source") instanceof Map<?, ?> source ? cast(source) : Map.of();
    }

    /** Configuration: a SERVICE log needs an OCISERVICE source with service, resource and category. */
    private static Map<String, Object> configuration(Object raw, String logType, String compartmentId) {
        if (!(raw instanceof Map<?, ?> given)) {
            if ("SERVICE".equals(logType)) {
                throw OciException.missingParameter("configuration is required for a SERVICE log");
            }
            return null;
        }
        Map<String, Object> source = cast(given.get("source"));
        if (!"OCISERVICE".equals(source.get("sourceType"))) {
            throw OciException.invalidParameter("configuration.source.sourceType must be OCISERVICE");
        }
        for (String field : List.of("service", "resource", "category")) {
            if (str(source, field) == null) {
                throw OciException.missingParameter("configuration.source." + field + " is required");
            }
        }
        Map<String, Object> storedSource = new LinkedHashMap<>();
        storedSource.put("sourceType", "OCISERVICE");
        storedSource.put("service", source.get("service"));
        storedSource.put("resource", source.get("resource"));
        storedSource.put("category", source.get("category"));
        storedSource.put("parameters", source.get("parameters") instanceof Map<?, ?> p ? p : new LinkedHashMap<>());
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("compartmentId", given.get("compartmentId") instanceof String c ? c : compartmentId);
        configuration.put("source", storedSource);
        configuration.put("archiving", given.get("archiving") instanceof Map<?, ?> a ? a : Map.of("isEnabled", false));
        return configuration;
    }

    private static int retention(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n) || !RETENTION_DAYS.contains(n.intValue())) {
            throw OciException.invalidParameter("retentionDuration must be 30, 60, 90, 120, 150 or 180, got " + value);
        }
        return n.intValue();
    }

    private static void updateTags(StoredLoggingResource resource, Map<String, Object> b) {
        if (b.get("freeformTags") instanceof Map<?, ?> tags) {
            resource.getSpec().put("freeformTags", tags);
        }
        if (b.get("definedTags") instanceof Map<?, ?> tags) {
            resource.getSpec().put("definedTags", tags);
        }
    }

    private static void touch(StoredLoggingResource resource) {
        resource.setTimeLastModified(Instant.now().toString());
        resource.setEtag(Etags.newEtag());
    }

    private static Map<String, Object> tags(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>(cast(map)) : new LinkedHashMap<>();
    }

    private static void putIfPresent(Map<String, Object> spec, Map<String, Object> b, String field) {
        String value = str(b, field);
        if (value == null) {
            spec.remove(field);
        } else {
            spec.put(field, value);
        }
    }

    static Instant time(String value, String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw OciException.invalidParameter(field + " must be an RFC 3339 timestamp, got " + value);
        }
    }

    private static OciException notFound(String kind, String id) {
        String what = switch (kind) {
            case "loggroup" -> "log group";
            case "logsavedsearch" -> "saved search";
            default -> kind;
        };
        return OciException.notAuthorizedOrNotFound("Authorization failed or requested resource not found: " + what
                + " " + id);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String requireString(Map<String, Object> b, String key) {
        String value = str(b, key);
        if (value == null) {
            throw OciException.missingParameter(key + " is required");
        }
        return value;
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(resources, tenancyId, compartmentId, StoredLoggingResource::getCompartmentId,
                StoredLoggingResource::getLifecycleState, r -> r.getKind() + " " + r.getId()).toList();
    }
}
