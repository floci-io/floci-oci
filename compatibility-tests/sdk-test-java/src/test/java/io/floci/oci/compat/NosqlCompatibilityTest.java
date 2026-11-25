package io.floci.oci.compat;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.nosql.NosqlClient;
import com.oracle.bmc.nosql.model.CreateIndexDetails;
import com.oracle.bmc.nosql.model.CreateTableDetails;
import com.oracle.bmc.nosql.model.IndexKey;
import com.oracle.bmc.nosql.model.QueryDetails;
import com.oracle.bmc.nosql.model.TableLimits;
import com.oracle.bmc.nosql.model.UpdateRowDetails;
import com.oracle.bmc.nosql.model.WorkRequest;
import com.oracle.bmc.nosql.model.WorkRequestResource;
import com.oracle.bmc.nosql.requests.CreateIndexRequest;
import com.oracle.bmc.nosql.requests.CreateTableRequest;
import com.oracle.bmc.nosql.requests.DeleteRowRequest;
import com.oracle.bmc.nosql.requests.DeleteTableRequest;
import com.oracle.bmc.nosql.requests.GetIndexRequest;
import com.oracle.bmc.nosql.requests.GetRowRequest;
import com.oracle.bmc.nosql.requests.GetTableRequest;
import com.oracle.bmc.nosql.requests.GetWorkRequestRequest;
import com.oracle.bmc.nosql.requests.ListTablesRequest;
import com.oracle.bmc.nosql.requests.QueryRequest;
import com.oracle.bmc.nosql.requests.UpdateRowRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validates NoSQL tables, indexes, rows and queries against the real oci-java-sdk. */
class NosqlCompatibilityTest {

    private static NosqlClient client;

    @BeforeAll
    static void setUp() {
        client = EmulatorFixture.nosql();
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    /** Mutations answer a work request; its TABLE resource names the table, as Terraform reads it. */
    private static WorkRequestResource tableResource(String workRequestId) {
        WorkRequest workRequest = client.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(workRequestId).build()).getWorkRequest();
        assertThat(workRequest.getStatus().getValue()).isEqualTo("SUCCEEDED");
        assertThat(workRequest.getTimeFinished()).isNotNull();
        WorkRequestResource resource = workRequest.getResources().get(0);
        assertThat(resource.getEntityType()).isEqualTo("TABLE");
        return resource;
    }

    @Test
    void tableRowsAndQueries() {
        String name = "sdk_users_" + System.nanoTime();
        String ddl = "CREATE TABLE " + name + " (id INTEGER, name STRING, info JSON, PRIMARY KEY (id))";
        var created = client.createTable(CreateTableRequest.builder()
                .createTableDetails(CreateTableDetails.builder()
                        .name(name)
                        .compartmentId(TENANCY)
                        .ddlStatement(ddl)
                        .tableLimits(TableLimits.builder()
                                .maxReadUnits(10).maxWriteUnits(10).maxStorageInGBs(1).build())
                        .build())
                .build());
        WorkRequestResource resource = tableResource(created.getOpcWorkRequestId());
        assertThat(resource.getActionType().getValue()).isEqualTo("CREATED");
        String tableId = resource.getIdentifier();

        var table = client.getTable(GetTableRequest.builder().tableNameOrId(tableId).build());
        assertThat(table.getEtag()).isNotBlank();
        assertThat(table.getTable().getDdlStatement()).isEqualTo(ddl);
        assertThat(table.getTable().getLifecycleState().getValue()).isEqualTo("ACTIVE");
        assertThat(table.getTable().getSchema().getPrimaryKey()).containsExactly("id");
        assertThat(table.getTable().getTableLimits().getCapacityMode().getValue()).isEqualTo("PROVISIONED");

        var listed = client.listTables(ListTablesRequest.builder()
                .compartmentId(TENANCY).name(name).build()).getTableCollection();
        assertThat(listed.getItems()).extracting(t -> t.getId()).containsExactly(tableId);

        for (int i = 1; i <= 3; i++) {
            var put = client.updateRow(UpdateRowRequest.builder()
                    .tableNameOrId(name)
                    .updateRowDetails(UpdateRowDetails.builder()
                            .compartmentId(TENANCY)
                            .value(Map.of("id", i, "name", "user" + i, "info", Map.of("tier", i)))
                            .build())
                    .build());
            assertThat(put.getUpdateRowResult().getVersion()).isNotBlank();
        }

        var conflict = client.updateRow(UpdateRowRequest.builder()
                .tableNameOrId(tableId)
                .updateRowDetails(UpdateRowDetails.builder()
                        .value(Map.of("id", 1, "name", "again"))
                        .option(UpdateRowDetails.Option.IfAbsent)
                        .isGetReturnRow(true)
                        .build())
                .build()).getUpdateRowResult();
        assertThat(conflict.getVersion()).isNull();
        assertThat(conflict.getExistingValue()).containsEntry("name", "user1");

        var row = client.getRow(GetRowRequest.builder().tableNameOrId(tableId).key(List.of("id:2")).build());
        assertThat(row.getRow().getValue()).containsEntry("name", "user2");
        assertThat(row.getEtag()).isNotBlank();

        var query = client.query(QueryRequest.builder()
                .queryDetails(QueryDetails.builder()
                        .compartmentId(TENANCY)
                        .statement("SELECT id, name FROM " + name + " t WHERE t.info.tier >= 2 ORDER BY id DESC")
                        .build())
                .build()).getQueryResultCollection();
        assertThat(query.getItems()).extracting(item -> item.get("name")).containsExactly("user3", "user2");

        var deleted = client.deleteRow(DeleteRowRequest.builder().tableNameOrId(tableId).key(List.of("id:3"))
                .build()).getDeleteRowResult();
        assertThat(deleted.getIsSuccess()).isTrue();

        var dropped = client.deleteTable(DeleteTableRequest.builder().tableNameOrId(tableId).build());
        assertThat(tableResource(dropped.getOpcWorkRequestId()).getActionType().getValue()).isEqualTo("DELETED");
    }

    @Test
    void indexesReportTableUpdates() {
        String name = "sdk_idx_" + System.nanoTime();
        var created = client.createTable(CreateTableRequest.builder()
                .createTableDetails(CreateTableDetails.builder()
                        .name(name)
                        .compartmentId(TENANCY)
                        .ddlStatement("CREATE TABLE " + name + " (id INTEGER, info JSON, PRIMARY KEY (id))")
                        .build())
                .build());
        String tableId = tableResource(created.getOpcWorkRequestId()).getIdentifier();

        var index = client.createIndex(CreateIndexRequest.builder()
                .tableNameOrId(tableId)
                .createIndexDetails(CreateIndexDetails.builder()
                        .name("cityIdx")
                        .keys(List.of(IndexKey.builder().columnName("info").jsonPath("city")
                                .jsonFieldType("STRING").build()))
                        .build())
                .build());
        WorkRequestResource resource = tableResource(index.getOpcWorkRequestId());
        assertThat(resource.getActionType().getValue()).isEqualTo("UPDATED");
        assertThat(resource.getIdentifier()).isEqualTo(tableId);

        var read = client.getIndex(GetIndexRequest.builder().tableNameOrId(tableId).indexName("cityIdx").build())
                .getIndex();
        assertThat(read.getTableId()).isEqualTo(tableId);
        assertThat(read.getKeys().get(0).getJsonPath()).isEqualTo("city");

        client.deleteTable(DeleteTableRequest.builder().tableNameOrId(tableId).build());
        assertThatThrownBy(() -> client.getTable(GetTableRequest.builder().tableNameOrId(name)
                .compartmentId(TENANCY).build()))
                .isInstanceOfSatisfying(BmcException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getServiceCode()).isEqualTo("NotAuthorizedOrNotFound");
                });
    }
}
