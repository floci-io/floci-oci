package io.floci.oci.services.nosql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.nosql.model.StoredNosqlRow;
import io.floci.oci.services.nosql.model.StoredNosqlTable;
import io.floci.oci.services.nosql.model.StoredNosqlTable.IndexKey;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class NosqlServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..nosqltestcompartment";
    private static final String TERRAFORM_DDL = "CREATE TABLE IF NOT EXISTS test_table(id INTEGER GENERATED ALWAYS"
            + " AS IDENTITY, name STRING, age STRING, info JSON, guid STRING AS UUID, PRIMARY KEY(SHARD(id)))";
    private static final String USERS_DDL = "CREATE TABLE users (id INTEGER, name STRING, info JSON,"
            + " PRIMARY KEY (id)) USING TTL 2 DAYS";

    private NosqlService service;
    private WorkRequestService workRequests;
    private InMemoryStorage<String, StoredNosqlRow> rows;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        rows = new InMemoryStorage<>();
        clock = new MutableClock();
        service = new NosqlService(new InMemoryStorage<>(), rows, config, workRequests, clock);
    }

    private String createUsers() {
        return service.createTable("users", COMPARTMENT, USERS_DDL, null, null, null, null).tableId();
    }

    private static Map<String, Object> user(int id, String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        return value;
    }

    private NosqlService.PutOutcome put(String table, Map<String, Object> value, String option, String ifMatch) {
        return service.putRow(table, null, value, option, true, null, null, null, ifMatch);
    }

    @Test
    void createTableRecordsTableWorkRequestAndSchema() {
        NosqlService.WorkRequestOutcome outcome =
                service.createTable("test_table", COMPARTMENT, TERRAFORM_DDL, null, false, null, null);
        assertTrue(outcome.tableId().startsWith("ocid1.nosqltable.oc1.iad."));

        StoredWorkRequest wr = workRequests.get("nosql", outcome.workRequestId());
        assertEquals("CREATE_TABLE", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertNotNull(wr.getTimeFinished());
        assertEquals("TABLE", wr.getResources().getFirst().getEntityType());
        assertEquals("CREATED", wr.getResources().getFirst().getActionType());
        assertEquals(outcome.tableId(), wr.getResources().getFirst().getIdentifier());

        StoredNosqlTable table = service.getTable(outcome.tableId(), null);
        assertEquals(TERRAFORM_DDL, table.getDdlStatement());
        assertEquals("ACTIVE", table.getLifecycleState());
        assertEquals("ON_DEMAND", table.getTableLimits().getCapacityMode());
        assertEquals(25, table.getTableLimits().getMaxStorageInGBs());
        assertEquals(5, table.getColumns().size());
        assertEquals("id", table.getIdentity().getColumnName());
        assertTrue(table.getIdentity().isAlways());
        assertTrue(table.getColumns().get(4).isAsUuid());
    }

    @Test
    void createTableIfNotExistsReturnsTheExistingTable() {
        String first = service.createTable("test_table", COMPARTMENT, TERRAFORM_DDL, null, null, null, null).tableId();
        String second = service.createTable("test_table", COMPARTMENT, TERRAFORM_DDL, null, null, null, null).tableId();
        assertEquals(first, second);

        createUsers();
        OciException duplicate = assertThrows(OciException.class, this::createUsers);
        assertEquals(409, duplicate.getHttpStatus());
    }

    @Test
    void createTableRejectsBadDdlAndMismatchedName() {
        OciException syntax = assertThrows(OciException.class, () -> service.createTable("users", COMPARTMENT,
                "CREATE TABLE users (id INTEGER)", null, null, null, null));
        assertEquals("InvalidParameter", syntax.getCode());
        OciException mismatch = assertThrows(OciException.class, () -> service.createTable("people", COMPARTMENT,
                USERS_DDL, null, null, null, null));
        assertEquals("InvalidParameter", mismatch.getCode());
    }

    @Test
    void nameLookupWithoutCompartmentIs400() {
        createUsers();
        OciException error = assertThrows(OciException.class, () -> service.getTable("users", null));
        assertEquals(400, error.getHttpStatus());
        assertEquals("MissingParameter", error.getCode());
        assertEquals("users", service.getTable("USERS", COMPARTMENT).getName());
    }

    @Test
    void ifAbsentAndIfPresent() {
        String table = createUsers();
        NosqlService.PutOutcome created = put(table, user(1, "ana"), "IF_ABSENT", null);
        assertNotNull(created.version());

        NosqlService.PutOutcome again = put(table, user(1, "bo"), "IF_ABSENT", null);
        assertNull(again.version());
        assertEquals(created.version(), again.existingVersion());
        assertEquals("ana", again.existingValue().get("name"));

        NosqlService.PutOutcome missing = put(table, user(2, "cy"), "IF_PRESENT", null);
        assertNull(missing.version());
        assertNull(missing.existingValue());
        assertTrue(service.getRow(table, null, List.of("id:2")).isEmpty());

        NosqlService.PutOutcome updated = put(table, user(1, "dee"), "IF_PRESENT", null);
        assertNotNull(updated.version());
        assertNotEquals(created.version(), updated.version());
        assertEquals("dee", service.getRow(table, null, List.of("id:1")).orElseThrow().value().get("name"));
    }

    @Test
    void versionMismatchIsNotAnError() {
        String table = createUsers();
        String version = put(table, user(1, "ana"), null, null).version();

        NosqlService.PutOutcome stale = put(table, user(1, "bo"), null, "\"AAAAAAAAAAA=\"");
        assertNull(stale.version());
        assertEquals(version, stale.existingVersion());
        assertEquals("ana", stale.existingValue().get("name"));

        assertNotNull(put(table, user(1, "bo"), null, version).version());

        NosqlService.DeleteOutcome staleDelete = service.deleteRow(table, null, List.of("id:1"), true, version);
        assertFalse(staleDelete.success());
        assertEquals("bo", staleDelete.existingValue().get("name"));
        assertTrue(service.deleteRow(table, null, List.of("id:1"), false, null).success());
        assertFalse(service.deleteRow(table, null, List.of("id:1"), false, null).success());
    }

    @Test
    void ttlExpiryHidesThenSweepsRows() {
        String table = createUsers();
        put(table, user(1, "table-default"), null, null);
        service.putRow(table, null, user(2, "one-day"), null, false, 1, null, null, null);
        service.putRow(table, null, user(3, "forever"), null, false, 0, null, null, null);
        Optional<NosqlService.RowRead> row = service.getRow(table, null, List.of("id:2"));
        assertNotNull(row.orElseThrow().expiresAt());

        clock.advance(Duration.ofHours(25));
        assertTrue(service.getRow(table, null, List.of("id:2")).isEmpty());
        assertTrue(service.getRow(table, null, List.of("id:1")).isPresent());
        assertEquals(2, service.query(COMPARTMENT, "SELECT * FROM users", false, null).items().size());

        clock.advance(Duration.ofDays(1));
        assertEquals(2, service.purgeExpired(clock.millis()));
        assertEquals(1, rows.scan(k -> true).size());
        assertTrue(service.getRow(table, null, List.of("id:3")).isPresent());
    }

    @Test
    void identityAndUuidGeneration() {
        String table = service.createTable("test_table", COMPARTMENT, TERRAFORM_DDL, null, null, null, null)
                .tableId();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "ana");
        value.put("guid", UUID.randomUUID().toString());
        NosqlService.PutOutcome first = service.putRow(table, null, value, null, false, null, null, null, null);
        NosqlService.PutOutcome second = service.putRow(table, null, value, null, false, null, null, null, null);
        assertEquals("1", first.generatedValue());
        assertEquals("2", second.generatedValue());
        assertEquals(1, service.getRow(table, null, List.of("id:1")).orElseThrow().value().get("id"));

        Map<String, Object> explicit = new LinkedHashMap<>(value);
        explicit.put("id", 9);
        OciException always = assertThrows(OciException.class,
                () -> service.putRow(table, null, explicit, null, false, null, null, null, null));
        assertEquals("InvalidParameter", always.getCode());

        Map<String, Object> badUuid = new LinkedHashMap<>();
        badUuid.put("guid", "not-a-uuid");
        assertThrows(OciException.class,
                () -> service.putRow(table, null, badUuid, null, false, null, null, null, null));

        String generated = service.createTable("g", COMPARTMENT, "CREATE TABLE g (id STRING AS UUID GENERATED BY"
                + " DEFAULT, v INTEGER, PRIMARY KEY (id))", null, null, null, null).tableId();
        String uuid = service.putRow(generated, null, Map.of("v", 1), null, false, null, null, null, null)
                .generatedValue();
        assertEquals(uuid, UUID.fromString(uuid).toString());
    }

    @Test
    void rowValuesAreValidatedAgainstTheSchema() {
        String table = createUsers();
        assertThrows(OciException.class, () -> put(table, Map.of("id", "one"), null, null));
        assertThrows(OciException.class, () -> put(table, Map.of("name", "no key"), null, null));
        assertThrows(OciException.class, () -> service.putRow(table, null, Map.of("id", 1, "extra", 2), null,
                false, null, null, true, null));
        assertNotNull(put(table, Map.of("id", 1, "extra", 2), null, null).version());
        assertThrows(OciException.class, () -> service.getRow(table, null, List.of("name:x")));
        assertThrows(OciException.class, () -> service.getRow(table, null, List.of("id:abc")));
    }

    @Test
    void collectionMembersAndEnumValuesAreValidatedAgainstTheFullType() {
        String table = service.createTable("typed", COMPARTMENT, "CREATE TABLE typed (id INTEGER,"
                + " scores ARRAY(INTEGER), address RECORD(zip INTEGER, city STRING), color ENUM(red, green),"
                + " labels MAP(STRING), PRIMARY KEY (id))", null, null, null, null).tableId();

        assertTypeRejected(table, "scores", List.of(1, "bad"), "Column scores[1] is INTEGER and cannot hold bad");
        assertTypeRejected(table, "address", Map.of("zip", "bad"),
                "Column address.zip is INTEGER and cannot hold bad");
        assertTypeRejected(table, "address", Map.of("street", "Main"),
                "Column address is RECORD(zip INTEGER, city STRING) and has no field street");
        assertTypeRejected(table, "color", "blue", "Column color is ENUM(red, green) and cannot hold blue");
        assertTypeRejected(table, "labels", Map.of("a", 1), "Column labels.a is STRING and cannot hold 1");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        assertTypeRejected(table, "scores", withNull, "Column scores[0] is INTEGER and cannot hold null");

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", 1);
        value.put("scores", List.of(1, 2L));
        value.put("address", Map.of("ZIP", 10115));
        value.put("color", "green");
        value.put("labels", Map.of("a", "x"));
        assertNotNull(put(table, value, null, null).version());

        Map<String, Object> stored = service.getRow(table, null, List.of("id:1")).orElseThrow().value();
        assertEquals(List.of(1, 2), stored.get("scores"));
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("zip", 10115);
        address.put("city", null);
        assertEquals(address, stored.get("address"));
        assertEquals("green", stored.get("color"));
    }

    @Test
    void recordFieldsWithQuotedNamesCanBeWritten() {
        String table = service.createTable("quoted", COMPARTMENT, "CREATE TABLE quoted (id INTEGER,"
                + " person RECORD(`first-name` STRING, age INTEGER), PRIMARY KEY (id))", null, null, null, null)
                .tableId();
        assertEquals("RECORD(`first-name` STRING, age INTEGER)",
                service.getTable(table, null).getColumns().get(1).getType());

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", 1);
        value.put("person", Map.of("first-name", "Ana", "age", 30));
        assertNotNull(put(table, value, null, null).version());
        assertTypeRejected(table, "person", Map.of("first-name", 7),
                "Column person.first-name is STRING and cannot hold 7");
    }

    private void assertTypeRejected(String table, String column, Object columnValue, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", 1);
        value.put(column, columnValue);
        OciException error = assertThrows(OciException.class, () -> put(table, value, null, null));
        assertEquals(400, error.getHttpStatus());
        assertEquals("InvalidParameter", error.getCode());
        assertEquals(message, error.getMessage());
    }

    @Test
    void alterTableChangesSchemaAndEchoesTheStatement() {
        String table = createUsers();
        put(table, user(1, "ana"), null, null);
        String alter = "ALTER TABLE users (ADD email STRING DEFAULT 'none', DROP info)";
        NosqlService.WorkRequestOutcome outcome =
                service.updateTable(table, null, alter, null, null, null, null);
        assertEquals("UPDATE_TABLE", workRequests.get("nosql", outcome.workRequestId()).getOperationType());

        StoredNosqlTable altered = service.getTable(table, null);
        assertEquals(alter, altered.getDdlStatement());
        assertEquals(List.of("id", "name", "email"), altered.getColumns().stream().map(c -> c.getName()).toList());
        assertEquals("none", service.getRow(table, null, List.of("id:1")).orElseThrow().value().get("email"));

        assertThrows(OciException.class, () -> service.updateTable(table, null, "ALTER TABLE users (DROP id)",
                null, null, null, null));
        assertThrows(OciException.class, () -> service.updateTable(table, null, USERS_DDL, null, null, null,
                null));
    }

    @Test
    void rejectedAlterTableLeavesTheSchemaUnchanged() {
        String table = createUsers();
        String etag = service.getTable(table, null).getEtag();

        OciException error = assertThrows(OciException.class, () -> service.updateTable(table, null,
                "ALTER TABLE users (DROP info, ADD name STRING)", null, null, null, null));
        assertEquals(400, error.getHttpStatus());
        assertEquals("InvalidParameter", error.getCode());

        StoredNosqlTable after = service.getTable(table, null);
        assertEquals(List.of("id", "name", "info"), after.getColumns().stream().map(c -> c.getName()).toList());
        assertEquals(etag, after.getEtag());
        assertEquals(USERS_DDL, after.getDdlStatement());
    }

    @Test
    void droppedColumnValuesDoNotReturnWhenTheColumnIsAddedAgain() {
        String table = createUsers();
        String version = put(table, user(1, "ana"), null, null).version();

        service.updateTable(table, null, "ALTER TABLE users (DROP name)", null, null, null, null);
        assertFalse(rows.scan(k -> true).getFirst().getValue().containsKey("name"));
        service.updateTable(table, null, "ALTER TABLE users (ADD name INTEGER DEFAULT 0)", null, null, null,
                null);

        NosqlService.RowRead row = service.getRow(table, null, List.of("id:1")).orElseThrow();
        assertEquals(0, row.value().get("name"));
        assertEquals(version, row.version());
        assertEquals(0, service.query(COMPARTMENT, "SELECT name FROM users", false, null).items().getFirst()
                .get("name"));
    }

    @Test
    void tableChangesReplaceTheStoredTableSoReadersKeepAStableSnapshot() {
        String table = createUsers();
        StoredNosqlTable read = service.getTable(table, null);
        Map<String, Object> wire = NosqlWire.table(read);

        service.updateTable(table, null, "ALTER TABLE users (ADD email STRING, DROP info)", null, null, null,
                null);
        service.createIndex(table, null, "nameIdx", List.of(new IndexKey("name", null, null)), null);
        service.changeCompartment(table, null, "ocid1.compartment.oc1..other", null);

        assertEquals(List.of("id", "name", "info"), read.getColumns().stream().map(c -> c.getName()).toList());
        assertTrue(read.getIndexes().isEmpty());
        assertEquals(COMPARTMENT, read.getCompartmentId());
        assertEquals(wire, NosqlWire.table(read));

        StoredNosqlTable current = service.getTable(table, null);
        assertNotSame(read, current);
        assertEquals(List.of("id", "name", "email"), current.getColumns().stream().map(c -> c.getName()).toList());
        assertEquals(1, current.getIndexes().size());

        service.deleteIndex(table, null, "nameIdx", null, null);
        assertEquals(1, current.getIndexes().size());
        service.deleteTable(table, null, null, null);
        assertEquals("ACTIVE", current.getLifecycleState());
        assertEquals("DELETED", service.getTable(table, null).getLifecycleState());
    }

    @Test
    void indexesReportUpdateTable() {
        String table = createUsers();
        NosqlService.WorkRequestOutcome created = service.createIndex(table, null, "cityIdx",
                List.of(new IndexKey("info", "address.city", "STRING")), null);
        StoredWorkRequest wr = workRequests.get("nosql", created.workRequestId());
        assertEquals("UPDATE_TABLE", wr.getOperationType());
        assertEquals("TABLE", wr.getResources().getFirst().getEntityType());
        assertEquals("UPDATED", wr.getResources().getFirst().getActionType());
        assertEquals(table, wr.getResources().getFirst().getIdentifier());

        StoredNosqlTable stored = service.getTable(table, null);
        assertEquals("address.city", service.getIndex(stored, "cityIdx").getKeys().getFirst().getJsonPath());
        assertThrows(OciException.class, () -> service.createIndex(table, null, "nameIdx",
                List.of(new IndexKey("name", "a.b", null)), null));
        assertEquals(409, assertThrows(OciException.class, () -> service.createIndex(table, null, "cityIdx",
                List.of(new IndexKey("name", null, null)), false)).getHttpStatus());

        service.deleteIndex(table, null, "cityIdx", null, null);
        assertThrows(OciException.class, () -> service.getIndex(service.getTable(table, null), "cityIdx"));
        assertNotNull(service.deleteIndex(table, null, "cityIdx", true, null).workRequestId());
    }

    @Test
    void deleteTableLeavesATombstoneAndDropsRows() {
        String table = createUsers();
        put(table, user(1, "ana"), null, null);
        NosqlService.WorkRequestOutcome deleted = service.deleteTable(table, null, null, null);
        assertEquals("DELETED", workRequests.get("nosql", deleted.workRequestId()).getResources().getFirst()
                .getActionType());

        assertEquals("DELETED", service.getTable(table, null).getLifecycleState());
        assertThrows(OciException.class, () -> service.getTable("users", COMPARTMENT));
        assertTrue(rows.scan(k -> true).isEmpty());
        assertTrue(service.listTables(COMPARTMENT, null, null, null, null).isEmpty());
        assertEquals(1, service.listTables(COMPARTMENT, null, "ALL", null, null).size());
        assertNotEquals(table, createUsers());
    }

    @Test
    void queryPrepareAndSummarize() {
        String table = createUsers();
        for (int i = 1; i <= 5; i++) {
            put(table, user(i, "user" + i), null, null);
        }
        NosqlService.QueryOutcome outcome = service.query(COMPARTMENT,
                "SELECT id, name FROM users WHERE id > 2 ORDER BY id DESC", false, null);
        assertEquals(List.of(5, 4, 3), outcome.items().stream().map(r -> r.get("id")).toList());

        String prepared = service.prepare(COMPARTMENT, "DECLARE $id INTEGER; SELECT * FROM users WHERE id = $id");
        assertEquals("user4", service.query(COMPARTMENT, prepared, true, Map.of("$id", 4)).items().getFirst()
                .get("name"));

        assertThrows(OciException.class, () -> service.query(COMPARTMENT, "SELECT * FROM nope", false, null));
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.query(COMPARTMENT, "DELETE FROM users WHERE id = 1", false, null)).getCode());

        assertEquals(Map.of("operation", "SELECT", "tableName", "users"),
                service.summarize("SELECT * FROM users"));
        assertEquals("CREATE_INDEX", service.summarize("CREATE INDEX i ON users (name)").get("operation"));
        assertEquals("UPDATE", service.summarize("UPDATE users SET name = 'x' WHERE id = 1").get("operation"));
        assertTrue(service.summarize("SELECT * FORM users").containsKey("syntaxError"));
    }

    @Test
    void tablesCountAsCompartmentUsage() {
        createUsers();
        assertEquals(1, service.resourcesIn("ocid1.tenancy.oc1..test", COMPARTMENT).size());
    }
}
