package io.floci.oci.services.containerinstances;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-container lane: flips {@code mock=false}, so a container instance is a pause container plus
 * busybox containers on Docker. Skips cleanly when no Docker engine answers.
 */
@QuarkusTest
@TestProfile(ContainerInstancesDockerTest.RealContainerInstancesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContainerInstancesDockerTest {

    private static final String AD = "Floc:US-ASHBURN-AD-1";
    private static final String LABEL = "io.floci.resource-id";

    private String compartment;
    private TestNetworks.Network network;
    private String instanceId;
    private String containerId;
    private String vnicId;

    @BeforeAll
    void requireDocker() throws Exception {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding: skipping real container instance tests");
    }

    @BeforeEach
    void createNetwork() {
        if (compartment == null) {
            compartment = TestCompartments.create("ciDocker");
            network = TestNetworks.create(compartment);
        }
    }

    @Test
    @Order(1)
    void createRunsTheContainersAndTheWorkRequestSucceeds() throws Exception {
        String config = Base64.getEncoder().encodeToString("from-a-config-file\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("availabilityDomain", AD);
        details.put("displayName", "ci-docker");
        details.put("shape", "CI.Standard.E4.Flex");
        details.put("shapeConfig", Map.of("ocpus", 1, "memoryInGBs", 2));
        details.put("containerRestartPolicy", "NEVER");
        details.put("volumes", List.of(
                Map.of("name", "conf", "volumeType", "CONFIGFILE",
                        "configs", List.of(Map.of("fileName", "index.html", "data", config))),
                Map.of("name", "scratch", "volumeType", "EMPTYDIR")));
        details.put("containers", List.of(Map.of(
                "imageUrl", "busybox:1.36",
                "command", List.of("sh", "-c"),
                "arguments", List.of("echo started-$MODE; exec httpd -f -p 8080 -h /www"),
                "environmentVariables", Map.of("MODE", "docker"),
                "resourceConfig", Map.of("vcpusLimit", 0.5, "memoryLimitInGBs", 0.25),
                "volumeMounts", List.of(Map.of("volumeName", "conf", "mountPath", "/www"),
                        Map.of("volumeName", "scratch", "mountPath", "/scratch")))));
        details.put("vnics", List.of(Map.of("subnetId", network.subnetId())));

        Response created = given().contentType("application/json").body(details)
            .when().post("/20210415/containerInstances")
            .then().statusCode(200).extract().response();
        instanceId = created.path("id");
        containerId = created.path("containers[0].containerId");
        vnicId = created.path("vnics[0].vnicId");
        assertEquals("CREATING", created.path("lifecycleState"));

        awaitWorkRequest(created.header("opc-work-request-id"), 180);
        given().when().get("/20210415/containerInstances/" + instanceId)
            .then().statusCode(200).body("lifecycleState", equalTo("ACTIVE"));
        assertFalse(docker("ps", "-q", "--filter", "label=" + LABEL + "=" + instanceId).isBlank(),
                "the pause container runs");
        assertFalse(docker("ps", "-q", "--filter", "label=" + LABEL + "=" + containerId).isBlank(),
                "the container runs");
    }

    @Test
    @Order(2)
    void theContainerLogsAreRetrievable() throws Exception {
        String logs = "";
        for (int i = 0; i < 20 && !logs.contains("started-docker"); i++) {
            logs = given().when().post("/20210415/containers/" + containerId + "/actions/retrieveLogs")
                .then().statusCode(200).extract().asString();
            Thread.sleep(500);
        }
        assertTrue(logs.contains("started-docker"), logs);
    }

    @Test
    @Order(3)
    void thePodAnswersOnItsVnicAddressWithTheConfigFile() throws Exception {
        String ip = given().when().get("/20160918/vnics/" + vnicId)
            .then().statusCode(200).extract().path("privateIp");
        assertNotNull(ip, "the VNIC records the pod's address");
        String pause = docker("ps", "-q", "--filter", "label=" + LABEL + "=" + instanceId).trim();
        String networkName = docker("inspect", "-f", "{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}",
                pause).trim();
        String body = "";
        for (int i = 0; i < 20 && !body.contains("from-a-config-file"); i++) {
            body = docker("run", "--rm", "--network", networkName, "busybox:1.36",
                    "wget", "-qO-", "-T", "2", "http://" + ip + ":8080/");
            Thread.sleep(500);
        }
        assertTrue(body.contains("from-a-config-file"), body);
        String limits = docker("inspect", "-f", "{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}",
                docker("ps", "-q", "--filter", "label=" + LABEL + "=" + containerId).trim()).trim();
        assertEquals("500000000 268435456", limits);
    }

    @Test
    @Order(4)
    void stopAndStartTheInstance() throws Exception {
        String stop = given().when().post("/20210415/containerInstances/" + instanceId + "/actions/stop")
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(stop, 60);
        given().when().get("/20210415/containerInstances/" + instanceId)
            .then().statusCode(200).body("lifecycleState", equalTo("INACTIVE"));
        assertTrue(docker("ps", "-q", "--filter", "label=" + LABEL + "=" + containerId).isBlank());

        String start = given().when().post("/20210415/containerInstances/" + instanceId + "/actions/start")
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(start, 60);
        given().when().get("/20210415/containers/" + containerId)
            .then().statusCode(200).body("lifecycleState", equalTo("ACTIVE"));
        assertFalse(docker("ps", "-q", "--filter", "label=" + LABEL + "=" + containerId).isBlank());
    }

    @Test
    @Order(5)
    void deleteRemovesTheContainersAndTheVolumes() throws Exception {
        String delete = given().when().delete("/20210415/containerInstances/" + instanceId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        awaitWorkRequest(delete, 60);
        given().when().get("/20210415/containerInstances/" + instanceId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
        assertTrue(docker("ps", "-aq", "--filter", "label=" + LABEL + "=" + instanceId).isBlank());
        assertTrue(docker("ps", "-aq", "--filter", "label=" + LABEL + "=" + containerId).isBlank());
        assertFalse(docker("volume", "ls", "-q").contains("ci-" + instanceId), "the EMPTYDIR volume is removed");
        given().when().get("/20160918/vnics/" + vnicId).then().statusCode(404);
    }

    @Test
    @Order(6)
    void cleanup() throws Exception {
        for (String id : docker("ps", "-aq", "--filter", "label=io.floci.service=containerinstances").lines().toList()) {
            docker("rm", "-f", id);
        }
    }

    private void awaitWorkRequest(String workRequestId, int seconds) throws InterruptedException {
        String status = "";
        for (int i = 0; i < seconds * 2; i++) {
            status = given().when().get("/20210415/workRequests/" + workRequestId)
                .then().statusCode(200).extract().path("status");
            if (!"IN_PROGRESS".equals(status) && !"ACCEPTED".equals(status)) {
                break;
            }
            Thread.sleep(500);
        }
        if (!"SUCCEEDED".equals(status)) {
            String errors = given().when().get("/20210415/workRequests/" + workRequestId + "/errors").asString();
            fail("work request " + workRequestId + " ended " + status + ": " + errors);
        }
    }

    private static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(120, TimeUnit.SECONDS);
        return output;
    }

    public static class RealContainerInstancesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.containerinstances.mock", "false");
        }
    }
}
