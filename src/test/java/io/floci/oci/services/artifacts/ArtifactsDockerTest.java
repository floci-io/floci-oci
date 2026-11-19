package io.floci.oci.services.artifacts;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane: flips {@code mock=false}, starts the shared {@code registry:2} container and
 * pushes an image through the {@code /v2/} data plane with the registry protocol itself (blob
 * uploads and a manifest), so no docker CLI push is needed. Skips when no Docker engine responds.
 */
@QuarkusTest
@TestProfile(ArtifactsDockerTest.RealArtifactsProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArtifactsDockerTest {

    private static final String HOST = "iad.ocir.localhost.floci.io";
    private static final String REPOSITORY = "docker-it/app";
    private static final byte[] LAYER = "floci-oci layer".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONFIG = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);

    private String compartment;
    private String namespace;
    private String manifestDigest;
    private String imageId;

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine not responding; skipping the real registry tests");
    }

    /** The test server is not up yet in {@code @BeforeAll}, so the compartment is made lazily here. */
    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("dockerartifacts");
        }
    }

    private static RequestSpecification registry() {
        return given().urlEncodingEnabled(false).header("Host", HOST).header("Authorization", "Bearer test");
    }

    private static String sha256(byte[] data) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void pushBlob(byte[] data) throws Exception {
        String location = registry().when().post("/v2/" + namespace + "/" + REPOSITORY + "/blobs/uploads/")
            .then().statusCode(202).extract().header("Location");
        String separator = location.contains("?") ? "&" : "?";
        registry().contentType("application/octet-stream").body(data)
            .when().put(location + separator + "digest=" + sha256(data))
            .then().statusCode(201);
    }

    @Test
    @Order(1)
    void loginChallengeAdvertisesTheTokenEndpoint() {
        given().header("Host", HOST)
            .when().get("/v2/")
            .then().statusCode(401)
                .header("WWW-Authenticate", containsString("/20180419/docker/token"));
    }

    @Test
    @Order(2)
    void pushIntoACreatedRepository() throws Exception {
        namespace = given().queryParam("compartmentId", compartment)
            .when().get("/20160918/container/configuration")
            .then().statusCode(200).extract().path("namespace");
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", REPOSITORY))
            .when().post("/20160918/container/repositories")
            .then().statusCode(200);

        pushBlob(LAYER);
        pushBlob(CONFIG);
        String manifest = "{\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"size\":"
                + CONFIG.length + ",\"digest\":\"" + sha256(CONFIG) + "\"},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.diff.tar.gzip\",\"size\":"
                + LAYER.length + ",\"digest\":\"" + sha256(LAYER) + "\"}]}";
        manifestDigest = registry().contentType("application/vnd.docker.distribution.manifest.v2+json")
                .body(manifest)
            .when().put("/v2/" + namespace + "/" + REPOSITORY + "/manifests/v1")
            .then().statusCode(201).extract().header("Docker-Content-Digest");

        registry().when().get("/v2/" + namespace + "/" + REPOSITORY + "/tags/list")
            .then().statusCode(200)
                .body("name", equalTo(namespace + "/" + REPOSITORY))
                .body("tags", contains("v1"));
    }

    @Test
    @Order(3)
    void pushedImageIsListedByTheManagementPlane() {
        imageId = given().queryParam("compartmentId", compartment).queryParam("repositoryName", REPOSITORY)
            .when().get("/20160918/container/images")
            .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].digest", equalTo(manifestDigest))
                .body("items[0].displayName", equalTo(REPOSITORY + ":v1"))
                .extract().path("items[0].id");

        given().when().get("/20160918/container/images/" + imageId)
            .then().statusCode(200)
                .body("versions[0].version", equalTo("v1"))
                .body("layers", hasSize(1))
                .body("layersSizeInBytes", equalTo(LAYER.length));
    }

    @Test
    @Order(4)
    void pushIntoAMissingRepositoryIsNameUnknown() {
        registry().when().post("/v2/" + namespace + "/missing/blobs/uploads/")
            .then().statusCode(404).body("errors[0].code", equalTo("NAME_UNKNOWN"));
    }

    @Test
    @Order(5)
    void deleteImageThenCleanUp() {
        given().when().delete("/20160918/container/images/" + imageId).then().statusCode(204);
        registry().when().get("/v2/" + namespace + "/" + REPOSITORY + "/manifests/" + manifestDigest)
            .then().statusCode(404);
        // Stops the registry sidecar via Resettable.
        given().post("/_floci-oci/state/reset").then().statusCode(200);
    }

    public static class RealArtifactsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.artifacts.mock", "false");
        }
    }
}
