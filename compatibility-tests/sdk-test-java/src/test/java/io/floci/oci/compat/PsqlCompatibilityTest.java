package io.floci.oci.compat;

import com.oracle.bmc.psql.PostgresqlClient;
import com.oracle.bmc.psql.model.ConnectionDetails;
import com.oracle.bmc.psql.model.CreateDbSystemDetails;
import com.oracle.bmc.psql.model.Credentials;
import com.oracle.bmc.psql.model.DbSystem;
import com.oracle.bmc.psql.model.NetworkDetails;
import com.oracle.bmc.psql.model.OciOptimizedStorageDetails;
import com.oracle.bmc.psql.model.OperationStatus;
import com.oracle.bmc.psql.model.PlainTextPasswordDetails;
import com.oracle.bmc.psql.model.ResetMasterUserPasswordDetails;
import com.oracle.bmc.psql.model.WorkRequest;
import com.oracle.bmc.psql.requests.CreateDbSystemRequest;
import com.oracle.bmc.psql.requests.DeleteDbSystemRequest;
import com.oracle.bmc.psql.requests.GetConnectionDetailsRequest;
import com.oracle.bmc.psql.requests.GetDbSystemRequest;
import com.oracle.bmc.psql.requests.GetWorkRequestRequest;
import com.oracle.bmc.psql.requests.ListDbSystemsRequest;
import com.oracle.bmc.psql.requests.ListDefaultConfigurationsRequest;
import com.oracle.bmc.psql.requests.ListShapesRequest;
import com.oracle.bmc.psql.requests.ResetMasterUserPasswordRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Database with PostgreSQL against the real oci-java-sdk, and then against a real
 * PostgreSQL client: the DB system the SDK creates is one an application can connect to.
 */
class PsqlCompatibilityTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String USER = "admin_user";
    private static final String PASSWORD = "BEstrO0ng_#11";
    private static final String NEW_PASSWORD = "An0ther_#22";

    private static PostgresqlClient postgresql;

    @BeforeAll
    static void setUp() {
        postgresql = EmulatorFixture.postgresql();
    }

    @AfterAll
    static void tearDown() {
        postgresql.close();
    }

    @Test
    void catalogListsShapesAndDefaultConfigurations() {
        var shapes = postgresql.listShapes(ListShapesRequest.builder().compartmentId(TENANCY).build());
        assertThat(shapes.getOpcRequestId()).isNotBlank();
        assertThat(shapes.getShapeCollection().getItems())
                .extracting(shape -> shape.getShape()).contains("VM.Standard.E4.Flex");

        var configurations = postgresql.listDefaultConfigurations(
                ListDefaultConfigurationsRequest.builder().dbVersion("15").build());
        assertThat(configurations.getDefaultConfigurationCollection().getItems()).hasSize(3);
        assertThat(configurations.getDefaultConfigurationCollection().getItems().get(0).getId())
                .contains("defaultconfiguration");
    }

    @Test
    void dbSystemLifecycleAndARealConnection() throws Exception {
        var created = postgresql.createDbSystem(CreateDbSystemRequest.builder()
                .createDbSystemDetails(CreateDbSystemDetails.builder()
                        .compartmentId(TENANCY)
                        .displayName("java-sdk-db")
                        .dbVersion("16")
                        .shape("PostgreSQL.VM.Standard.E4.Flex.2.32GB")
                        .storageDetails(OciOptimizedStorageDetails.builder().isRegionallyDurable(true).build())
                        .networkDetails(NetworkDetails.builder().subnetId("ocid1.subnet.oc1.iad.javasdk").build())
                        .credentials(Credentials.builder()
                                .username(USER)
                                .passwordDetails(PlainTextPasswordDetails.builder().password(PASSWORD).build())
                                .build())
                        .build())
                .build());
        String id = created.getDbSystem().getId();

        // Every check sits inside the try, so a failure still deletes the database it created.
        try {
            assertThat(created.getOpcWorkRequestId()).isNotBlank();
            assertThat(created.getEtag()).isNotBlank();
            assertThat(created.getDbSystem().getShape()).isEqualTo("VM.Standard.E4.Flex");
            assertThat(created.getDbSystem().getInstanceOcpuCount()).isEqualTo(2);
            assertThat(created.getDbSystem().getAdminUsername()).isEqualTo(USER);

            WorkRequest workRequest = awaitWorkRequest(created.getOpcWorkRequestId());
            assertThat(workRequest.getStatus()).isEqualTo(OperationStatus.Succeeded);
            assertThat(workRequest.getResources().get(0).getIdentifier()).isEqualTo(id);

            DbSystem active = postgresql.getDbSystem(GetDbSystemRequest.builder().dbSystemId(id).build())
                    .getDbSystem();
            assertThat(active.getLifecycleState()).isEqualTo(DbSystem.LifecycleState.Active);
            assertThat(active.getDbVersion()).startsWith("16");
            assertThat(postgresql.listDbSystems(ListDbSystemsRequest.builder().compartmentId(TENANCY).build())
                    .getDbSystemCollection().getItems()).extracting(summary -> summary.getId()).contains(id);

            ConnectionDetails connection = postgresql.getConnectionDetails(
                    GetConnectionDetailsRequest.builder().dbSystemId(id).build()).getConnectionDetails();
            assertThat(connection.getCaCertificate()).startsWith("-----BEGIN CERTIFICATE-----");
            String url = jdbcUrl(connection);
            try (Connection jdbc = connect(url, PASSWORD); Statement statement = jdbc.createStatement();
                 ResultSet version = statement.executeQuery("SHOW server_version")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getString(1)).startsWith("16");
            }

            var reset = postgresql.resetMasterUserPassword(ResetMasterUserPasswordRequest.builder()
                    .dbSystemId(id)
                    .resetMasterUserPasswordDetails(ResetMasterUserPasswordDetails.builder()
                            .passwordDetails(PlainTextPasswordDetails.builder().password(NEW_PASSWORD).build())
                            .build())
                    .build());
            assertThat(awaitWorkRequest(reset.getOpcWorkRequestId()).getStatus())
                    .isEqualTo(OperationStatus.Succeeded);
            connect(url, NEW_PASSWORD).close();
        } finally {
            var deleted = postgresql.deleteDbSystem(DeleteDbSystemRequest.builder().dbSystemId(id).build());
            awaitWorkRequest(deleted.getOpcWorkRequestId());
        }
        assertThat(postgresql.getDbSystem(GetDbSystemRequest.builder().dbSystemId(id).build())
                .getDbSystem().getLifecycleState()).isEqualTo(DbSystem.LifecycleState.Deleted);
    }

    private static WorkRequest awaitWorkRequest(String workRequestId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        WorkRequest workRequest = null;
        while (Instant.now().isBefore(deadline)) {
            workRequest = postgresql.getWorkRequest(
                    GetWorkRequestRequest.builder().workRequestId(workRequestId).build()).getWorkRequest();
            if (workRequest.getTimeFinished() != null) {
                return workRequest;
            }
            Thread.sleep(1000);
        }
        return workRequest;
    }

    /** The endpoint and certificate exactly as OCI returns them, with the host name verified. */
    private static String jdbcUrl(ConnectionDetails connection) throws Exception {
        Path caCertificate = Files.createTempFile("floci-psql-ca", ".pem");
        caCertificate.toFile().deleteOnExit();
        Files.writeString(caCertificate, connection.getCaCertificate());
        return "jdbc:postgresql://" + connection.getPrimaryDbEndpoint().getFqdn() + ":"
                + connection.getPrimaryDbEndpoint().getPort() + "/postgres?sslmode=verify-full&sslrootcert="
                + caCertificate.toAbsolutePath();
    }

    private static Connection connect(String url, String password) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", USER);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(url, properties);
    }
}
