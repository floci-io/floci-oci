package io.floci.oci.compat;

import com.oracle.bmc.database.DatabaseClient;
import com.oracle.bmc.database.model.AutonomousDatabase;
import com.oracle.bmc.database.model.AutonomousDatabaseSummary;
import com.oracle.bmc.database.model.CreateAutonomousDatabaseBase;
import com.oracle.bmc.database.model.CreateAutonomousDatabaseDetails;
import com.oracle.bmc.database.model.GenerateAutonomousDatabaseWalletDetails;
import com.oracle.bmc.database.model.UpdateAutonomousDatabaseDetails;
import com.oracle.bmc.database.requests.CreateAutonomousDatabaseRequest;
import com.oracle.bmc.database.requests.DeleteAutonomousDatabaseRequest;
import com.oracle.bmc.database.requests.GenerateAutonomousDatabaseWalletRequest;
import com.oracle.bmc.database.requests.GetAutonomousDatabaseRequest;
import com.oracle.bmc.database.requests.ListAutonomousDatabasesRequest;
import com.oracle.bmc.database.requests.StartAutonomousDatabaseRequest;
import com.oracle.bmc.database.requests.StopAutonomousDatabaseRequest;
import com.oracle.bmc.database.requests.UpdateAutonomousDatabaseRequest;
import com.oracle.bmc.database.responses.CreateAutonomousDatabaseResponse;
import com.oracle.bmc.database.responses.GenerateAutonomousDatabaseWalletResponse;
import com.oracle.bmc.workrequests.model.WorkRequest;
import com.oracle.bmc.workrequests.requests.GetWorkRequestRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Autonomous Databases (management plane, mock mode) against the real oci-java-sdk,
 * with their work requests polled on the core work-request client.
 */
class AutonomousDatabaseCompatibilityTest {

    private static DatabaseClient database;
    private static com.oracle.bmc.workrequests.WorkRequestClient workRequests;

    @BeforeAll
    static void setUp() {
        database = EmulatorFixture.database();
        workRequests = EmulatorFixture.coreWorkRequests();
    }

    @AfterAll
    static void tearDown() {
        database.close();
        workRequests.close();
    }

    @Test
    void autonomousDatabaseLifecycle() throws IOException {
        String dbName = "javaadb" + (System.currentTimeMillis() % 100000);
        CreateAutonomousDatabaseResponse created = database.createAutonomousDatabase(
                CreateAutonomousDatabaseRequest.builder()
                        .createAutonomousDatabaseDetails(CreateAutonomousDatabaseDetails.builder()
                                .compartmentId(TENANCY)
                                .dbName(dbName)
                                .displayName("java-sdk-adb")
                                .adminPassword("Welcome12345#")
                                .computeModel(CreateAutonomousDatabaseBase.ComputeModel.Ecpu)
                                .computeCount(2f)
                                .dataStorageSizeInTBs(1)
                                .dbWorkload(CreateAutonomousDatabaseBase.DbWorkload.Oltp)
                                .dbVersion("19c")
                                .build())
                        .build());
        AutonomousDatabase adb = created.getAutonomousDatabase();
        String id = adb.getId();
        assertThat(id).startsWith("ocid1.autonomousdatabase.");
        assertThat(adb.getLifecycleState()).isEqualTo(AutonomousDatabase.LifecycleState.Available);
        assertThat(adb.getDbName()).isEqualTo(dbName);
        assertThat(adb.getDbVersion()).isEqualTo("19c");
        assertThat(adb.getConnectionStrings().getHigh()).contains(dbName.toLowerCase() + "_high");
        assertThat(created.getEtag()).isNotBlank();

        WorkRequest workRequest = workRequests.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(created.getOpcWorkRequestId()).build()).getWorkRequest();
        assertThat(workRequest.getStatus()).isEqualTo(WorkRequest.Status.Succeeded);
        assertThat(workRequest.getResources()).anySatisfy(resource -> {
            assertThat(resource.getEntityType()).containsIgnoringCase("database");
            assertThat(resource.getIdentifier()).isEqualTo(id);
        });

        List<AutonomousDatabaseSummary> listed = database.listAutonomousDatabases(
                ListAutonomousDatabasesRequest.builder().compartmentId(TENANCY).displayName("java-sdk-adb").build())
                .getItems();
        assertThat(listed).extracting(AutonomousDatabaseSummary::getId).contains(id);

        AutonomousDatabase updated = database.updateAutonomousDatabase(UpdateAutonomousDatabaseRequest.builder()
                .autonomousDatabaseId(id)
                .updateAutonomousDatabaseDetails(UpdateAutonomousDatabaseDetails.builder()
                        .computeCount(4f).build())
                .build()).getAutonomousDatabase();
        assertThat(updated.getComputeCount()).isEqualTo(4f);

        assertThat(database.stopAutonomousDatabase(StopAutonomousDatabaseRequest.builder()
                .autonomousDatabaseId(id).build()).getAutonomousDatabase().getLifecycleState())
                .isEqualTo(AutonomousDatabase.LifecycleState.Stopped);
        assertThat(database.startAutonomousDatabase(StartAutonomousDatabaseRequest.builder()
                .autonomousDatabaseId(id).build()).getAutonomousDatabase().getLifecycleState())
                .isEqualTo(AutonomousDatabase.LifecycleState.Available);

        GenerateAutonomousDatabaseWalletResponse wallet = database.generateAutonomousDatabaseWallet(
                GenerateAutonomousDatabaseWalletRequest.builder()
                        .autonomousDatabaseId(id)
                        .generateAutonomousDatabaseWalletDetails(GenerateAutonomousDatabaseWalletDetails.builder()
                                .password("WalletPass1")
                                .generateType(GenerateAutonomousDatabaseWalletDetails.GenerateType.Single)
                                .build())
                        .build());
        Map<String, String> files = unzip(wallet.getInputStream());
        assertThat(files).containsKeys("tnsnames.ora", "sqlnet.ora");
        assertThat(files.get("tnsnames.ora")).contains(dbName.toLowerCase() + "_tp = ");

        String deleteWorkRequest = database.deleteAutonomousDatabase(DeleteAutonomousDatabaseRequest.builder()
                .autonomousDatabaseId(id).build()).getOpcWorkRequestId();
        assertThat(deleteWorkRequest).isNotBlank();
        assertThat(database.getAutonomousDatabase(GetAutonomousDatabaseRequest.builder()
                .autonomousDatabaseId(id).build()).getAutonomousDatabase().getLifecycleState())
                .isEqualTo(AutonomousDatabase.LifecycleState.Terminated);
    }

    private static Map<String, String> unzip(InputStream wallet) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(wallet)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
