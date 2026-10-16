package io.floci.oci.services.psql;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A DB system backed by a real PostgreSQL container, used the way an application would: with
 * the endpoint and CA certificate from GetConnectionDetails. Skipped when Docker is not
 * available. The last test deletes the DB system, so nothing is left running.
 */
@QuarkusTest
@TestProfile(PsqlDockerTest.RealPsqlProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PsqlDockerTest {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(240);
    private static final String USER = "adminUser";
    private static final String PASSWORD = "BEstrO0ng_#11";
    private static final String NEW_PASSWORD = "N3w'pass \"w0rd\"; --";

    private static String compartment;

    private String dbSystemId;
    private String workRequestId;
    private String jdbcUrl;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception expected) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding, skipping the real PostgreSQL test");
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("psqldockercompartment");
        }
    }

    @Test
    @Order(1)
    void createReturnsCreatingWithAnInProgressWorkRequest() {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", "docker-psql");
        details.put("compartmentId", compartment);
        details.put("dbVersion", "16");
        details.put("shape", "PostgreSQL.VM.Standard.E4.Flex.2.32GB");
        details.put("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", true));
        details.put("networkDetails", Map.of("subnetId", "ocid1.subnet.oc1.iad.psqldockersubnet"));
        details.put("credentials", Map.of("username", USER,
                "passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", PASSWORD)));

        Response created = given().contentType("application/json").body(details)
            .when().post("/20220915/dbSystems")
            .then().statusCode(200)
                .header("opc-work-request-id", notNullValue())
                .body("lifecycleState", equalTo("CREATING"))
                .extract().response();
        dbSystemId = created.path("id");
        workRequestId = created.header("opc-work-request-id");

        given().when().get("/20220915/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].identifier", equalTo(dbSystemId));
    }

    @Test
    @Order(2)
    void theDbSystemBecomesActiveOncePostgresAnswers() throws InterruptedException {
        awaitState("ACTIVE");

        given().when().get("/20220915/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue());
        String dbVersion = given().when().get("/20220915/dbSystems/" + dbSystemId)
            .then().extract().path("dbVersion");
        assertTrue(dbVersion.matches("16\\.\\d+"), "the server's own version, got " + dbVersion);
    }

    @Test
    @Order(3)
    void anApplicationConnectsOverTlsWithTheReturnedCertificate() throws Exception {
        jdbcUrl = jdbcUrl();

        try (Connection connection = connect(PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id int primary key, item text)");
            statement.execute("INSERT INTO orders VALUES (1, 'kept across a restart')");
            try (ResultSet ssl = statement.executeQuery(
                    "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                assertTrue(ssl.next());
                assertTrue(ssl.getBoolean(1), "the connection is encrypted");
            }
        }
        assertThrows(SQLException.class, () -> connect("not-the-password").close());
    }

    @Test
    @Order(4)
    void resettingTheMasterPasswordTakesEffectInTheDatabase() throws Exception {
        String reset = given().contentType("application/json")
                .body(Map.of("passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", NEW_PASSWORD)))
            .when().post("/20220915/dbSystems/" + dbSystemId + "/actions/resetMasterUserPassword")
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(reset);
        awaitState("ACTIVE");

        connect(NEW_PASSWORD).close();
        assertThrows(SQLException.class, () -> connect(PASSWORD).close());
    }

    @Test
    @Order(5)
    void stopAndStartKeepTheData() throws Exception {
        given().when().post("/20220915/dbSystems/" + dbSystemId + "/actions/stop")
            .then().statusCode(202);
        awaitState("INACTIVE");
        assertThrows(SQLException.class, () -> connect(NEW_PASSWORD).close());

        given().when().post("/20220915/dbSystems/" + dbSystemId + "/actions/start")
            .then().statusCode(202);
        awaitState("ACTIVE");

        jdbcUrl = jdbcUrl();
        try (Connection connection = connect(NEW_PASSWORD); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT item FROM orders WHERE id = 1")) {
            assertTrue(row.next());
            assertEquals("kept across a restart", row.getString(1));
        }
    }

    @Test
    @Order(6)
    void deleteRemovesTheContainer() throws Exception {
        given().when().delete("/20220915/dbSystems/" + dbSystemId)
            .then().statusCode(202);
        awaitState("DELETED");

        assertThrows(SQLException.class, () -> connect(NEW_PASSWORD).close());
        Process listing = new ProcessBuilder("docker", "ps", "-a", "--filter",
                "label=io.floci.resource-id=" + dbSystemId, "--format", "{{.Names}}")
                .redirectErrorStream(true).start();
        String names = new String(listing.getInputStream().readAllBytes()).strip();
        listing.waitFor(10, TimeUnit.SECONDS);
        assertEquals("", names, "no container is left for the deleted DB system");
    }

    private String jdbcUrl() throws Exception {
        Response connection = given().when().get("/20220915/dbSystems/" + dbSystemId + "/connectionDetails")
            .then().statusCode(200).extract().response();
        Path caCertificate = Files.createTempFile("floci-psql-ca", ".pem");
        caCertificate.toFile().deleteOnExit();
        Files.writeString(caCertificate, connection.path("caCertificate"));
        String host = connection.path("primaryDbEndpoint.fqdn");
        int port = connection.path("primaryDbEndpoint.port");
        // verify-full also checks the host name against the certificate.
        return "jdbc:postgresql://" + host + ":" + port + "/postgres?sslmode=verify-full&sslrootcert="
                + caCertificate.toAbsolutePath();
    }

    private Connection connect(String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", USER);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private void awaitState(String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String state = null;
        String details = null;
        while (Instant.now().isBefore(deadline)) {
            Response system = given().when().get("/20220915/dbSystems/" + dbSystemId)
                .then().statusCode(200).extract().response();
            state = system.path("lifecycleState");
            details = system.path("lifecycleDetails");
            if (expected.equals(state) || "FAILED".equals(state) || "NEEDS_ATTENTION".equals(state)) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(expected, state, "lifecycleDetails: " + details);
    }

    private void awaitWorkRequest(String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = given().when().get("/20220915/workRequests/" + id)
                .then().statusCode(200).extract().path("status");
            if (!"IN_PROGRESS".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals("SUCCEEDED", status);
    }

    public static class RealPsqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.psql.mock", "false");
        }
    }
}
