package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A stream pool backed by a real Redpanda broker: messages produced with {@code rpk} are read
 * back through GetMessages, and messages put through PutMessages are on the broker at the
 * offset PutMessages returned. Skipped when Docker is not available. The last test deletes the
 * pool, so nothing is left running.
 */
@QuarkusTest
@TestProfile(StreamingKafkaDockerTest.RealStreamingProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamingKafkaDockerTest {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(180);

    private static String compartment;

    @Inject
    StreamPoolKafkaManager kafka;

    @Inject
    EmulatorConfig config;

    private String poolId;
    private String poolWorkRequestId;
    private String streamId;
    private String streamName;
    private String bootstrapServers;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception expected) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding, skipping the real Redpanda test");
    }

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("streamingdockercompartment");
        }
    }

    @Test
    @Order(1)
    void createReturnsCreatingWithAnInProgressWorkRequest() {
        Response created = given().contentType("application/json")
                .body(Map.of("name", "docker-pool", "compartmentId", compartment))
            .when().post("/20180418/streampools")
            .then().statusCode(200)
                .header("opc-work-request-id", notNullValue())
                .body("lifecycleState", equalTo("CREATING"))
                .extract().response();
        poolId = created.path("id");
        poolWorkRequestId = created.header("opc-work-request-id");

        given().when().get("/20180418/workRequests/" + poolWorkRequestId)
            .then().statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].entityType", equalTo("stream_pool"))
                .body("resources[0].identifier", equalTo(poolId));
    }

    @Test
    @Order(2)
    void thePoolBecomesActiveWithAKafkaEndpoint() throws InterruptedException {
        awaitState("/20180418/streampools/" + poolId, "ACTIVE");
        given().when().get("/20180418/workRequests/" + poolWorkRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue());
        bootstrapServers = given().when().get("/20180418/streampools/" + poolId)
            .then().statusCode(200)
                .body("kafkaSettings.bootstrapServers", startsWith("localhost:"))
                .extract().path("kafkaSettings.bootstrapServers");
    }

    @Test
    @Order(3)
    void aStreamInThePoolBecomesATopic() throws Exception {
        streamName = "docker-stream-" + System.nanoTime();
        Response created = given().contentType("application/json")
                .body(Map.of("name", streamName, "partitions", 2, "streamPoolId", poolId))
            .when().post("/20180418/streams")
            .then().statusCode(200)
                .body("lifecycleState", equalTo("CREATING"))
                .extract().response();
        streamId = created.path("id");
        awaitState("/20180418/streams/" + streamId, "ACTIVE");
        given().when().get("/20180418/workRequests/" + created.header("opc-work-request-id"))
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));

        String topics = exec("rpk", "-X", "brokers=127.0.0.1:29092", "topic", "list");
        assertTrue(topics.contains(streamName), "the stream's topic is on the broker: " + topics);
    }

    @Test
    @Order(4)
    void aRecordProducedWithRpkIsReadByGetMessages() throws Exception {
        execWithInput("hello\n", "rpk", "-X", "brokers=127.0.0.1:29092", "topic", "produce", streamName,
                "-p", "0", "-k", "k1");

        String cursor = cursor("0", "TRIM_HORIZON");
        given().queryParam("cursor", cursor)
            .when().get("/20180418/streams/" + streamId + "/messages")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].stream", equalTo(streamName))
                .body("[0].partition", equalTo("0"))
                .body("[0].offset", equalTo(0))
                .body("[0].key", equalTo(b64("k1")))
                .body("[0].value", equalTo(b64("hello")))
                .body("[0].timestamp", notNullValue());
    }

    @Test
    @Order(5)
    void aRecordPutThroughOciIsOnTheBrokerAtTheSameOffset() throws Exception {
        int offset = given().contentType("application/json")
                .body(Map.of("messages", List.of(Map.of("value", b64("world")))))
            .when().post("/20180418/streams/" + streamId + "/messages")
            .then().statusCode(200)
                .body("failures", equalTo(0))
                .body("entries[0].partition", equalTo("0"))
                .extract().path("entries[0].offset");
        assertEquals(1, offset);

        String proxy = publishedProxy();
        HttpRequest fetch = HttpRequest.newBuilder(URI.create("http://" + proxy + "/topics/" + streamName
                        + "/partitions/0/records?offset=" + offset + "&timeout=1000&max_bytes=1048576"))
                .header("Accept", "application/vnd.kafka.binary.v2+json")
                .GET().build();
        String records = HttpClient.newHttpClient().send(fetch, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(records.contains("\"offset\":" + offset), records);
        assertTrue(records.contains("\"value\":\"" + b64("world") + "\""), records);

        String latest = cursor("0", "LATEST");
        given().contentType("application/json")
            .body(Map.of("messages", List.of(Map.of("value", b64("after")))))
            .when().post("/20180418/streams/" + streamId + "/messages")
            .then().statusCode(200);
        given().queryParam("cursor", latest)
            .when().get("/20180418/streams/" + streamId + "/messages")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].offset", equalTo(2))
                .body("[0].value", equalTo(b64("after")));
    }

    @Test
    @Order(6)
    void aKafkaClientOnTheHostReadsTheStreamAtBootstrapServers() throws Exception {
        // A client container on the host's network stands in for an application on the host.
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"),
                "host networking reaches the published port only on Linux");
        String consumed = run(null, "docker", "run", "--rm", "--network", "host", "--entrypoint", "rpk",
                config.services().streaming().kafkaImage(), "-X", "brokers=" + bootstrapServers,
                "topic", "consume", streamName, "-p", "0", "-o", "0", "-n", "3", "-f", "%v\\n");
        assertEquals(List.of("hello", "world", "after"), List.of(consumed.strip().split("\n")));
    }

    @Test
    @Order(7)
    void deletingTheStreamAndPoolRemovesTheBroker() throws Exception {
        String streamDeleted = given().when().delete("/20180418/streams/" + streamId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        Instant streamDeadline = Instant.now().plus(READY_TIMEOUT);
        int status = 200;
        while (status != 404 && Instant.now().isBefore(streamDeadline)) {
            status = given().when().get("/20180418/streams/" + streamId).then().extract().statusCode();
            if (status != 404) {
                Thread.sleep(500);
            }
        }
        assertEquals(404, status, "the stream is gone once its topic is deleted");
        given().when().get("/20180418/workRequests/" + streamDeleted)
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));
        String topics = exec("rpk", "-X", "brokers=127.0.0.1:29092", "topic", "list");
        assertTrue(!topics.contains(streamName), "the stream's topic is deleted: " + topics);
        String deleted = given().when().delete("/20180418/streampools/" + poolId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given().when().get("/20180418/workRequests/" + deleted)
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));

        Instant deadline = Instant.now().plusSeconds(60);
        boolean gone = false;
        while (!gone && Instant.now().isBefore(deadline)) {
            Process inspect = new ProcessBuilder("docker", "inspect", kafka.containerName(poolId))
                    .redirectErrorStream(true).start();
            gone = inspect.waitFor(10, TimeUnit.SECONDS) && inspect.exitValue() != 0;
            if (!gone) {
                Thread.sleep(500);
            }
        }
        assertTrue(gone, "the broker container is removed");
    }

    private String cursor(String partition, String type) {
        return given().contentType("application/json")
                .body(Map.of("partition", partition, "type", type))
            .when().post("/20180418/streams/" + streamId + "/cursors")
            .then().statusCode(200).extract().path("value");
    }

    private String publishedProxy() throws Exception {
        String mapping = run(null, "docker", "port", kafka.containerName(poolId),
                StreamPoolKafkaManager.PROXY_PORT + "/tcp").strip().split("\n")[0];
        return "localhost:" + mapping.substring(mapping.lastIndexOf(':') + 1);
    }

    private String exec(String... command) throws Exception {
        return execWithInput(null, command);
    }

    private String execWithInput(String input, String... command) throws Exception {
        List<String> full = new ArrayList<>(List.of("docker", "exec", "-i", kafka.containerName(poolId)));
        full.addAll(List.of(command));
        return run(input, full.toArray(new String[0]));
    }

    private static String run(String input, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "timed out: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + " failed: " + output);
        return output;
    }

    private static void awaitState(String path, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            state = given().when().get(path).then().statusCode(200).extract().path("lifecycleState");
            if (expected.equals(state)) {
                return;
            }
            assertTrue(!"FAILED".equals(state), path + " FAILED: "
                    + given().when().get(path).then().extract().path("lifecycleStateDetails"));
            Thread.sleep(500);
        }
        assertEquals(expected, state, path + " did not reach " + expected);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static class RealStreamingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.streaming.mock", "false");
        }
    }
}
