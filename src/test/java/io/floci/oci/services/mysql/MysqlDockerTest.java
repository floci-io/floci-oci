package io.floci.oci.services.mysql;

import com.mysql.cj.jdbc.Driver;
import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A DB system backed by a real MySQL container, used the way an application would: over JDBC,
 * with the endpoint GetDbSystem returns and the admin user the create named. Skipped when Docker
 * is not available. The last test deletes the DB system, so nothing is left running.
 */
@QuarkusTest
@TestProfile(MysqlDockerTest.RealMysqlProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlDockerTest {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(300);
    private static final String USER = "adminUser";
    private static final String PASSWORD = "BEstrO0ng_#11'\\x";

    private static String compartment;
    private static TestNetworks.Network network;

    private String dbSystemId;
    private String workRequestId;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception expected) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding, skipping the real MySQL test");
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("mysqldockercompartment");
            network = TestNetworks.create(compartment);
        }
    }

    @Test
    @Order(1)
    void createReturnsCreatingWithAnInProgressWorkRequest() {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", "docker-mysql");
        details.put("compartmentId", compartment);
        details.put("shapeName", "MySQL.2");
        details.put("subnetId", network.subnetId());
        details.put("mysqlVersion", "8.4");
        details.put("adminUsername", USER);
        details.put("adminPassword", PASSWORD);

        Response created = given().contentType("application/json").body(details)
            .when().post("/20190415/dbSystems")
            .then().statusCode(200)
                .header("opc-work-request-id", notNullValue())
                .body("lifecycleState", equalTo("CREATING"))
                .body("mysqlVersion", equalTo("8.4.4"))
                .extract().response();
        dbSystemId = created.path("id");
        workRequestId = created.header("opc-work-request-id");

        given().when().get("/20190415/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].identifier", equalTo(dbSystemId));
    }

    @Test
    @Order(2)
    void theDbSystemBecomesActiveOnceMysqlAnswers() throws InterruptedException {
        awaitState("ACTIVE");

        given().when().get("/20190415/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue());
    }

    @Test
    @Order(3)
    void theAdminConnectsAtTheReturnedEndpointWithEveryPrivilege() throws Exception {
        try (Connection connection = connect(PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE shop");
            statement.execute("CREATE TABLE shop.orders (id int primary key, item varchar(64))");
            statement.execute("INSERT INTO shop.orders VALUES (1, 'kept across a restart')");
            try (ResultSet version = statement.executeQuery("SELECT VERSION()")) {
                assertTrue(version.next());
                assertTrue(version.getString(1).startsWith("8.4.4"), version.getString(1));
            }
        }
        assertThrows(SQLException.class, () -> connect("not-the-password").close());
    }

    @Test
    @Order(4)
    void stopAndStartKeepTheData() throws Exception {
        String stop = given().contentType("application/json").body(Map.of("shutdownType", "FAST"))
            .when().post("/20190415/dbSystems/" + dbSystemId + "/actions/stop")
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(stop);
        awaitState("INACTIVE");
        assertTrue(dockerPs().isEmpty(), "the stopped system's container is gone");

        String start = given().when().post("/20190415/dbSystems/" + dbSystemId + "/actions/start")
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(start);
        awaitState("ACTIVE");

        try (Connection connection = connect(PASSWORD); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT item FROM shop.orders")) {
            assertTrue(rows.next());
            assertEquals("kept across a restart", rows.getString(1));
        }
    }

    @Test
    @Order(5)
    void deleteRemovesTheContainer() throws Exception {
        String delete = given().when().delete("/20190415/dbSystems/" + dbSystemId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(delete);
        awaitState("DELETED");
        assertTrue(dockerPs().isEmpty(), "the deleted system's container is gone");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Connection connect(String password) throws SQLException {
        Response system = given().when().get("/20190415/dbSystems/" + dbSystemId)
            .then().statusCode(200).extract().response();
        String host = system.path("endpoints[0].hostname");
        int port = system.path("endpoints[0].port");
        Properties properties = new Properties();
        properties.setProperty("user", USER);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("sslMode", "PREFERRED");
        // Through the driver itself, not DriverManager: DriverManager loads its drivers once, with the
        // class loader of the first test that asks, and refuses them to the next Quarkus test profile.
        return new Driver().connect("jdbc:mysql://" + host + ":" + port + "/", properties);
    }

    /** The running containers labelled with this DB system's OCID. */
    private List<String> dockerPs() throws Exception {
        Process ps = new ProcessBuilder("docker", "ps", "-aq", "--filter", "label=io.floci.resource-id=" + dbSystemId)
                .redirectErrorStream(true).start();
        assertTrue(ps.waitFor(10, TimeUnit.SECONDS));
        String output = new String(ps.getInputStream().readAllBytes()).strip();
        return output.isEmpty() ? List.of() : List.of(output.split("\\s+"));
    }

    private void awaitState(String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String state = null;
        String details = null;
        while (Instant.now().isBefore(deadline)) {
            Response system = given().when().get("/20190415/dbSystems/" + dbSystemId)
                .then().statusCode(200).extract().response();
            state = system.path("lifecycleState");
            details = system.path("lifecycleDetails");
            if (expected.equals(state) || "FAILED".equals(state)) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(expected, state, "lifecycleDetails: " + details);
        assertFalse("FAILED".equals(state) && !"FAILED".equals(expected));
    }

    private void awaitWorkRequest(String id) throws InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = given().when().get("/20190415/workRequests/" + id)
                .then().statusCode(200).extract().path("status");
            if (!"IN_PROGRESS".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals("SUCCEEDED", status);
    }

    public static class RealMysqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.mysql.mock", "false");
        }
    }
}
