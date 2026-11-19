package io.floci.oci.services.objectstorage;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Object versioning over the wire: headers, the ListObjectVersions body, and the bucket rules. */
@QuarkusTest
class ObjectStorageVersioningRestIntegrationTest {

    private static final String NS = "floci-test";
    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itversioning");
        }
    }

    private static String bucket(String versioning) {
        String name = "ver-" + System.nanoTime();
        given().contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment, "versioning", versioning))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(200).body("versioning", equalTo(versioning));
        return name;
    }

    private static String put(String bucket, String name, String body) {
        return given().body(body).when().put("/n/" + NS + "/b/" + bucket + "/o/" + name)
            .then().statusCode(200).header("version-id", notNullValue())
            .extract().header("version-id");
    }

    @Test
    void unversionedBucketsStillReturnVersionIds() {
        String bucket = bucket("Disabled");
        String versionId = put(bucket, "a.txt", "hello");
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/a.txt")
            .then().statusCode(200).header("version-id", equalTo(versionId)).body(equalTo("hello"));
        given().when().head("/n/" + NS + "/b/" + bucket + "/o/a.txt")
            .then().statusCode(200).header("version-id", equalTo(versionId));

        // A permanent delete answers with last-modified only, as recorded against real OCI.
        given().when().delete("/n/" + NS + "/b/" + bucket + "/o/a.txt")
            .then().statusCode(204)
                .header("last-modified", notNullValue())
                .header("version-id", nullValue())
                .header("is-delete-marker", nullValue());
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void versionsMarkersAndListObjectVersions() {
        String bucket = bucket("Enabled");
        String v1 = put(bucket, "doc.txt", "one");
        String v2 = put(bucket, "doc.txt", "two");

        given().queryParam("versionId", v1).when().get("/n/" + NS + "/b/" + bucket + "/o/doc.txt")
            .then().statusCode(200).header("version-id", equalTo(v1)).body(equalTo("one"));

        String marker = given().when().delete("/n/" + NS + "/b/" + bucket + "/o/doc.txt")
            .then().statusCode(204)
                .header("is-delete-marker", equalTo("true"))
                .header("last-modified", notNullValue())
                .extract().header("version-id");
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/doc.txt")
            .then().statusCode(404).body("code", equalTo("ObjectNotFound"));

        given().when().get("/n/" + NS + "/b/" + bucket + "/objectversions")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .body("items.versionId", contains(marker, v2, v1))
                .body("items.isDeleteMarker", contains(true, false, false))
                .body("items[1].timeModified", notNullValue())
                .body("items[1].size", nullValue())
                .body("$", not(hasKey("prefixes")));

        given().queryParam("fields", "name,size,etag,md5,timeCreated,storageTier")
            .when().get("/n/" + NS + "/b/" + bucket + "/objectversions")
            .then().statusCode(200)
                .body("items[0].size", nullValue())
                .body("items[1].size", equalTo(3))
                .body("items[1].md5", notNullValue())
                .body("items[1].storageTier", equalTo("Standard"));

        given().queryParam("limit", 1).when().get("/n/" + NS + "/b/" + bucket + "/objectversions")
            .then().statusCode(200).header("opc-next-page", notNullValue()).body("items", hasSize(1));

        given().when().delete("/n/" + NS + "/b/" + bucket)
            .then().statusCode(409).body("code", equalTo("BucketNotEmpty"));

        for (String versionId : new String[]{marker, v2, v1}) {
            given().queryParam("versionId", versionId).when().delete("/n/" + NS + "/b/" + bucket + "/o/doc.txt")
                .then().statusCode(204).header("is-delete-marker", nullValue());
        }
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void versioningIsSuspendedNotDisabled() {
        String bucket = bucket("Enabled");
        given().contentType("application/json").body(Map.of("versioning", "Suspended"))
            .when().post("/n/" + NS + "/b/" + bucket)
            .then().statusCode(200).body("versioning", equalTo("Suspended"));
        given().contentType("application/json").body(Map.of("versioning", "Disabled"))
            .when().post("/n/" + NS + "/b/" + bucket)
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
        given().when().delete("/n/" + NS + "/b/" + bucket).then().statusCode(204);
    }

    @Test
    void renameCommitAndCopyCarryVersions() {
        String bucket = bucket("Enabled");
        String v1 = put(bucket, "src", "first");
        put(bucket, "src", "second");

        given().contentType("application/json").body(Map.of("sourceName", "src", "newName", "dst"))
            .when().post("/n/" + NS + "/b/" + bucket + "/actions/renameObject")
            .then().statusCode(200).header("version-id", notNullValue());

        given().contentType("application/json")
                .body(Map.of("sourceObjectName", "src", "sourceVersionId", v1, "destinationRegion", "us-ashburn-1",
                        "destinationNamespace", NS, "destinationBucket", bucket, "destinationObjectName", "restored"))
            .when().post("/n/" + NS + "/b/" + bucket + "/actions/copyObject")
            .then().statusCode(202);
        given().when().get("/n/" + NS + "/b/" + bucket + "/o/restored").then().statusCode(200).body(equalTo("first"));

        String uploadId = given().contentType("application/json").body(Map.of("object", "mp"))
            .when().post("/n/" + NS + "/b/" + bucket + "/u")
            .then().statusCode(200).extract().path("uploadId");
        String etag = given().body("part").queryParam("uploadId", uploadId).queryParam("uploadPartNum", 1)
            .when().put("/n/" + NS + "/b/" + bucket + "/u/mp")
            .then().statusCode(200).extract().header("etag");
        given().contentType("application/json")
                .body(Map.of("partsToCommit", List.of(Map.of("partNum", 1, "etag", etag))))
                .queryParam("uploadId", uploadId)
            .when().post("/n/" + NS + "/b/" + bucket + "/u/mp")
            .then().statusCode(200).header("version-id", notNullValue());
    }
}
