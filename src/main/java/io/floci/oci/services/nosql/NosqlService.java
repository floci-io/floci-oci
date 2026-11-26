package io.floci.oci.services.nosql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.AlterTable;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.ColumnDef;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.ColumnType;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.CreateIndex;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.CreateTable;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.DdlStatement;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.DropIndex;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.DropTable;
import io.floci.oci.services.nosql.ddl.NosqlDdlParser.RecordField;
import io.floci.oci.services.nosql.model.StoredNosqlRow;
import io.floci.oci.services.nosql.model.StoredNosqlTable;
import io.floci.oci.services.nosql.model.StoredNosqlTable.Column;
import io.floci.oci.services.nosql.model.StoredNosqlTable.Index;
import io.floci.oci.services.nosql.model.StoredNosqlTable.IndexKey;
import io.floci.oci.services.nosql.query.NosqlQueryEvaluator;
import io.floci.oci.services.nosql.query.NosqlQueryParser;
import io.floci.oci.services.nosql.query.NosqlQueryParser.SelectQuery;
import io.floci.oci.services.nosql.query.NosqlSyntaxException;
import io.floci.oci.services.nosql.query.NosqlTokenizer.Token;
import io.floci.oci.services.nosql.query.NosqlTokens;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * NoSQL Database Cloud Service, API {@code 20190828}: tables created from DDL, their indexes,
 * single-row puts, gets and deletes, and a SELECT subset over the rows. Every table mutation
 * completes synchronously and records a SUCCEEDED work request whose resource is
 * {@code TABLE}/{@code CREATED|UPDATED|DELETED}/the table OCID, which is what Terraform waits on.
 * Index changes are table updates on the wire: the work request operation enum has no index
 * operation.
 */
@ApplicationScoped
public class NosqlService implements CompartmentUsage, Resettable {

    private static final Logger LOG = Logger.getLogger(NosqlService.class);

    static final String WR_SERVICE = "nosql";
    static final String API = "/20190828";
    static final String ENTITY_TABLE = "TABLE";
    private static final int ON_DEMAND_STORAGE_GB = 25;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,255}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, StoredNosqlTable> tables;
    private final StorageBackend<String, StoredNosqlRow> rows;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final Clock clock;

    @Inject
    public NosqlService(StorageFactory storageFactory, EmulatorConfig config, ServiceRegistry serviceRegistry,
                        WorkRequestService workRequests, OciContext ociContext, Clock clock) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
        this.clock = clock;
        this.tables = storageFactory.create("nosql", "nosql-tables.json",
                new TypeReference<Map<String, StoredNosqlTable>>() {});
        this.rows = storageFactory.create("nosql", "nosql-rows.json",
                new TypeReference<Map<String, StoredNosqlRow>>() {});
    }

    NosqlService(StorageBackend<String, StoredNosqlTable> tables, StorageBackend<String, StoredNosqlRow> rows,
                 EmulatorConfig config, WorkRequestService workRequests, Clock clock) {
        this.tables = tables;
        this.rows = rows;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
        this.ociContext = OciContext.fromConfig(config);
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("nosql")
                .enabled(config.services().nosql().enabled())
                .storageKey("nosql")
                .resourceClasses(NosqlController.class, NosqlRowsController.class)
                .build());
    }

    StorageBackend<String, StoredNosqlRow> rowStore() {
        return rows;
    }

    public record WorkRequestOutcome(String workRequestId, String tableId) {
    }

    // ── Tables ─────────────────────────────────────────────────────────────────

    public synchronized WorkRequestOutcome createTable(String name, String compartmentId, String ddlStatement,
                                                       StoredNosqlTable.Limits limits, Boolean autoReclaimable,
                                                       Map<String, String> freeformTags,
                                                       Map<String, Map<String, Object>> definedTags) {
        require(name, "name");
        require(compartmentId, "compartmentId");
        require(ddlStatement, "ddlStatement");
        if (!(parseDdl(ddlStatement) instanceof CreateTable create)) {
            throw OciException.invalidParameter("ddlStatement must be a CREATE TABLE statement");
        }
        if (!create.tableName().equalsIgnoreCase(name)) {
            throw OciException.invalidParameter("The table name " + create.tableName()
                    + " in ddlStatement does not match name " + name);
        }
        if (!TABLE_NAME.matcher(name).matches()) {
            throw OciException.invalidParameter("Invalid table name " + name + ": names start with a letter and"
                    + " hold only letters, digits and underscores; child tables are not supported");
        }
        Optional<StoredNosqlTable> existing = findByName(compartmentId, name);
        if (existing.isPresent()) {
            if (create.ifNotExists()) {
                return outcome("CREATE_TABLE", existing.get(), "CREATED");
            }
            throw new OciException("NotAuthorizedOrResourceAlreadyExists",
                    "Table " + name + " already exists in compartment " + compartmentId, 409);
        }
        StoredNosqlTable table = new StoredNosqlTable();
        table.setId(Ocids.generate("nosqltable", ociContext.realm(), ociContext.regionCode()));
        table.setCompartmentId(compartmentId);
        table.setName(name);
        String now = clock.instant().toString();
        table.setTimeCreated(now);
        table.setTimeUpdated(now);
        table.setLifecycleState("ACTIVE");
        table.setDdlStatement(ddlStatement);
        table.setAutoReclaimable(Boolean.TRUE.equals(autoReclaimable));
        table.setTableLimits(limits != null ? limits
                : new StoredNosqlTable.Limits(0, 0, ON_DEMAND_STORAGE_GB, "ON_DEMAND"));
        for (ColumnDef def : create.columns()) {
            Column column = column(def);
            table.getColumns().add(column);
            if (def.identity() != null) {
                StoredNosqlTable.Identity identity = new StoredNosqlTable.Identity();
                identity.setColumnName(def.name());
                identity.setAlways(def.identity().always());
                identity.setOnNull(def.identity().onNull());
                identity.setStart(def.identity().start());
                identity.setIncrement(def.identity().increment());
                table.setIdentity(identity);
                table.setIdentityNext(def.identity().start());
            }
        }
        table.setPrimaryKey(new ArrayList<>(create.primaryKey()));
        table.setShardKey(new ArrayList<>(create.shardKey()));
        if (create.ttl() != null) {
            table.setTtlValue(create.ttl().value());
            table.setTtlUnit(create.ttl().unit());
        }
        table.setFreeformTags(freeformTags);
        table.setDefinedTags(definedTags);
        table.setEtag(Etags.newEtag());
        tables.put(table.getId(), table);
        LOG.infov("createTable {0} ({1})", name, table.getId());
        return outcome("CREATE_TABLE", table, "CREATED");
    }

    /**
     * Resolves {@code tableNameOrId}: an OCID directly (DELETED tombstones included), or a name
     * within {@code compartmentId}, which a name lookup requires.
     */
    public StoredNosqlTable getTable(String tableNameOrId, String compartmentId) {
        require(tableNameOrId, "tableNameOrId");
        if (tableNameOrId.startsWith("ocid1.")) {
            StoredNosqlTable table = tables.get(tableNameOrId).orElseThrow(() -> tableNotFound(tableNameOrId));
            if (compartmentId != null && !compartmentId.equals(table.getCompartmentId())) {
                throw tableNotFound(tableNameOrId);
            }
            return table;
        }
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter(
                    "compartmentId is required when tableNameOrId is a table name: " + tableNameOrId);
        }
        return findByName(compartmentId, tableNameOrId).orElseThrow(() -> tableNotFound(tableNameOrId));
    }

    private StoredNosqlTable liveTable(String tableNameOrId, String compartmentId) {
        StoredNosqlTable table = getTable(tableNameOrId, compartmentId);
        if ("DELETED".equals(table.getLifecycleState())) {
            throw tableNotFound(tableNameOrId);
        }
        return table;
    }

    private Optional<StoredNosqlTable> findByName(String compartmentId, String name) {
        return tables.scan(k -> true).stream()
                .filter(t -> compartmentId.equals(t.getCompartmentId()))
                .filter(t -> t.getName().equalsIgnoreCase(name))
                .filter(t -> !"DELETED".equals(t.getLifecycleState()))
                .findFirst();
    }

    /** DELETED tables are listed only when asked for by state, or with {@code ALL}. */
    public List<StoredNosqlTable> listTables(String compartmentId, String name, String lifecycleState,
                                             String sortBy, String sortOrder) {
        Comparator<StoredNosqlTable> order = "name".equals(sortBy)
                ? Comparator.comparing(t -> t.getName().toLowerCase(Locale.ROOT))
                : Comparator.comparing(StoredNosqlTable::getTimeCreated);
        boolean descending = sortOrder == null ? !"name".equals(sortBy) : "DESC".equalsIgnoreCase(sortOrder);
        if (descending) {
            order = order.reversed();
        }
        return tables.scan(k -> true).stream()
                .filter(t -> compartmentId.equals(t.getCompartmentId()))
                .filter(t -> name == null || t.getName().equalsIgnoreCase(name))
                .filter(t -> stateMatches(t.getLifecycleState(), lifecycleState))
                .sorted(order)
                .toList();
    }

    private static boolean stateMatches(String state, String filter) {
        if (filter == null || filter.isBlank()) {
            return !"DELETED".equals(state);
        }
        return "ALL".equals(filter) || filter.equals(state);
    }

    public synchronized WorkRequestOutcome updateTable(String tableNameOrId, String compartmentId,
                                                       String ddlStatement, StoredNosqlTable.Limits limits,
                                                       Map<String, String> freeformTags,
                                                       Map<String, Map<String, Object>> definedTags,
                                                       String ifMatch) {
        StoredNosqlTable table = copyOf(liveTable(tableNameOrId, compartmentId));
        Etags.checkIfMatch(ifMatch, table.getEtag());
        List<String> droppedColumns = List.of();
        if (ddlStatement != null && !ddlStatement.isBlank()) {
            if (!(parseDdl(ddlStatement) instanceof AlterTable alter)) {
                throw OciException.invalidParameter("UpdateTable takes an ALTER TABLE statement");
            }
            if (!alter.tableName().equalsIgnoreCase(table.getName())) {
                throw OciException.invalidParameter("The table name " + alter.tableName()
                        + " in ddlStatement does not match table " + table.getName());
            }
            droppedColumns = alter(table, alter);
            table.setDdlStatement(ddlStatement);
        }
        if (limits != null) {
            table.setTableLimits(limits);
        }
        if (freeformTags != null) {
            table.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            table.setDefinedTags(definedTags);
        }
        touch(table);
        removeColumnValues(table.getId(), droppedColumns);
        return outcome("UPDATE_TABLE", table, "UPDATED");
    }

    /** Applies {@code alter} to {@code table} and returns the names of the columns it dropped. */
    private static List<String> alter(StoredNosqlTable table, AlterTable alter) {
        List<String> droppedColumns = new ArrayList<>();
        for (String dropped : alter.dropped()) {
            Column column = table.column(dropped);
            if (column == null) {
                throw OciException.invalidParameter("Table " + table.getName() + " has no column " + dropped);
            }
            if (table.getPrimaryKey().stream().anyMatch(k -> k.equalsIgnoreCase(dropped))) {
                throw OciException.invalidParameter("Cannot drop the primary key column " + dropped);
            }
            if (table.getIdentity() != null && table.getIdentity().getColumnName().equalsIgnoreCase(dropped)) {
                table.setIdentity(null);
            }
            boolean indexed = table.getIndexes().stream()
                    .anyMatch(i -> i.getKeys().stream().anyMatch(k -> k.getColumnName().equalsIgnoreCase(dropped)));
            if (indexed) {
                throw OciException.invalidParameter("Cannot drop column " + dropped + ": an index uses it");
            }
            table.getColumns().remove(column);
            droppedColumns.add(column.getName());
        }
        for (ColumnDef added : alter.added()) {
            if (table.column(added.name()) != null) {
                throw OciException.invalidParameter("Table " + table.getName() + " already has a column "
                        + added.name());
            }
            table.getColumns().add(column(added));
        }
        if (alter.ttl() != null) {
            table.setTtlValue(alter.ttl().value());
            table.setTtlUnit(alter.ttl().unit());
        }
        return droppedColumns;
    }

    /**
     * Removes the values of dropped columns from the table's rows, so a column added later
     * under the same name starts from its default. Each changed row is stored as a new object:
     * a query reading the old one is not disturbed.
     */
    private void removeColumnValues(String tableId, List<String> droppedColumns) {
        if (droppedColumns.isEmpty()) {
            return;
        }
        String prefix = tableId + "|";
        for (StoredNosqlRow row : rows.scan(k -> k.startsWith(prefix))) {
            if (droppedColumns.stream().noneMatch(row.getValue()::containsKey)) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>(row.getValue());
            droppedColumns.forEach(value::remove);
            StoredNosqlRow updated = new StoredNosqlRow();
            updated.setTableId(row.getTableId());
            updated.setPrimaryKey(row.getPrimaryKey());
            updated.setValue(value);
            updated.setVersion(row.getVersion());
            updated.setExpiresAt(row.getExpiresAt());
            updated.setTimeModified(row.getTimeModified());
            rows.put(rowKey(row.getTableId(), row.getPrimaryKey()), updated);
        }
    }

    public synchronized WorkRequestOutcome deleteTable(String tableNameOrId, String compartmentId,
                                                       Boolean isIfExists, String ifMatch) {
        StoredNosqlTable table;
        try {
            table = copyOf(liveTable(tableNameOrId, compartmentId));
        } catch (OciException e) {
            if (Boolean.TRUE.equals(isIfExists) && e.getHttpStatus() == 404) {
                String wr = workRequests.succeeded(WR_SERVICE, "DELETE_TABLE", compartmentId, List.of());
                return new WorkRequestOutcome(wr, null);
            }
            throw e;
        }
        Etags.checkIfMatch(ifMatch, table.getEtag());
        deleteRowsOf(table.getId());
        table.setLifecycleState("DELETED");
        table.setIndexes(new ArrayList<>());
        touch(table);
        LOG.infov("deleteTable {0} ({1})", table.getName(), table.getId());
        return outcome("DELETE_TABLE", table, "DELETED");
    }

    /**
     * ChangeTableCompartment. The work request operation enum has no move operation, so the
     * move reports UPDATE_TABLE.
     */
    public synchronized WorkRequestOutcome changeCompartment(String tableNameOrId, String fromCompartmentId,
                                                             String toCompartmentId, String ifMatch) {
        require(toCompartmentId, "toCompartmentId");
        StoredNosqlTable table = copyOf(liveTable(tableNameOrId, fromCompartmentId));
        Etags.checkIfMatch(ifMatch, table.getEtag());
        if (!toCompartmentId.equals(table.getCompartmentId())
                && findByName(toCompartmentId, table.getName()).isPresent()) {
            throw new OciException("NotAuthorizedOrResourceAlreadyExists", "Table " + table.getName()
                    + " already exists in compartment " + toCompartmentId, 409);
        }
        table.setCompartmentId(toCompartmentId);
        touch(table);
        return outcome("UPDATE_TABLE", table, "UPDATED");
    }

    // ── Indexes ────────────────────────────────────────────────────────────────

    public synchronized WorkRequestOutcome createIndex(String tableNameOrId, String compartmentId, String name,
                                                       List<IndexKey> keys, Boolean isIfNotExists) {
        require(name, "name");
        if (keys == null || keys.isEmpty()) {
            throw OciException.missingParameter("keys is required");
        }
        StoredNosqlTable table = copyOf(liveTable(tableNameOrId, compartmentId));
        if (table.index(name) != null) {
            if (Boolean.TRUE.equals(isIfNotExists)) {
                return outcome("UPDATE_TABLE", table, "UPDATED");
            }
            throw new OciException("NotAuthorizedOrResourceAlreadyExists",
                    "Index " + name + " already exists on table " + table.getName(), 409);
        }
        List<IndexKey> resolved = new ArrayList<>();
        for (IndexKey key : keys) {
            if (key.getColumnName() == null || key.getColumnName().isBlank()) {
                throw OciException.missingParameter("keys[].columnName is required");
            }
            Column column = table.column(key.getColumnName());
            if (column == null) {
                throw OciException.invalidParameter("Table " + table.getName() + " has no column "
                        + key.getColumnName());
            }
            if (key.getJsonPath() != null && !"JSON".equals(column.getBaseType())) {
                throw OciException.invalidParameter("jsonPath is only valid on a JSON column; "
                        + column.getName() + " is " + column.getType());
            }
            resolved.add(new IndexKey(key.getColumnName(), key.getJsonPath(), key.getJsonFieldType()));
        }
        Index index = new Index();
        index.setName(name);
        index.setKeys(resolved);
        index.setLifecycleState("ACTIVE");
        index.setTimeCreated(clock.instant().toString());
        index.setEtag(Etags.newEtag());
        table.getIndexes().add(index);
        touch(table);
        return outcome("UPDATE_TABLE", table, "UPDATED");
    }

    public Index getIndex(StoredNosqlTable table, String indexName) {
        Index index = table.index(indexName);
        if (index == null || "DELETED".equals(table.getLifecycleState())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: index " + indexName);
        }
        return index;
    }

    public List<Index> listIndexes(StoredNosqlTable table, String name, String lifecycleState, String sortBy,
                                   String sortOrder) {
        Comparator<Index> order = "timeCreated".equals(sortBy)
                ? Comparator.comparing(Index::getTimeCreated)
                : Comparator.comparing(i -> i.getName().toLowerCase(Locale.ROOT));
        if ("DESC".equalsIgnoreCase(sortOrder)) {
            order = order.reversed();
        }
        return table.getIndexes().stream()
                .filter(i -> name == null || i.getName().equalsIgnoreCase(name))
                .filter(i -> lifecycleState == null || "ALL".equals(lifecycleState)
                        || lifecycleState.equals(i.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public synchronized WorkRequestOutcome deleteIndex(String tableNameOrId, String compartmentId,
                                                       String indexName, Boolean isIfExists, String ifMatch) {
        StoredNosqlTable table = copyOf(liveTable(tableNameOrId, compartmentId));
        Index index = table.index(indexName);
        if (index == null) {
            if (Boolean.TRUE.equals(isIfExists)) {
                return outcome("UPDATE_TABLE", table, "UPDATED");
            }
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: index " + indexName);
        }
        Etags.checkIfMatch(ifMatch, index.getEtag());
        table.getIndexes().remove(index);
        touch(table);
        return outcome("UPDATE_TABLE", table, "UPDATED");
    }

    // ── Rows ───────────────────────────────────────────────────────────────────

    /** UpdateRowResult: a failed condition is a 200 with a null {@code version}, not an error. */
    public record PutOutcome(String version, String existingVersion, Map<String, Object> existingValue,
                             String generatedValue) {
    }

    public synchronized PutOutcome putRow(String tableNameOrId, String compartmentId, Map<String, Object> value,
                                          String option, Boolean isGetReturnRow, Integer ttlDays,
                                          Boolean isTtlUseTableDefault, Boolean isExactMatch, String ifMatch) {
        StoredNosqlTable table = liveTable(tableNameOrId, compartmentId);
        if (value == null) {
            throw OciException.missingParameter("value is required");
        }
        if (option != null && !"IF_ABSENT".equals(option) && !"IF_PRESENT".equals(option)) {
            throw OciException.invalidParameter("option must be IF_ABSENT or IF_PRESENT, got " + option);
        }
        if (ttlDays != null && ttlDays < 0) {
            throw OciException.invalidParameter("ttl must not be negative");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Column column = table.column(entry.getKey());
            if (column == null) {
                if (Boolean.TRUE.equals(isExactMatch)) {
                    throw OciException.invalidParameter("Table " + table.getName() + " has no column "
                            + entry.getKey());
                }
                continue;
            }
            input.put(column.getName(), entry.getValue());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        String generated = null;
        StoredNosqlTable.Identity identity = table.getIdentity();
        for (Column column : table.getColumns()) {
            String name = column.getName();
            boolean present = input.containsKey(name);
            Object raw = input.get(name);
            if (identity != null && identity.getColumnName().equalsIgnoreCase(name)) {
                if (identity.isAlways() && present) {
                    throw OciException.invalidParameter("Column " + name
                            + " is GENERATED ALWAYS AS IDENTITY and cannot be given a value");
                }
                if (!present || (identity.isOnNull() && raw == null)) {
                    long next = table.getIdentityNext();
                    table.setIdentityNext(next + identity.getIncrement());
                    raw = narrowIdentity(column, next);
                    generated = String.valueOf(next);
                    present = true;
                }
            } else if (column.isAsUuid() && column.isUuidGenerated() && (!present || raw == null)) {
                raw = UUID.randomUUID().toString();
                generated = (String) raw;
                present = true;
            }
            if (!present) {
                if (Boolean.TRUE.equals(isExactMatch)) {
                    throw OciException.invalidParameter("value has no field for column " + name
                            + " and isExactMatch is true");
                }
                raw = defaultValue(column);
            }
            if (raw == null) {
                if (!column.isNullable()) {
                    throw OciException.invalidParameter("Column " + name + " may not be null");
                }
                row.put(name, null);
            } else {
                row.put(name, coerce(column, raw));
            }
        }
        String primaryKey = canonicalKey(table, row);
        StoredNosqlRow existing = liveRow(table.getId(), primaryKey);
        boolean returnRow = Boolean.TRUE.equals(isGetReturnRow);
        boolean versionMismatch = ifMatch != null && !ifMatch.isBlank()
                && (existing == null || !unquote(ifMatch).equals(existing.getVersion()));
        boolean optionFailed = ("IF_ABSENT".equals(option) && existing != null)
                || ("IF_PRESENT".equals(option) && existing == null);
        if (versionMismatch || optionFailed) {
            tables.put(table.getId(), table);
            return new PutOutcome(null,
                    returnRow && existing != null ? existing.getVersion() : null,
                    returnRow && existing != null ? project(table, existing) : null, null);
        }
        long now = clock.millis();
        Long expiresAt;
        if (ttlDays != null) {
            expiresAt = ttlDays == 0 ? null : now + ttlDays * DAY_MILLIS;
        } else if (existing == null || Boolean.TRUE.equals(isTtlUseTableDefault)) {
            expiresAt = tableDefaultExpiry(table, now);
        } else {
            expiresAt = existing.getExpiresAt();
        }
        StoredNosqlRow stored = new StoredNosqlRow();
        stored.setTableId(table.getId());
        stored.setPrimaryKey(primaryKey);
        stored.setValue(row);
        stored.setVersion(nextVersion(table));
        stored.setExpiresAt(expiresAt);
        stored.setTimeModified(Instant.ofEpochMilli(now).toString());
        rows.put(rowKey(table.getId(), primaryKey), stored);
        tables.put(table.getId(), table);
        return new PutOutcome(stored.getVersion(), null, null, generated);
    }

    public record RowRead(Map<String, Object> value, String version, Long expiresAt) {
    }

    /** The row with {@code key}, or empty when there is none (or it has expired). */
    public Optional<RowRead> getRow(String tableNameOrId, String compartmentId, List<String> key) {
        StoredNosqlTable table = liveTable(tableNameOrId, compartmentId);
        StoredNosqlRow row = liveRow(table.getId(), canonicalKey(table, parseKey(table, key)));
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new RowRead(project(table, row), row.getVersion(), row.getExpiresAt()));
    }

    public record DeleteOutcome(boolean success, String existingVersion, Map<String, Object> existingValue) {
    }

    public synchronized DeleteOutcome deleteRow(String tableNameOrId, String compartmentId, List<String> key,
                                                Boolean isGetReturnRow, String ifMatch) {
        StoredNosqlTable table = liveTable(tableNameOrId, compartmentId);
        String primaryKey = canonicalKey(table, parseKey(table, key));
        StoredNosqlRow existing = liveRow(table.getId(), primaryKey);
        if (existing == null) {
            return new DeleteOutcome(false, null, null);
        }
        if (ifMatch != null && !ifMatch.isBlank() && !unquote(ifMatch).equals(existing.getVersion())) {
            boolean returnRow = Boolean.TRUE.equals(isGetReturnRow);
            return new DeleteOutcome(false, returnRow ? existing.getVersion() : null,
                    returnRow ? project(table, existing) : null);
        }
        rows.delete(rowKey(table.getId(), primaryKey));
        return new DeleteOutcome(true, null, null);
    }

    /** Deletes every row whose time to live has passed; the TTL sweeper calls it per partition. */
    public synchronized int purgeExpired(long nowMillis) {
        List<StoredNosqlRow> expired = rows.scan(k -> true).stream().filter(r -> r.expiredAt(nowMillis)).toList();
        for (StoredNosqlRow row : expired) {
            rows.delete(rowKey(row.getTableId(), row.getPrimaryKey()));
        }
        if (!expired.isEmpty()) {
            LOG.debugv("Removed {0} expired NoSQL rows", expired.size());
        }
        return expired.size();
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    public record QueryOutcome(List<Map<String, Object>> items, int rowsRead) {
    }

    public QueryOutcome query(String compartmentId, String statement, Boolean isPrepared,
                              Map<String, Object> variables) {
        require(compartmentId, "compartmentId");
        require(statement, "statement");
        String text = Boolean.TRUE.equals(isPrepared) ? decodePrepared(statement) : statement;
        SelectQuery query = parseSelect(text);
        StoredNosqlTable table = liveTable(query.tableName(), compartmentId);
        List<Map<String, Object>> values = liveRows(table);
        try {
            List<Map<String, Object>> items = new NosqlQueryEvaluator(query, variables,
                    table.getColumns().stream().map(Column::getName).toList()).execute(values);
            return new QueryOutcome(items, values.size());
        } catch (NosqlSyntaxException e) {
            throw OciException.invalidParameter(e.getMessage());
        }
    }

    /** PrepareStatement: the prepared form is the statement text, base64-encoded. */
    public String prepare(String compartmentId, String statement) {
        require(compartmentId, "compartmentId");
        require(statement, "statement");
        SelectQuery query = parseSelect(statement);
        liveTable(query.tableName(), compartmentId);
        return Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8));
    }

    /** SummarizeStatement: never fails on bad syntax, which it reports as {@code syntaxError}. */
    public Map<String, Object> summarize(String statement) {
        require(statement, "statement");
        Map<String, Object> summary = new LinkedHashMap<>();
        try {
            NosqlTokens tokens = new NosqlTokens(statement);
            Token first = tokens.peek();
            if (first.is("CREATE") || first.is("ALTER") || first.is("DROP")) {
                summarizeDdl(NosqlDdlParser.parse(statement), summary);
            } else if (first.is("UPDATE")) {
                tokens.next();
                summary.put("operation", "UPDATE");
                summary.put("tableName", tokens.identifier("a table name"));
            } else if (first.is("INSERT") || first.is("UPSERT")) {
                tokens.next();
                tokens.expect("INTO");
                summary.put("operation", "INSERT");
                summary.put("tableName", tokens.identifier("a table name"));
            } else if (first.is("DELETE")) {
                tokens.next();
                tokens.expect("FROM");
                summary.put("operation", "DELETE");
                summary.put("tableName", tokens.identifier("a table name"));
            } else {
                SelectQuery query = NosqlQueryParser.parse(statement);
                summary.put("operation", "SELECT");
                summary.put("tableName", query.tableName());
            }
        } catch (NosqlSyntaxException e) {
            summary.clear();
            summary.put("syntaxError", e.getMessage());
        }
        return summary;
    }

    private static void summarizeDdl(DdlStatement ddl, Map<String, Object> summary) {
        switch (ddl) {
            case CreateTable c -> {
                summary.put("operation", "CREATE_TABLE");
                summary.put("tableName", c.tableName());
                summary.put("isIfNotExists", c.ifNotExists());
            }
            case AlterTable a -> {
                summary.put("operation", "ALTER_TABLE");
                summary.put("tableName", a.tableName());
            }
            case DropTable d -> {
                summary.put("operation", "DROP_TABLE");
                summary.put("tableName", d.tableName());
                summary.put("isIfExists", d.ifExists());
            }
            case CreateIndex c -> {
                summary.put("operation", "CREATE_INDEX");
                summary.put("tableName", c.tableName());
                summary.put("indexName", c.indexName());
                summary.put("isIfNotExists", c.ifNotExists());
            }
            case DropIndex d -> {
                summary.put("operation", "DROP_INDEX");
                summary.put("tableName", d.tableName());
                summary.put("indexName", d.indexName());
                summary.put("isIfExists", d.ifExists());
            }
        }
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    /** DeleteWorkRequest cancels; every emulated work request is already finished. */
    public void cancelWorkRequest(String workRequestId) {
        StoredWorkRequest wr = workRequests.get(WR_SERVICE, workRequestId);
        if (wr.getTimeFinished() != null) {
            throw OciException.conflict("Work request " + workRequestId + " has already finished with status "
                    + wr.getStatus() + " and cannot be canceled");
        }
    }

    // ── CompartmentUsage, Resettable ───────────────────────────────────────────

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(tables, tenancyId, compartmentId, StoredNosqlTable::getCompartmentId,
                StoredNosqlTable::getLifecycleState, t -> "nosql table " + t.getId()).toList();
    }

    @Override
    public synchronized void clear() {
        tables.clear();
        rows.clear();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private WorkRequestOutcome outcome(String operationType, StoredNosqlTable table, String action) {
        String wr = workRequests.succeeded(WR_SERVICE, operationType, table.getCompartmentId(),
                List.of(WorkRequestService.resource(ENTITY_TABLE, action, table.getId(),
                        API + "/tables/" + table.getId())));
        return new WorkRequestOutcome(wr, table.getId());
    }

    /**
     * A deep copy of a stored table. Every schema, index, limit, tag or state change works on a
     * copy and stores it only once every check has passed, so a rejected request leaves the
     * stored table as it was, and a reader outside the lock (GetTable and its wire mapping,
     * GetRow, Query) keeps a table object that no writer changes under it. PutRow advances the
     * {@code identityNext} and {@code versionSequence} counters in place, under the lock: they
     * are scalars that no reader maps to the wire.
     */
    private static StoredNosqlTable copyOf(StoredNosqlTable table) {
        return MAPPER.convertValue(table, StoredNosqlTable.class);
    }

    private void touch(StoredNosqlTable table) {
        table.setTimeUpdated(clock.instant().toString());
        table.setEtag(Etags.newEtag());
        tables.put(table.getId(), table);
    }

    private static DdlStatement parseDdl(String ddl) {
        try {
            return NosqlDdlParser.parse(ddl);
        } catch (NosqlSyntaxException e) {
            throw OciException.invalidParameter("Invalid DDL statement: " + e.getMessage());
        }
    }

    private static SelectQuery parseSelect(String statement) {
        try {
            return NosqlQueryParser.parse(statement);
        } catch (NosqlSyntaxException e) {
            throw OciException.invalidParameter("Invalid query statement: " + e.getMessage());
        }
    }

    private static String decodePrepared(String statement) {
        try {
            return new String(Base64.getDecoder().decode(statement), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter("statement is not a prepared statement: " + e.getMessage());
        }
    }

    private static Column column(ColumnDef def) {
        Column column = new Column();
        column.setName(def.name());
        column.setType(def.type());
        column.setBaseType(def.baseType());
        column.setNullable(def.nullable());
        column.setDefaultValue(def.defaultValue());
        column.setAsUuid(def.asUuid());
        column.setUuidGenerated(def.uuidGenerated());
        if (def.defaultValue() != null) {
            try {
                coerce(column, defaultValue(column));
            } catch (OciException e) {
                throw OciException.invalidParameter("Invalid DEFAULT for column " + def.name() + ": "
                        + e.getMessage());
            }
        }
        return column;
    }

    private static Object defaultValue(Column column) {
        String text = column.getDefaultValue();
        if (text == null) {
            return null;
        }
        try {
            return switch (column.getBaseType()) {
                case "INTEGER" -> Integer.parseInt(text);
                case "LONG" -> Long.parseLong(text);
                case "FLOAT", "DOUBLE" -> Double.parseDouble(text);
                case "NUMBER" -> new BigDecimal(text);
                case "BOOLEAN" -> Boolean.parseBoolean(text);
                default -> text;
            };
        } catch (NumberFormatException e) {
            throw OciException.invalidParameter("DEFAULT " + text + " is not a valid " + column.getType());
        }
    }

    private static Object narrowIdentity(Column column, long value) {
        return switch (column.getBaseType()) {
            case "INTEGER" -> {
                if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                    throw OciException.invalidParameter("Identity column " + column.getName()
                            + " has run out of INTEGER values");
                }
                yield (int) value;
            }
            default -> value;
        };
    }

    /**
     * Validates {@code raw} against the full column type and returns the value to store: ARRAY,
     * MAP and RECORD members are checked against their declared types, and an ENUM value against
     * its symbols.
     */
    static Object coerce(Column column, Object raw) {
        ColumnType type;
        try {
            type = NosqlDdlParser.parseType(column.getType());
        } catch (NosqlSyntaxException e) {
            throw new IllegalStateException("Column " + column.getName() + " has an unreadable type "
                    + column.getType(), e);
        }
        Object value = coerce(column.getName(), type, raw);
        if (column.isAsUuid() && value instanceof String s) {
            try {
                UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw OciException.invalidParameter("Column " + column.getName()
                        + " is a UUID column and " + s + " is not a UUID");
            }
        }
        return value;
    }

    /** {@code path} names the value in messages: the column, then {@code [i]} or {@code .field} steps. */
    private static Object coerce(String path, ColumnType type, Object raw) {
        return switch (type.base()) {
            case "INTEGER" -> {
                long value = integral(path, type, raw);
                if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                    throw typeMismatch(path, type, raw);
                }
                yield (int) value;
            }
            case "LONG" -> integral(path, type, raw);
            case "FLOAT", "DOUBLE" -> {
                if (!(raw instanceof Number n)) {
                    throw typeMismatch(path, type, raw);
                }
                yield n.doubleValue();
            }
            case "NUMBER" -> {
                if (!(raw instanceof Number)) {
                    throw typeMismatch(path, type, raw);
                }
                yield raw;
            }
            case "BOOLEAN" -> {
                if (!(raw instanceof Boolean)) {
                    throw typeMismatch(path, type, raw);
                }
                yield raw;
            }
            case "STRING", "BINARY" -> {
                if (!(raw instanceof String s)) {
                    throw typeMismatch(path, type, raw);
                }
                yield s;
            }
            case "ENUM" -> {
                if (!(raw instanceof String s) || !type.symbols().contains(s)) {
                    throw typeMismatch(path, type, raw);
                }
                yield s;
            }
            case "TIMESTAMP" -> {
                if (!(raw instanceof String s) || !isTimestamp(s)) {
                    throw typeMismatch(path, type, raw);
                }
                yield s;
            }
            case "ARRAY" -> {
                if (!(raw instanceof List<?> list)) {
                    throw typeMismatch(path, type, raw);
                }
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    items.add(member(path + "[" + i + "]", type.element(), list.get(i)));
                }
                yield items;
            }
            case "MAP" -> {
                if (!(raw instanceof Map<?, ?> map)) {
                    throw typeMismatch(path, type, raw);
                }
                Map<String, Object> entries = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    entries.put(key, member(path + "." + key, type.element(), entry.getValue()));
                }
                yield entries;
            }
            case "RECORD" -> {
                if (!(raw instanceof Map<?, ?> map)) {
                    throw typeMismatch(path, type, raw);
                }
                yield record(path, type, map);
            }
            default -> raw;
        };
    }

    /** An ARRAY item or MAP value: only a JSON item type admits null. */
    private static Object member(String path, ColumnType type, Object raw) {
        if (raw == null) {
            if (!"JSON".equals(type.base())) {
                throw typeMismatch(path, type, null);
            }
            return null;
        }
        return coerce(path, type, raw);
    }

    /** A RECORD value has every declared field, in declared order; an absent field is null. */
    private static Map<String, Object> record(String path, ColumnType type, Map<?, ?> raw) {
        Map<String, Object> given = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = String.valueOf(entry.getKey());
            RecordField field = type.fields().stream().filter(f -> f.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> OciException.invalidParameter("Column " + path + " is " + type.text()
                            + " and has no field " + name));
            given.put(field.name(), entry.getValue());
        }
        Map<String, Object> record = new LinkedHashMap<>();
        for (RecordField field : type.fields()) {
            Object value = given.get(field.name());
            record.put(field.name(), value == null ? null : coerce(path + "." + field.name(), field.type(), value));
        }
        return record;
    }

    private static long integral(String path, ColumnType type, Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof BigInteger big && big.bitLength() < 64) {
            return big.longValue();
        }
        if (raw instanceof Number n) {
            BigDecimal decimal = new BigDecimal(n.toString());
            if (decimal.stripTrailingZeros().scale() <= 0) {
                try {
                    return decimal.longValueExact();
                } catch (ArithmeticException e) {
                    throw typeMismatch(path, type, raw);
                }
            }
        }
        throw typeMismatch(path, type, raw);
    }

    private static boolean isTimestamp(String s) {
        try {
            OffsetDateTime.parse(s);
            return true;
        } catch (DateTimeParseException notOffset) {
            LOG.tracev("{0} is not an offset date-time: {1}", s, notOffset.getMessage());
        }
        try {
            LocalDateTime.parse(s);
            return true;
        } catch (DateTimeParseException notLocal) {
            LOG.tracev("{0} is not a local date-time: {1}", s, notLocal.getMessage());
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException notDate) {
            return false;
        }
    }

    private static OciException typeMismatch(Column column, Object raw) {
        return OciException.invalidParameter("Column " + column.getName() + " is " + column.getType()
                + " and cannot hold " + raw);
    }

    private static OciException typeMismatch(String path, ColumnType type, Object raw) {
        return OciException.invalidParameter("Column " + path + " is " + type.text() + " and cannot hold " + raw);
    }

    /** Parses GetRow/DeleteRow {@code key} values, each {@code column:value}, into typed key values. */
    private static Map<String, Object> parseKey(StoredNosqlTable table, List<String> key) {
        if (key == null || key.isEmpty()) {
            throw OciException.missingParameter("key is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String part : key) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                throw OciException.invalidParameter("key must be column:value, got " + part);
            }
            String name = part.substring(0, colon);
            String text = part.substring(colon + 1);
            Column column = table.column(name);
            if (column == null || table.getPrimaryKey().stream().noneMatch(k -> k.equalsIgnoreCase(name))) {
                throw OciException.invalidParameter(name + " is not a primary key column of table "
                        + table.getName());
            }
            values.put(column.getName(), keyValue(column, text));
        }
        return values;
    }

    private static Object keyValue(Column column, String text) {
        try {
            return switch (column.getBaseType()) {
                case "INTEGER" -> Integer.parseInt(text);
                case "LONG" -> Long.parseLong(text);
                case "FLOAT", "DOUBLE" -> Double.parseDouble(text);
                case "NUMBER" -> new BigDecimal(text);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                        throw typeMismatch(column, text);
                    }
                    yield Boolean.parseBoolean(text);
                }
                default -> text;
            };
        } catch (NumberFormatException e) {
            throw typeMismatch(column, text);
        }
    }

    /** The primary key as a JSON array of type-normalised strings, in primary key order. */
    private static String canonicalKey(StoredNosqlTable table, Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (String keyColumn : table.getPrimaryKey()) {
            Object value = values.get(keyColumn);
            if (value == null) {
                throw OciException.invalidParameter("The primary key column " + keyColumn + " has no value");
            }
            Column column = table.column(keyColumn);
            parts.add(switch (column.getBaseType()) {
                case "INTEGER", "LONG" -> Long.toString(((Number) value).longValue());
                case "FLOAT", "DOUBLE" -> Double.toString(((Number) value).doubleValue());
                case "NUMBER" -> new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
                default -> String.valueOf(value);
            });
        }
        try {
            return MAPPER.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode a primary key of table " + table.getName(), e);
        }
    }

    private static String rowKey(String tableId, String primaryKey) {
        return tableId + "|" + primaryKey;
    }

    private StoredNosqlRow liveRow(String tableId, String primaryKey) {
        Optional<StoredNosqlRow> row = rows.get(rowKey(tableId, primaryKey));
        if (row.isEmpty() || row.get().expiredAt(clock.millis())) {
            return null;
        }
        return row.get();
    }

    private List<Map<String, Object>> liveRows(StoredNosqlTable table) {
        String prefix = table.getId() + "|";
        long now = clock.millis();
        return rows.scan(k -> k.startsWith(prefix)).stream()
                .filter(r -> !r.expiredAt(now))
                .map(r -> project(table, r))
                .sorted(primaryKeyOrder(table))
                .toList();
    }

    /** Rows come back in primary key order when a query has no ORDER BY. */
    private static Comparator<Map<String, Object>> primaryKeyOrder(StoredNosqlTable table) {
        return (a, b) -> {
            for (String key : table.getPrimaryKey()) {
                int cmp = NosqlQueryEvaluator.compareValues(a.get(key), b.get(key));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        };
    }

    private void deleteRowsOf(String tableId) {
        String prefix = tableId + "|";
        for (StoredNosqlRow row : rows.scan(k -> k.startsWith(prefix))) {
            rows.delete(rowKey(row.getTableId(), row.getPrimaryKey()));
        }
    }

    /** A row as the current schema sees it: dropped columns hidden, added ones at their default. */
    private static Map<String, Object> project(StoredNosqlTable table, StoredNosqlRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Column column : table.getColumns()) {
            out.put(column.getName(), row.getValue().containsKey(column.getName())
                    ? row.getValue().get(column.getName()) : defaultValue(column));
        }
        return out;
    }

    private static Long tableDefaultExpiry(StoredNosqlTable table, long now) {
        if (table.getTtlValue() == null || table.getTtlValue() <= 0) {
            return null;
        }
        return now + new NosqlDdlParser.Ttl(table.getTtlValue(), table.getTtlUnit()).millis();
    }

    private static String nextVersion(StoredNosqlTable table) {
        long next = table.getVersionSequence() + 1;
        table.setVersionSequence(next);
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(Long.BYTES).putLong(next).array());
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter(name + " is required");
        }
    }

    private static OciException tableNotFound(String tableNameOrId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: table " + tableNameOrId);
    }

    static Integer schemaTtlDays(StoredNosqlTable table) {
        if (table.getTtlValue() == null) {
            return 0;
        }
        return new NosqlDdlParser.Ttl(table.getTtlValue(), table.getTtlUnit()).days();
    }
}
