package io.floci.oci.compat;

import com.oracle.bmc.mysql.DbSystemClient;
import com.oracle.bmc.mysql.MysqlaasClient;
import com.oracle.bmc.mysql.WorkRequestsClient;
import com.oracle.bmc.mysql.model.ConfigurationSummary;
import com.oracle.bmc.mysql.model.CreateDbSystemDetails;
import com.oracle.bmc.mysql.model.CreateMaintenanceDetails;
import com.oracle.bmc.mysql.model.DbSystem;
import com.oracle.bmc.mysql.model.InnoDbShutdownMode;
import com.oracle.bmc.mysql.model.ShapeSummary;
import com.oracle.bmc.mysql.model.StopDbSystemDetails;
import com.oracle.bmc.mysql.model.VersionSummary;
import com.oracle.bmc.mysql.model.WorkRequest;
import com.oracle.bmc.mysql.model.WorkRequestOperationStatus;
import com.oracle.bmc.mysql.requests.CreateDbSystemRequest;
import com.oracle.bmc.mysql.requests.DeleteDbSystemRequest;
import com.oracle.bmc.mysql.requests.GetDbSystemRequest;
import com.oracle.bmc.mysql.requests.GetWorkRequestRequest;
import com.oracle.bmc.mysql.requests.ListConfigurationsRequest;
import com.oracle.bmc.mysql.requests.ListDbSystemsRequest;
import com.oracle.bmc.mysql.requests.ListShapesRequest;
import com.oracle.bmc.mysql.requests.ListVersionsRequest;
import com.oracle.bmc.mysql.requests.StartDbSystemRequest;
import com.oracle.bmc.mysql.requests.StopDbSystemRequest;
import com.oracle.bmc.mysql.responses.CreateDbSystemResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates MySQL HeatWave DB systems and the catalog against the real oci-java-sdk. */
class MysqlCompatibilityTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static DbSystemClient dbSystems;
    private static MysqlaasClient catalog;
    private static WorkRequestsClient workRequests;

    @BeforeAll
    static void setUp() {
        dbSystems = EmulatorFixture.mysqlDbSystems();
        catalog = EmulatorFixture.mysqlCatalog();
        workRequests = EmulatorFixture.mysqlWorkRequests();
    }

    @AfterAll
    static void tearDown() {
        dbSystems.close();
        catalog.close();
        workRequests.close();
    }

    @Test
    void catalogListsShapesVersionsAndDefaultConfigurations() {
        List<ShapeSummary> shapes = catalog.listShapes(ListShapesRequest.builder().compartmentId(TENANCY).build())
                .getItems();
        assertThat(shapes).extracting(ShapeSummary::getName).contains("MySQL.2", "MySQL.VM.Standard.E3.1.8GB");

        List<VersionSummary> versions = catalog.listVersions(ListVersionsRequest.builder()
                .compartmentId(TENANCY).build()).getItems();
        assertThat(versions).flatExtracting(VersionSummary::getVersions)
                .extracting(version -> version.getVersion()).contains("8.4.4");

        List<ConfigurationSummary> configurations = catalog.listConfigurations(ListConfigurationsRequest.builder()
                .compartmentId(TENANCY).shapeName("MySQL.2")
                .type(List.of(ListConfigurationsRequest.Type.Default)).build()).getItems();
        assertThat(configurations).extracting(ConfigurationSummary::getDisplayName)
                .containsExactlyInAnyOrder("MySQL.2.Standalone", "MySQL.2.HA");
    }

    @Test
    void dbSystemLifecycle() throws InterruptedException {
        CreateDbSystemResponse created = dbSystems.createDbSystem(CreateDbSystemRequest.builder()
                .createDbSystemDetails(CreateDbSystemDetails.builder()
                        .compartmentId(TENANCY)
                        .displayName("java-sdk-mysql")
                        .shapeName("MySQL.2")
                        .subnetId(CompatNetworks.create(TENANCY, "java-mysql").subnetId())
                        .mysqlVersion("8.4")
                        .adminUsername("admin_user")
                        .adminPassword("BEstrO0ng_#11")
                        .maintenance(CreateMaintenanceDetails.builder().windowStartTime("sun 01:00").build())
                        .build())
                .build());
        String id = created.getDbSystem().getId();

        // Every check sits inside the try, so a failure still deletes the DB system it created.
        try {
            assertThat(created.getOpcWorkRequestId()).isNotBlank();
            assertThat(created.getEtag()).isNotBlank();
            assertThat(created.getDbSystem().getMysqlVersion()).isEqualTo("8.4.4");
            assertThat(created.getDbSystem().getMaintenance().getWindowStartTime()).isEqualTo("sun 01:00");

            WorkRequest workRequest = awaitWorkRequest(created.getOpcWorkRequestId());
            assertThat(workRequest.getStatus()).isEqualTo(WorkRequestOperationStatus.Succeeded);
            assertThat(workRequest.getResources().get(0).getIdentifier()).isEqualTo(id);

            DbSystem active = dbSystems.getDbSystem(GetDbSystemRequest.builder().dbSystemId(id).build())
                    .getDbSystem();
            assertThat(active.getLifecycleState()).isEqualTo(DbSystem.LifecycleState.Active);
            assertThat(active.getEndpoints()).isNotEmpty();
            assertThat(active.getEndpoints().get(0).getPort()).isPositive();
            assertThat(dbSystems.listDbSystems(ListDbSystemsRequest.builder().compartmentId(TENANCY).build())
                    .getItems()).extracting(summary -> summary.getId()).contains(id);

            String stop = dbSystems.stopDbSystem(StopDbSystemRequest.builder().dbSystemId(id)
                    .stopDbSystemDetails(StopDbSystemDetails.builder().shutdownType(InnoDbShutdownMode.Fast).build())
                    .build()).getOpcWorkRequestId();
            assertThat(awaitWorkRequest(stop).getStatus()).isEqualTo(WorkRequestOperationStatus.Succeeded);
            assertThat(state(id)).isEqualTo(DbSystem.LifecycleState.Inactive);

            String start = dbSystems.startDbSystem(StartDbSystemRequest.builder().dbSystemId(id).build())
                    .getOpcWorkRequestId();
            assertThat(awaitWorkRequest(start).getStatus()).isEqualTo(WorkRequestOperationStatus.Succeeded);
            assertThat(state(id)).isEqualTo(DbSystem.LifecycleState.Active);
        } finally {
            String delete = dbSystems.deleteDbSystem(DeleteDbSystemRequest.builder().dbSystemId(id).build())
                    .getOpcWorkRequestId();
            awaitWorkRequest(delete);
        }
        assertThat(state(id)).isEqualTo(DbSystem.LifecycleState.Deleted);
    }

    private static DbSystem.LifecycleState state(String id) {
        return dbSystems.getDbSystem(GetDbSystemRequest.builder().dbSystemId(id).build())
                .getDbSystem().getLifecycleState();
    }

    private static WorkRequest awaitWorkRequest(String workRequestId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        WorkRequest workRequest = null;
        while (Instant.now().isBefore(deadline)) {
            workRequest = workRequests.getWorkRequest(GetWorkRequestRequest.builder()
                    .workRequestId(workRequestId).build()).getWorkRequest();
            if (workRequest.getStatus() != WorkRequestOperationStatus.InProgress
                    && workRequest.getStatus() != WorkRequestOperationStatus.Accepted) {
                return workRequest;
            }
            Thread.sleep(1000);
        }
        return workRequest;
    }
}
