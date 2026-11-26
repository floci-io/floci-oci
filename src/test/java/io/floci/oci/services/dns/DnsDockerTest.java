package io.floci.oci.services.dns;

import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real container resolves a private-zone record through the embedded DNS server. floci-oci runs on
 * the host here, so the server listens on {@code floci-oci.dns.host-port} on loopback, and a busybox
 * container sharing the host network queries it there. Skipped without Docker, off Linux (where
 * {@code --network host} does not share the host's loopback), and when the tests themselves run in a
 * container (where the server binds port 53 instead).
 */
@QuarkusTest
@TestProfile(DnsDockerTest.HostPortProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DnsDockerTest {

    /** Fixed: the profile is read in another class loader, so a port picked at class load would differ. */
    private static final int HOST_PORT = 25354;
    private static final String IMAGE = "busybox:1.36";

    @Inject
    ContainerDetector containerDetector;

    private String viewId;
    private String zoneId;
    private String zoneName;

    @BeforeAll
    void requireDockerOnLinuxHost() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception expected) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding, skipping the DNS resolution test");
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"),
                "--network host shares the host loopback on Linux only");
        assumeFalse(containerDetector.isRunningInContainer(),
                "inside a container the embedded DNS server serves port 53, not the host port");
    }

    @Test
    @Order(1)
    void privateZoneRecordResolvesInAContainer() throws Exception {
        String compartment = TestCompartments.create("dnsdocker");
        viewId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "docker-view"))
            .when().post("/20180115/views")
            .then().statusCode(201).extract().path("id");
        zoneName = "docker" + System.nanoTime() + ".internal";
        zoneId = given().contentType("application/json")
                .body(Map.of("name", zoneName, "compartmentId", compartment, "scope", "PRIVATE", "viewId", viewId))
            .when().post("/20180115/zones")
            .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(Map.of("items", List.of(Map.of(
                    "operation", "ADD", "domain", "app." + zoneName, "rtype", "A", "rdata", "192.0.2.44", "ttl", 30))))
            .when().patch("/20180115/zones/" + zoneId + "/records")
            .then().statusCode(200);

        String output = run("docker", "run", "--rm", "--network", "host", IMAGE,
                "nslookup", "-port=" + HOST_PORT, "app." + zoneName, "127.0.0.1");
        assertTrue(output.contains("192.0.2.44"), output);
    }

    @Test
    @Order(2)
    void deletedRecordNoLongerResolves() throws Exception {
        given().when().delete("/20180115/zones/" + zoneId + "/records/app." + zoneName + "/A")
            .then().statusCode(204);

        String output = run("docker", "run", "--rm", "--network", "host", IMAGE,
                "nslookup", "-port=" + HOST_PORT, "app." + zoneName, "127.0.0.1");
        assertTrue(output.contains("NXDOMAIN"), output);
    }

    @Test
    @Order(3)
    void cleanup() {
        given().when().delete("/20180115/zones/" + zoneId).then().statusCode(204);
        given().when().delete("/20180115/views/" + viewId).then().statusCode(204);
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "docker run did not finish: " + output);
        return output;
    }

    public static class HostPortProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.dns.host-port", String.valueOf(HOST_PORT));
        }
    }
}
