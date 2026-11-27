package io.floci.oci.services.artifacts;

import io.floci.oci.testing.OciRequestSigner;
import io.floci.oci.testing.SecurityTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-repository blob mounts in {@code strict} mode against the real {@code registry:2} sidecar. A
 * mount's {@code from} is an OCIR repository and reads that namespace, so it is held to the same
 * tenancy rule as the destination: a mount from another tenancy, named either way, falls back to a
 * plain upload session and copies nothing, while a mount within the tenancy is performed.
 */
@QuarkusTest
@TestProfile(OcirMountStrictDockerTest.StrictRegistryProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OcirMountStrictDockerTest {

    private static final String DEFAULT_TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String OTHER_TENANCY = "ocid1.tenancy.oc1..mountother" + System.nanoTime();
    private static final String HOST = "iad.ocir.localhost.floci.io";
    private static final String TOKEN_PATH = "/20180419/docker/token";
    private static final RestAssuredConfig AS_SIGNED = RestAssuredConfig.config()
            .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false));

    private final KeyPair sessionKey = OciRequestSigner.newKeyPair();
    private final byte[] otherLayer = ("other tenancy layer " + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
    private final byte[] ownLayer = ("own tenancy layer " + System.nanoTime()).getBytes(StandardCharsets.UTF_8);

    private String ownNamespace;
    private String ownBearer;
    private String otherNamespace;

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

    private static String sha256(byte[] data) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** A JSON request in the other tenancy, signed with a session token as {@code oci session} does. */
    private RequestSpecification asOtherTenancy(String method, String target, String body) {
        String token = SecurityTokens.session(OTHER_TENANCY, "ocid1.user.oc1..mountsession",
                (RSAPublicKey) sessionKey.getPublic());
        Map<String, String> headers = OciRequestSigner.of("ST$" + token, sessionKey.getPrivate())
                .sign(method, target, "localhost", Instant.now(), body.getBytes(StandardCharsets.UTF_8),
                        "application/json");
        RequestSpecification request = given().config(AS_SIGNED).urlEncodingEnabled(false);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!"content-length".equals(header.getKey())) {
                request.header(header.getKey(), header.getValue());
            }
        }
        return request.body(body.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestSpecification registry(String bearer) {
        return given().urlEncodingEnabled(false).header("Host", HOST).header("Authorization", "Bearer " + bearer);
    }

    private static String login(String namespace, String userName, String authToken) {
        return given().auth().preemptive().basic(namespace + "/" + userName, authToken)
            .when().get(TOKEN_PATH)
            .then().statusCode(200).extract().path("token");
    }

    private static void pushBlob(String bearer, String repository, byte[] data) throws Exception {
        String location = registry(bearer).when().post("/v2/" + repository + "/blobs/uploads/")
            .then().statusCode(202).extract().header("Location");
        String separator = location.contains("?") ? "&" : "?";
        registry(bearer).contentType("application/octet-stream").body(data)
            .when().put(location + separator + "digest=" + sha256(data))
            .then().statusCode(201);
    }

    @Test
    @Order(1)
    void theOtherTenancyPushesABlob() throws Exception {
        String repository = "{\"compartmentId\":\"" + OTHER_TENANCY + "\",\"displayName\":\"secret/app\"}";
        otherNamespace = asOtherTenancy("POST", "/20160918/container/repositories", repository)
            .when().post("/20160918/container/repositories")
            .then().statusCode(200).extract().path("namespace");
        String user = "{\"compartmentId\":\"" + OTHER_TENANCY + "\",\"name\":\"other-pusher\",\"description\":\"d\"}";
        String userId = asOtherTenancy("POST", "/20160918/users", user)
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
        String tokens = "/20160918/users/" + userId + "/authTokens";
        String authToken = asOtherTenancy("POST", tokens, "{\"description\":\"docker\"}")
            .when().post(tokens)
            .then().statusCode(200).extract().path("token");

        String otherBearer = login(otherNamespace, "other-pusher", authToken);
        pushBlob(otherBearer, otherNamespace + "/secret/app", otherLayer);
        registry(otherBearer).when().head("/v2/" + otherNamespace + "/secret/app/blobs/" + sha256(otherLayer))
            .then().statusCode(200);
    }

    @Test
    @Order(2)
    void theCallerLogsInToItsOwnTenancy() {
        ownNamespace = given().queryParam("compartmentId", DEFAULT_TENANCY)
            .when().get("/20160918/container/configuration")
            .then().statusCode(200).extract().path("namespace");
        for (String name : new String[] {"mount/source", "mount/target"}) {
            given().contentType("application/json").body(Map.of("compartmentId", DEFAULT_TENANCY, "displayName", name))
                .when().post("/20160918/container/repositories")
                .then().statusCode(200);
        }
        String userName = "mounter-" + System.nanoTime();
        String userId = given().contentType("application/json")
                .body(Map.of("compartmentId", DEFAULT_TENANCY, "name", userName, "description", "d"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
        String authToken = given().contentType("application/json").body(Map.of("description", "docker"))
            .when().post("/20160918/users/{userId}/authTokens", userId)
            .then().statusCode(200).extract().path("token");
        ownBearer = login(ownNamespace, userName, authToken);
    }

    @Test
    @Order(3)
    void aMountFromAnotherTenancyCopiesNothing() throws Exception {
        String digest = sha256(otherLayer);
        String target = "/v2/" + ownNamespace + "/mount/target/blobs/";
        for (String from : new String[] {otherNamespace + "/secret/app", "iad/" + otherNamespace + "/secret/app"}) {
            registry(ownBearer)
                .when().post(target + "uploads/?mount=" + digest + "&from=" + from)
                .then().statusCode(202)
                    .header("Location", startsWith("/v2/" + ownNamespace + "/mount/target/blobs/uploads/"));
            registry(ownBearer).when().head(target + digest).then().statusCode(404);
        }
        registry(ownBearer).when().head("/v2/" + otherNamespace + "/secret/app/blobs/" + digest)
            .then().statusCode(403);
    }

    @Test
    @Order(4)
    void aMountWithinTheTenancyIsPerformed() throws Exception {
        pushBlob(ownBearer, ownNamespace + "/mount/source", ownLayer);
        String digest = sha256(ownLayer);
        String target = "/v2/" + ownNamespace + "/mount/target/blobs/";
        registry(ownBearer).when().head(target + digest).then().statusCode(404);

        registry(ownBearer)
            .when().post(target + "uploads/?mount=" + digest + "&from=" + ownNamespace + "/mount/source")
            .then().statusCode(201)
                .header("Docker-Content-Digest", equalTo(digest))
                .header("Location", equalTo(target + digest));
        registry(ownBearer).when().head(target + digest).then().statusCode(200);
    }

    @Test
    @Order(5)
    void cleanUp() {
        // Stops the registry sidecar via Resettable.
        given().post("/_floci-oci/state/reset").then().statusCode(200);
    }

    public static class StrictRegistryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.auth.signature-verification", "strict",
                    "floci-oci.services.artifacts.mock", "false");
        }
    }
}
