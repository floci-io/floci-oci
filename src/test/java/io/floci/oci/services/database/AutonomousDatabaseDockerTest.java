package io.floci.oci.services.database;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import oracle.jdbc.OracleDriver;
import oracle.security.pki.OracleSecretStore;
import oracle.security.pki.OracleWallet;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An Autonomous Database backed by a real Autonomous Database Free container, used the way an
 * application would: over JDBC, through the wallet GenerateAutonomousDatabaseWallet returns. Skipped
 * when Docker is not available. The image is about 12 GB and each container takes about 4 GB of
 * memory, so unlike the other Docker tests it also runs only when asked for: selected by name
 * ({@code -Dtest=AutonomousDatabaseDockerTest}) or with {@code FLOCI_OCI_TEST_ADB=true}. A plain
 * {@code mvn verify} on a laptop or in CI skips it. The last test deletes the database, so nothing
 * is left running.
 */
@QuarkusTest
@TestProfile(AutonomousDatabaseDockerTest.RealAutonomousDatabaseProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutonomousDatabaseDockerTest {

    private static final Logger LOG = Logger.getLogger(AutonomousDatabaseDockerTest.class);
    /** The first create pulls the image (about 12 GB), then the container takes about 3 minutes. */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration ACTION_TIMEOUT = Duration.ofMinutes(12);
    private static final String ADMIN_PASSWORD = "Floci#Secret2026";
    private static final String WALLET_PASSWORD = "Floci#Wallet26";

    private String compartment;
    private String databaseId;
    private String createWorkRequestId;
    private Path walletDir;
    private final Map<String, Instant> started = new HashMap<>();

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception expected) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding, skipping the real Autonomous Database test");
        assumeTrue(System.getProperty("test", "").contains(AutonomousDatabaseDockerTest.class.getSimpleName())
                        || "true".equalsIgnoreCase(System.getenv("FLOCI_OCI_TEST_ADB")),
                "The Autonomous Database Free image is about 12 GB: select this test by name or set"
                        + " FLOCI_OCI_TEST_ADB=true to run it");
    }

    /** The test server's port is only wired into RestAssured once a test runs, not in @BeforeAll. */
    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("adbdockercompartment");
        }
    }

    @AfterAll
    void removeWallet() throws IOException {
        deleteRecursively(walletDir);
    }

    /** The wallet tools can leave files of their own beside a wallet, so directories go with their contents. */
    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    @Order(1)
    void createReturnsProvisioningWithAnInProgressWorkRequest() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("dbName", "flocidocker");
        details.put("displayName", "docker-adb");
        details.put("adminPassword", ADMIN_PASSWORD);
        started.put("create", Instant.now());

        Response created = given().contentType("application/json").body(details)
            .when().post("/20160918/autonomousDatabases")
            .then().statusCode(200)
                .header("opc-work-request-id", notNullValue())
                .body("lifecycleState", equalTo("PROVISIONING"))
                .extract().response();
        databaseId = created.path("id");
        createWorkRequestId = created.header("opc-work-request-id");

        given().when().get("/20160918/workRequests/" + createWorkRequestId)
            .then().statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].identifier", equalTo(databaseId));
    }

    @Test
    @Order(2)
    void theDatabaseBecomesAvailableOnceTheContainerIsHealthy() throws Exception {
        assumeTrue(databaseId != null);
        awaitState("AVAILABLE", CREATE_TIMEOUT);
        LOG.infov("create to AVAILABLE took {0}", Duration.between(started.get("create"), Instant.now()));

        given().when().get("/20160918/workRequests/" + createWorkRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"));
        Response database = given().when().get("/20160918/autonomousDatabases/" + databaseId)
            .then().statusCode(200).extract().response();
        LOG.infov("connectionStrings: {0}", database.path("connectionStrings").toString());
        assertNotNull(database.path("connectionStrings.high"));
        assertFalse(database.path("connectionStrings.high").toString().contains(":1522/"),
                "the connection strings name the published port");
        assertEquals(List.of(databaseId), dockerPs(false));
    }

    @Test
    @Order(3)
    void theAdminConnectsThroughTheGeneratedWallet() throws Exception {
        assumeTrue(databaseId != null);
        byte[] zip = given().contentType("application/json")
                .body(Map.of("password", WALLET_PASSWORD, "generateType", "SINGLE"))
            .when().post("/20160918/autonomousDatabases/" + databaseId + "/actions/generateWallet")
            .then().statusCode(200).extract().asByteArray();
        walletDir = Files.createTempDirectory("adb-wallet");
        List<String> names = unzip(zip, walletDir);
        LOG.infov("wallet files: {0}", names);
        assertTrue(names.containsAll(List.of("cwallet.sso", "ewallet.p12", "tnsnames.ora", "sqlnet.ora")), names.toString());

        try (Connection connection = connect(walletDir, null); Statement statement = connection.createStatement()) {
            try (ResultSet one = statement.executeQuery("select 1 from dual")) {
                assertTrue(one.next());
                assertEquals(1, one.getInt(1));
            }
            statement.execute("create table orders (id number primary key, item varchar2(64))");
            statement.execute("insert into orders values (1, 'kept across a stop')");
            connection.commit();
        }
    }

    @Test
    @Order(4)
    void theKeystoresOpenWithTheRequestPasswordAndKeepTheOracleSecrets() throws Exception {
        assumeTrue(walletDir != null);
        KeyStore p12 = KeyStore.getInstance("PKCS12");
        p12.load(new ByteArrayInputStream(Files.readAllBytes(walletDir.resolve("ewallet.p12"))),
                WALLET_PASSWORD.toCharArray());
        assertTrue(p12.size() > 0);
        for (String store : List.of("keystore.jks", "truststore.jks")) {
            if (Files.exists(walletDir.resolve(store))) {
                KeyStore jks = KeyStore.getInstance("JKS");
                jks.load(Files.newInputStream(walletDir.resolve(store)), WALLET_PASSWORD.toCharArray());
                LOG.infov("{0}: {1} entries", store, jks.size());
            }
        }

        // The container's own ewallet.p12 and the re-encrypted one, each in a copy of the generated
        // wallet without the auto-login cwallet.sso, so the driver has to open ewallet.p12 with a password.
        String containerPassword = docker("inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}",
                containerName()).lines().filter(line -> line.startsWith("WALLET_PASSWORD="))
                .map(line -> line.substring("WALLET_PASSWORD=".length())).findFirst().orElseThrow();
        Path original = passwordOnlyCopy("adb-original-wallet");
        Path generated = passwordOnlyCopy("adb-generated-wallet");
        try {
            docker("cp", containerName() + ":" + AutonomousDatabaseContainerManager.WALLET_DIR + "/ewallet.p12",
                    original.resolve("ewallet.p12").toString());
            TreeSet<String> before = secretAliases(original, containerPassword);
            TreeSet<String> after = secretAliases(generated, WALLET_PASSWORD);
            LOG.infov("secret store aliases: container {0}, generated {1}", before, after);
            assertEquals(before, after);

            String originalOutcome = connectOutcome(original, containerPassword);
            String generatedOutcome = connectOutcome(generated, WALLET_PASSWORD);
            LOG.infov("password-only connect: container wallet {0}, generated wallet {1}", originalOutcome,
                    generatedOutcome);
            assertEquals(originalOutcome, generatedOutcome,
                    "the re-encrypted ewallet.p12 behaves like the container's own");
        } finally {
            deleteRecursively(original);
            deleteRecursively(generated);
        }
    }

    /** A copy of the generated wallet without cwallet.sso. */
    private Path passwordOnlyCopy(String prefix) throws IOException {
        Path copy = Files.createTempDirectory(prefix);
        try (Stream<Path> files = Files.list(walletDir)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().equals("cwallet.sso")) {
                    Files.copy(file, copy.resolve(file.getFileName()));
                }
            }
        }
        return copy;
    }

    /** "connected", or the class and message of the deepest cause, without the per-connection id. */
    private String connectOutcome(Path wallet, String walletPassword) {
        try (Connection connection = connect(wallet, walletPassword); Statement statement = connection.createStatement();
             ResultSet one = statement.executeQuery("select 1 from dual")) {
            return one.next() ? "connected" : "no row";
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            LOG.infov(e, "password-only connect through {0} failed", wallet);
            return root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()).replaceAll("\\(CONNECTION_ID=[^)]*\\)", "").strip();
        }
    }

    @Test
    @Order(5)
    void stopAndStartKeepTheSameContainerAndItsData() throws Exception {
        assumeTrue(walletDir != null);
        started.put("stop", Instant.now());
        String stop = given().when().post("/20160918/autonomousDatabases/" + databaseId + "/actions/stop")
            .then().statusCode(200)
                .body("lifecycleState", equalTo("STOPPING"))
                .extract().header("opc-work-request-id");
        awaitState("STOPPED", ACTION_TIMEOUT);
        awaitWorkRequest(stop);
        LOG.infov("stop took {0}", Duration.between(started.get("stop"), Instant.now()));
        assertTrue(dockerPs(true).isEmpty(), "the stopped database's container is not running");
        assertEquals(List.of(databaseId), dockerPs(false), "but it is still there");

        started.put("start", Instant.now());
        String start = given().when().post("/20160918/autonomousDatabases/" + databaseId + "/actions/start")
            .then().statusCode(200)
                .body("lifecycleState", equalTo("STARTING"))
                .extract().header("opc-work-request-id");
        awaitState("AVAILABLE", ACTION_TIMEOUT);
        awaitWorkRequest(start);
        LOG.infov("start took {0}", Duration.between(started.get("start"), Instant.now()));

        try (Connection connection = connect(walletDir, null); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select item from orders where id = 1")) {
            assertTrue(rows.next());
            assertEquals("kept across a stop", rows.getString(1));
        }
    }

    @Test
    @Order(6)
    void deleteRemovesTheContainer() throws Exception {
        assumeTrue(databaseId != null);
        String delete = given().when().delete("/20160918/autonomousDatabases/" + databaseId)
            .then().statusCode(204).extract().header("opc-work-request-id");
        awaitWorkRequest(delete);
        awaitState("TERMINATED", ACTION_TIMEOUT);
        assertTrue(dockerPs(false).isEmpty(), "the deleted database's container is gone");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The first alias of the connection strings, through the wallet; with a password, ewallet.p12 is used. */
    private Connection connect(Path wallet, String walletPassword) throws SQLException {
        String alias = given().when().get("/20160918/autonomousDatabases/" + databaseId)
            .then().statusCode(200).extract().path("connectionStrings.profiles[0].displayName");
        Properties properties = new Properties();
        properties.setProperty("user", "ADMIN");
        properties.setProperty("password", ADMIN_PASSWORD);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "20000");
        if (walletPassword != null) {
            properties.setProperty("oracle.net.wallet_password", walletPassword);
        }
        // Through the driver itself, not DriverManager: DriverManager loads its drivers once, with the
        // class loader of the first test that asks, and refuses them to the next Quarkus test profile.
        Connection connection = new OracleDriver().connect("jdbc:oracle:thin:@" + alias + "?TNS_ADMIN="
                + wallet.toAbsolutePath(), properties);
        assertNotNull(connection, "the driver accepted the URL");
        connection.setAutoCommit(false);
        return connection;
    }

    private static TreeSet<String> secretAliases(Path wallet, String password) throws Exception {
        OracleWallet oracleWallet = new OracleWallet();
        oracleWallet.open(wallet.toString(), password.toCharArray());
        OracleSecretStore store = oracleWallet.getSecretStore();
        TreeSet<String> aliases = new TreeSet<>();
        for (Object alias : Collections.list(store.internalAliases())) {
            aliases.add(alias.toString());
        }
        return aliases;
    }

    private static List<String> unzip(byte[] zip, Path target) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
                Files.write(target.resolve(entry.getName()), in.readAllBytes());
            }
        }
        return names;
    }

    private String containerName() throws Exception {
        String name = docker("ps", "-a", "--filter", "label=io.floci.resource-id=" + databaseId,
                "--format", "{{.Names}}").strip();
        assertFalse(name.isEmpty(), "the database has a container");
        return name;
    }

    /** The resource ids of the containers labelled with this database's OCID, running ones only or all. */
    private List<String> dockerPs(boolean runningOnly) throws Exception {
        List<String> command = new ArrayList<>(List.of("ps", "--filter", "label=io.floci.resource-id=" + databaseId,
                "--format", "{{.Label \"io.floci.resource-id\"}}"));
        if (!runningOnly) {
            command.add(1, "-a");
        }
        String output = docker(command.toArray(String[]::new)).strip();
        return output.isEmpty() ? List.of() : List.of(output.split("\\s+"));
    }

    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
        return output;
    }

    private void awaitState(String expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String state = null;
        String details = null;
        while (Instant.now().isBefore(deadline)) {
            Response database = given().when().get("/20160918/autonomousDatabases/" + databaseId)
                .then().statusCode(200).extract().response();
            state = database.path("lifecycleState");
            details = database.path("lifecycleDetails");
            if (expected.equals(state) || "UNAVAILABLE".equals(state)) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals(expected, state, "lifecycleDetails: " + details);
    }

    private void awaitWorkRequest(String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(ACTION_TIMEOUT);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = given().when().get("/20160918/workRequests/" + id)
                .then().statusCode(200).extract().path("status");
            if (!"IN_PROGRESS".equals(status)) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals("SUCCEEDED", status);
    }

    public static class RealAutonomousDatabaseProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.database.mock", "false",
                    "floci-oci.docker.image-pull-timeout-seconds", "2700");
        }
    }
}
