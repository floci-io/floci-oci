package io.floci.oci.services.objectstorage;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class ObjectStorageRestIntegrationTest {

    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..ittestcompartment";

    private static String uniqueBucket(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private void createBucket(String name) {
        given()
            .contentType("application/json")
            .body(Map.of("name", name, "compartmentId", COMPARTMENT))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("namespace", equalTo(NS))
                .body("versioning", equalTo("Disabled"));
    }

    @Test
    void namespaceEndpoints() {
        given()
            .when().get("/n")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body(equalTo("\"" + NS + "\""));

        given()
            .when().get("/n/" + NS)
            .then().statusCode(200)
                .body("namespace", equalTo(NS));
    }

    @Test
    void bucketCrudRoundtrip() {
        String bucket = uniqueBucket("it-bucket");
        createBucket(bucket);

        given()
            .contentType("application/json")
            .body(Map.of("name", bucket, "compartmentId", COMPARTMENT))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(409)
                .body("code", equalTo("BucketAlreadyExists"));

        given()
            .when().get("/n/" + NS + "/b/" + bucket)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("name", equalTo(bucket))
                .body("id", startsWith("ocid1.bucket."));

        given()
            .when().head("/n/" + NS + "/b/" + bucket)
            .then().statusCode(200).header("etag", notNullValue());

        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/n/" + NS + "/b")
            .then().statusCode(200)
                .body("find { it.name == '" + bucket + "' }.namespace", equalTo(NS));

        given()
            .when().delete("/n/" + NS + "/b/" + bucket)
            .then().statusCode(204);

        given()
            .when().get("/n/" + NS + "/b/" + bucket)
            .then().statusCode(404)
                .body("code", equalTo("BucketNotFound"));
    }

    @Test
    void objectPutGetHeadDeleteWithMetadataAndRange() {
        String bucket = uniqueBucket("it-objects");
        createBucket(bucket);

        given()
            .contentType("text/plain")
            .header("opc-meta-owner", "integration-test")
            .body("hello object storage")
            .when().put("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-content-md5", notNullValue());

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-meta-owner", equalTo("integration-test"))
                .body(equalTo("hello object storage"));

        given()
            .header("Range", "bytes=0-4")
            .when().get("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(206)
                .header("content-range", equalTo("bytes 0-4/20"))
                .body(equalTo("hello"));

        given()
            .when().head("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(200)
                .header("content-length", equalTo("20"))
                .header("etag", notNullValue());

        given()
            .when().delete("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(204);

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o/greeting.txt")
            .then().statusCode(404)
                .body("code", equalTo("ObjectNotFound"));

        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void objectNamesWithSlashesAndListing() {
        String bucket = uniqueBucket("it-list");
        createBucket(bucket);

        for (String name : List.of("logs/2026/01.log", "logs/2026/02.log", "readme.md")) {
            given().contentType("application/octet-stream").body("x".getBytes())
                .when().put("/n/" + NS + "/b/" + bucket + "/o/" + name)
                .then().statusCode(200);
        }

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o")
            .then().statusCode(200)
                .body("objects", hasSize(3))
                .body("objects[0].name", equalTo("logs/2026/01.log"))
                .body("objects[0].size", equalTo(1))
                .body("objects[0].md5", notNullValue());

        given()
            .queryParam("delimiter", "/")
            .when().get("/n/" + NS + "/b/" + bucket + "/o")
            .then().statusCode(200)
                .body("objects", hasSize(1))
                .body("prefixes", equalTo(List.of("logs/")));

        given()
            .queryParam("prefix", "logs/")
            .queryParam("limit", 1)
            .when().get("/n/" + NS + "/b/" + bucket + "/o")
            .then().statusCode(200)
                .body("objects", hasSize(1))
                .body("nextStartWith", equalTo("logs/2026/02.log"));

        for (String name : List.of("logs/2026/01.log", "logs/2026/02.log", "readme.md")) {
            given().when().delete("/n/" + NS + "/b/" + bucket + "/o/" + name).then().statusCode(204);
        }
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void renameAndCopyActions() {
        String bucket = uniqueBucket("it-actions");
        createBucket(bucket);
        given().contentType("text/plain").body("payload")
            .when().put("/n/" + NS + "/b/" + bucket + "/o/original.txt")
            .then().statusCode(200);

        given()
            .contentType("application/json")
            .body(Map.of("sourceName", "original.txt", "newName", "renamed.txt"))
            .when().post("/n/" + NS + "/b/" + bucket + "/actions/renameObject")
            .then().statusCode(200)
                .header("etag", notNullValue());

        String workRequestId = given()
                .contentType("application/json")
                .body(Map.of("sourceObjectName", "renamed.txt",
                        "destinationRegion", "us-ashburn-1",
                        "destinationNamespace", NS,
                        "destinationBucket", bucket,
                        "destinationObjectName", "copied.txt"))
            .when().post("/n/" + NS + "/b/" + bucket + "/actions/copyObject")
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");

        // Object Storage exposes work requests unversioned at the root.
        given()
            .when().get("/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("operationType", equalTo("COPY_OBJECT"));

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o/copied.txt")
            .then().statusCode(200).body(equalTo("payload"));

        for (String name : List.of("renamed.txt", "copied.txt")) {
            given().when().delete("/n/" + NS + "/b/" + bucket + "/o/" + name).then().statusCode(204);
        }
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void multipartUploadRoundtrip() {
        String bucket = uniqueBucket("it-multipart");
        createBucket(bucket);

        String uploadId = given()
                .contentType("application/json")
                .body(Map.of("object", "assembled.bin"))
            .when().post("/n/" + NS + "/b/" + bucket + "/u")
            .then().statusCode(200)
                .body("bucket", equalTo(bucket))
                .body("object", equalTo("assembled.bin"))
                .extract().path("uploadId");

        String etag1 = given()
                .contentType("application/octet-stream").body("hello ".getBytes())
                .queryParam("uploadId", uploadId).queryParam("uploadPartNum", 1)
            .when().put("/n/" + NS + "/b/" + bucket + "/u/assembled.bin")
            .then().statusCode(200)
                .header("opc-content-md5", notNullValue())
                .extract().header("etag");

        String etag2 = given()
                .contentType("application/octet-stream").body("multipart".getBytes())
                .queryParam("uploadId", uploadId).queryParam("uploadPartNum", 2)
            .when().put("/n/" + NS + "/b/" + bucket + "/u/assembled.bin")
            .then().statusCode(200).extract().header("etag");

        given()
            .contentType("application/json")
            .queryParam("uploadId", uploadId)
            .body(Map.of("partsToCommit", List.of(
                    Map.of("partNum", 1, "etag", etag1),
                    Map.of("partNum", 2, "etag", etag2))))
            .when().post("/n/" + NS + "/b/" + bucket + "/u/assembled.bin")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-multipart-md5", notNullValue());

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o/assembled.bin")
            .then().statusCode(200).body(equalTo("hello multipart"));

        given().when().delete("/n/" + NS + "/b/" + bucket + "/o/assembled.bin").then().statusCode(204);
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void preauthenticatedRequestGrantsAnonymousAccess() {
        String bucket = uniqueBucket("it-par");
        createBucket(bucket);
        given().contentType("text/plain").body("par-payload")
            .when().put("/n/" + NS + "/b/" + bucket + "/o/shared.txt")
            .then().statusCode(200);

        String accessUri = given()
                .contentType("application/json")
                .body(Map.of("name", "share", "accessType", "ObjectRead",
                        "timeExpires", "2999-01-01T00:00:00Z", "objectName", "shared.txt"))
            .when().post("/n/" + NS + "/b/" + bucket + "/p")
            .then().statusCode(200)
                .body("id", notNullValue())
                .body("accessUri", startsWith("/p/"))
                .extract().path("accessUri");

        // Anonymous data-path read through the PAR.
        given()
            .when().get(accessUri)
            .then().statusCode(200).body(equalTo("par-payload"));

        // A read-only PAR must not allow writes.
        given().contentType("text/plain").body("overwrite")
            .when().put(accessUri)
            .then().statusCode(404);

        String parId = given()
                .when().get("/n/" + NS + "/b/" + bucket + "/p")
                .then().statusCode(200).body("$", hasSize(1))
                .extract().path("[0].id");

        given().when().delete("/n/" + NS + "/b/" + bucket + "/p/" + parId).then().statusCode(204);
        given().when().get(accessUri).then().statusCode(404);

        given().when().delete("/n/" + NS + "/b/" + bucket + "/o/shared.txt").then().statusCode(204);
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void listRetentionRulesAnswersEmptyCollection() {
        // Terraform's bucket Read calls ListRetentionRules unconditionally — a 404 here
        // makes the provider silently drop the bucket from state.
        String bucket = uniqueBucket("it-retention");
        createBucket(bucket);
        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/retentionRules")
            .then().statusCode(200)
                .body("items", hasSize(0));
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void contentMd5MismatchIs400() {
        String bucket = uniqueBucket("it-md5");
        createBucket(bucket);
        given()
            .contentType("application/octet-stream")
            .header("Content-MD5", "bm90LXRoZS1yaWdodC1tZDU=")
            .body("abc".getBytes())
            .when().put("/n/" + NS + "/b/" + bucket + "/o/bad.bin")
            .then().statusCode(400)
                .body("code", equalTo("InvalidParameter"));
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }
}
