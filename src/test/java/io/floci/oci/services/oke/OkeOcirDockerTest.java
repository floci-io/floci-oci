package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.artifacts.ArtifactsRegistryManager;
import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane for a pod whose image is in the local OCIR: pushes busybox into the
 * {@code registry:2} sidecar, starts a real k3s cluster, and runs a pod from the image's
 * {@code iad.ocir.localhost.floci.io:<port>} reference, which k3s can only pull through the
 * {@code registries.yaml} mirror floci-oci writes. Then moves the registry to another port and
 * runs a second pod that must pull again, through the mirror floci-oci rewrote in the running
 * sidecar. Skips when no Docker engine responds.
 */
@QuarkusTest
@TestProfile(OkeOcirDockerTest.RealOkeAndOcirProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OkeOcirDockerTest {

    private static final String SOURCE_IMAGE = "busybox:1.36";
    private static final String REPOSITORY = "oke-ocir/busybox";
    private static final String POD = "ocir-pod";
    private static final String POD_AFTER_MOVE = "ocir-pod-after-move";
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    @Inject
    ArtifactsRegistryManager registry;

    @Inject
    EmulatorConfig config;

    @Inject
    PortAllocator portAllocator;

    private String compartment;
    private String vcnId;
    private String namespace;
    private String pushed;
    private String clusterId;

    @BeforeAll
    void requireDockerAndTheSourceImage() throws Exception {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding; skipping the OKE OCIR pull test");
        assumeTrue(docker("pull", SOURCE_IMAGE).exitCode() == 0, "could not pull " + SOURCE_IMAGE);
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("okeocirdocker");
            vcnId = TestNetworks.create(compartment).vcnId();
        }
    }

    @Test
    @Order(1)
    void pushBusyboxIntoTheRegistry() throws Exception {
        namespace = given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/configuration")
            .then().statusCode(200).extract().path("namespace");
        given().contentType(ContentType.JSON)
                .body(Map.of("compartmentId", compartment, "displayName", REPOSITORY))
            .when().post("/20160918/container/repositories")
            .then().statusCode(200);
        registry.ensureReady();
        pushed = "localhost:" + registry.hostPort() + "/iad/" + namespace + "/" + REPOSITORY + ":1";
        assertEquals(0, docker("tag", SOURCE_IMAGE, pushed).exitCode());
        assertEquals(0, docker("push", pushed).exitCode());
    }

    @Test
    @Order(2)
    void clusterBecomesActive() throws Exception {
        String workRequestId = given().contentType(ContentType.JSON)
                .body(Map.of("compartmentId", compartment, "name", "ocir-pull-cluster",
                        "vcnId", vcnId, "kubernetesVersion", "v1.30.1"))
            .when().post("/20180222/clusters")
            .then().statusCode(202).extract().header("opc-work-request-id");
        clusterId = OkeWorkRequests.createdId(workRequestId);
        Instant deadline = Instant.now().plus(TIMEOUT);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            state = given().when().get("/20180222/clusters/{id}", clusterId)
                .then().statusCode(200).extract().path("lifecycleState");
            if (!"CREATING".equals(state)) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals("ACTIVE", state);
    }

    @Test
    @Order(3)
    void aPodRunsTheImageByItsOcirReference() throws Exception {
        String image = "iad.ocir.localhost.floci.io:" + config.port() + "/" + namespace + "/" + REPOSITORY + ":1";
        // The API answers before the controllers have created the default service account a pod needs.
        Instant ready = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(ready) && kubectl("get", "serviceaccount", "default").exitCode() != 0) {
            Thread.sleep(2000);
        }
        Result run = kubectl("run", POD, "--image=" + image, "--restart=Never", "--command", "--",
                "sh", "-c", "echo pulled-from-ocir");
        assertEquals(0, run.exitCode(), run.output());

        Instant deadline = Instant.now().plus(TIMEOUT);
        String phase = "";
        while (Instant.now().isBefore(deadline)) {
            phase = kubectl("get", "pod", POD, "-o", "jsonpath={.status.phase}").output().trim();
            if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                break;
            }
            Thread.sleep(2000);
        }
        String events = kubectl("describe", "pod", POD).output();
        assertEquals("Succeeded", phase, "the pod should pull from the local OCIR:\n" + events);
        assertTrue(kubectl("logs", POD).output().contains("pulled-from-ocir"));
    }

    @Test
    @Order(4)
    void aMovedRegistryIsMirroredByTheRunningSidecarWithoutANewNode() throws Exception {
        String sidecar = ContainerStorageHelper.dockerName(config, "oke-" + clusterId);
        String nodeBefore = kubectl("get", "nodes", "-o", "jsonpath={.items[*].metadata.name}").output().trim();
        String startedBefore = docker("inspect", "-f", "{{.State.StartedAt}}", sidecar).output().trim();
        int oldPort = registry.hostPort();

        // Holding the released port while the registry starts again makes it pick another one.
        registry.stop();
        portAllocator.markReserved(oldPort);
        try {
            registry.ensureReady();
        } finally {
            portAllocator.release(oldPort);
        }
        assertNotEquals(oldPort, registry.hostPort(), "the registry should have moved");

        Instant restarted = Instant.now().plus(TIMEOUT);
        String startedAfter = startedBefore;
        while (Instant.now().isBefore(restarted) && startedAfter.equals(startedBefore)) {
            Thread.sleep(1000);
            startedAfter = docker("inspect", "-f", "{{.State.StartedAt}}", sidecar).output().trim();
        }
        assertNotEquals(startedBefore, startedAfter, "the k3s sidecar should restart with the moved mirror");
        String registries = docker("exec", sidecar, "cat", OkeClusterManager.REGISTRIES_FILE).output();
        assertTrue(registries.contains("host.docker.internal:" + registry.hostPort()), registries);

        Instant apiBack = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(apiBack) && kubectl("get", "serviceaccount", "default").exitCode() != 0) {
            Thread.sleep(2000);
        }
        String image = "iad.ocir.localhost.floci.io:" + config.port() + "/" + namespace + "/" + REPOSITORY + ":1";
        Result run = kubectl("run", POD_AFTER_MOVE, "--image=" + image, "--image-pull-policy=Always",
                "--restart=Never", "--command", "--", "sh", "-c", "sleep 3600");
        assertEquals(0, run.exitCode(), run.output());

        Instant deadline = Instant.now().plus(TIMEOUT);
        String phase = "";
        while (Instant.now().isBefore(deadline)) {
            phase = kubectl("get", "pod", POD_AFTER_MOVE, "-o", "jsonpath={.status.phase}").output().trim();
            if ("Running".equals(phase) || "Failed".equals(phase)) {
                break;
            }
            Thread.sleep(2000);
        }
        String events = kubectl("describe", "pod", POD_AFTER_MOVE).output();
        assertEquals("Running", phase, "the pod should pull from the moved OCIR registry:\n" + events);
        assertEquals(nodeBefore, kubectl("get", "nodes", "-o", "jsonpath={.items[*].metadata.name}").output().trim(),
                "restarting the sidecar in place keeps its node");
    }

    @Test
    @Order(5)
    void cleanUpStateAndSidecars() throws Exception {
        // Removes the k3s sidecar, the registry container and their volumes via Resettable.
        given().post("/_floci-oci/state/reset").then().statusCode(200);
        if (pushed != null) {
            docker("rmi", "-f", pushed);
        }
    }

    private Result kubectl(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("exec",
                ContainerStorageHelper.dockerName(config, "oke-" + clusterId), "kubectl"));
        command.addAll(List.of(args));
        return docker(command.toArray(String[]::new));
    }

    /**
     * Runs one docker command with a deadline: the output is drained on another thread so a
     * blocked pull, push or exec cannot hang the test, and the process is killed when it expires.
     */
    private static Result docker(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process));
        if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            return new Result(-1, "timed out after " + COMMAND_TIMEOUT + ": " + String.join(" ", command));
        }
        return new Result(process.exitValue(), output.get(10, TimeUnit.SECONDS));
    }

    private static String readAll(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Result(int exitCode, String output) {
    }

    /**
     * Real OKE and Artifacts, with the test server on {@code floci-oci.port}, which the token webhook
     * and the mirror's host names both use.
     */
    public static class RealOkeAndOcirProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String port = String.valueOf(freePort());
            return Map.of(
                    "floci-oci.services.oke.mock", "false",
                    "floci-oci.services.artifacts.mock", "false",
                    "quarkus.http.test-port", port,
                    "floci-oci.port", port);
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("No free port for the OKE OCIR Docker test", e);
            }
        }
    }
}
