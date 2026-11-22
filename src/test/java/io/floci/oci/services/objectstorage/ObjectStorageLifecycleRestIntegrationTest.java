package io.floci.oci.services.objectstorage;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Object lifecycle policies over the wire: PutObjectLifecyclePolicy, Get, Delete, and a sweep. */
@QuarkusTest
class ObjectStorageLifecycleRestIntegrationTest {

    private static final String NS = "floci-test";
    private static String compartment;

    @Inject
    ObjectLifecycleSweeper sweeper;

    @BeforeEach
    void setUp() {
        if (compartment == null) {
            compartment = TestCompartments.create("itlifecycle");
        }
    }

    private static String bucket() {
        String name = "it-lifecycle-" + System.nanoTime();
        given().contentType("application/json").body(Map.of("name", name, "compartmentId", compartment))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(200);
        return name;
    }

    private static Map<String, Object> rule(String name, String action, int amount, String target,
                                            Map<String, Object> filter) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", name);
        rule.put("action", action);
        rule.put("timeAmount", amount);
        rule.put("timeUnit", "DAYS");
        rule.put("isEnabled", true);
        rule.put("target", target);
        if (filter != null) {
            rule.put("objectNameFilter", filter);
        }
        return rule;
    }

    @Test
    void putGetAndDeleteRoundTrip() {
        String bucket = bucket();
        String path = "/n/" + NS + "/b/" + bucket + "/l";
        Map<String, Object> archive = rule("sampleRule", "ARCHIVE", 10, "objects",
                Map.of("inclusionPatterns", List.of("inclusionPattern1"),
                        "exclusionPatterns", List.of("exclusionPattern1")));

        String etag = given().contentType("application/json").body(Map.of("items", List.of(archive)))
            .when().put(path)
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("timeCreated", notNullValue())
                .body("items", hasSize(1))
                .body("items[0].name", equalTo("sampleRule"))
                .body("items[0].action", equalTo("ARCHIVE"))
                .body("items[0].timeAmount", equalTo(10))
                .body("items[0].timeUnit", equalTo("DAYS"))
                .body("items[0].isEnabled", equalTo(true))
                .body("items[0].target", equalTo("objects"))
                .body("items[0].objectNameFilter.inclusionPatterns", contains("inclusionPattern1"))
                .body("items[0].objectNameFilter.exclusionPatterns", contains("exclusionPattern1"))
                .body("items[0].objectNameFilter", not(hasKey("inclusionPrefixes")))
                .extract().header("etag");

        given().when().get(path)
            .then().statusCode(200)
                .header("etag", equalTo(etag))
                .body("items[0].name", equalTo("sampleRule"));

        given().contentType("application/json").header("if-match", "stale").body(Map.of("items", List.of()))
            .when().put(path)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));
        given().contentType("application/json").header("if-none-match", "*").body(Map.of("items", List.of()))
            .when().put(path)
            .then().statusCode(412).body("code", equalTo("IfNoneMatchFailed"));

        String emptied = given().contentType("application/json").header("if-match", etag)
                .body(Map.of("items", List.of()))
            .when().put(path)
            .then().statusCode(200).body("items", empty()).extract().header("etag");

        given().header("if-match", etag).when().delete(path)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));
        given().header("if-match", emptied).when().delete(path)
            .then().statusCode(204).header("opc-request-id", notNullValue());

        given().when().get(path)
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("LifecyclePolicyNotFound"))
                .body("message", containsString(bucket));
        given().when().delete(path).then().statusCode(404).body("code", equalTo("LifecyclePolicyNotFound"));
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void invalidRulesAreRejected() {
        String bucket = bucket();
        String path = "/n/" + NS + "/b/" + bucket + "/l";
        given().contentType("application/json")
                .body(Map.of("items", List.of(rule("r", "ABORT", 1, "objects", null))))
            .when().put(path)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().contentType("application/json")
                .body(Map.of("items", List.of(rule("r", "DELETE", 1, "objects",
                        Map.of("exclusionPatterns", List.of("[^a-z]"))))))
            .when().put(path)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"))
                .body("message", containsString("[^a-z]"));
        given().when().get("/n/" + NS + "/b/missing-" + System.nanoTime() + "/l")
            .then().statusCode(404).body("code", equalTo("BucketNotFound"));
    }

    @Test
    void aSweepDeletesDueObjectsAndAbortsDueUploads() {
        String bucket = bucket();
        given().body("old").when().put("/n/" + NS + "/b/" + bucket + "/o/tmp/a.txt").then().statusCode(200);
        given().body("keep").when().put("/n/" + NS + "/b/" + bucket + "/o/keep.txt").then().statusCode(200);
        String uploadId = given().contentType("application/json").body(Map.of("object", "big.bin"))
            .when().post("/n/" + NS + "/b/" + bucket + "/u")
            .then().statusCode(200).extract().path("uploadId");
        given().contentType("application/json")
                .body(Map.of("items", List.of(
                        rule("tmp", "DELETE", 1, "objects", Map.of("inclusionPrefixes", List.of("tmp/"))),
                        rule("uploads", "ABORT", 1, "multipart-uploads", null))))
            .when().put("/n/" + NS + "/b/" + bucket + "/l")
            .then().statusCode(200);

        sweeper.sweep(Instant.now());
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/tmp/a.txt").then().statusCode(200);

        sweeper.sweep(Instant.now().plus(Duration.ofDays(3)));
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/tmp/a.txt").then().statusCode(404);
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/keep.txt").then().statusCode(200);
        given().when().get("/n/" + NS + "/b/" + bucket + "/u")
            .then().statusCode(200).body("uploadId", not(hasItem(uploadId)));
    }
}
