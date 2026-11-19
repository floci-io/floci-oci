package io.floci.oci.services.objectstorage;

import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestRegions;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Object Storage replication over the wire, from an Ashburn source to a Phoenix destination. */
@QuarkusTest
class ObjectStorageReplicationRestIntegrationTest {

    private static final String NS = "floci-test";
    private static final String TENANCY = "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String PHOENIX = "objectstorage.us-phoenix-1.oraclecloud.com";
    private static String compartment;

    @BeforeEach
    void setUp() {
        if (compartment == null) {
            compartment = TestCompartments.create("itreplicationcompartment");
        }
        TestRegions.subscribe(TENANCY, "PHX");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static void createBucket(String host, String name) {
        given().header("Host", host).contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(200);
    }

    private static ValidatableResponse createPolicy(String source, String destinationRegion, String destination) {
        return given().contentType("application/json")
                .body(Map.of("name", "it-policy", "destinationRegionName", destinationRegion,
                        "destinationBucketName", destination))
            .when().post("/n/" + NS + "/b/" + source + "/replicationPolicies")
            .then();
    }

    /** Buckets {@code [source in Ashburn, destination in Phoenix]} with an active policy between them. */
    private static String[] replicatedPair() {
        String source = unique("it-repl-src");
        String destination = unique("it-repl-dst");
        createBucket(PHOENIX, destination);
        createBucket("localhost", source);
        createPolicy(source, "us-phoenix-1", destination).statusCode(200);
        return new String[] {source, destination};
    }

    private static String policyId(String source) {
        return given().when().get("/n/" + NS + "/b/" + source + "/replicationPolicies")
            .then().statusCode(200).extract().path("[0].id");
    }

    @Test
    void createPolicy_answersThePolicyWithNoSyncYet() {
        String source = unique("it-repl-src");
        String destination = unique("it-repl-dst");
        createBucket(PHOENIX, destination);
        createBucket("localhost", source);

        createPolicy(source, "us-phoenix-1", destination)
            .statusCode(200)
            .header("opc-request-id", notNullValue())
            .body("id", matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            .body("name", equalTo("it-policy"))
            .body("destinationRegionName", equalTo("us-phoenix-1"))
            .body("destinationBucketName", equalTo(destination))
            .body("timeCreated", notNullValue())
            .body("$", hasKey("timeLastSync"))
            .body("timeLastSync", nullValue())
            .body("status", equalTo("ACTIVE"))
            .body("statusMessage", equalTo("The policy is active."));

        given().when().get("/n/" + NS + "/b/" + source)
            .then().statusCode(200)
                .body("replicationEnabled", equalTo(true))
                .body("isReadOnly", equalTo(false));
        given().header("Host", PHOENIX).when().get("/n/" + NS + "/b/" + destination)
            .then().statusCode(200)
                .body("replicationEnabled", equalTo(false))
                .body("isReadOnly", equalTo(true));
    }

    @Test
    void aPlainBucketCarriesBothReplicationFlags() {
        String bucket = unique("it-repl-plain");
        createBucket("localhost", bucket);

        given().when().get("/n/" + NS + "/b/" + bucket)
            .then().statusCode(200)
                .body("replicationEnabled", equalTo(false))
                .body("isReadOnly", equalTo(false));
    }

    @Test
    void listsAreBareArrays() {
        String[] pair = replicatedPair();
        String id = policyId(pair[0]);

        given().when().get("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies?limit=1")
            .then().statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("opc-next-page", nullValue())
                .body("$", hasSize(1))
                .body("[0].id", equalTo(id))
                .body("[0].status", equalTo("ACTIVE"));
        given().when().get("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies/" + id)
            .then().statusCode(200).body("id", equalTo(id));
        given().header("Host", PHOENIX).when().get("/n/" + NS + "/b/" + pair[1] + "/replicationSources")
            .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].policyName", equalTo("it-policy"))
                .body("[0].sourceRegionName", equalTo("us-ashburn-1"))
                .body("[0].sourceBucketName", equalTo(pair[0]));
    }

    @Test
    void anObjectWrittenToTheSourceIsReadableInTheDestinationRegion() {
        String[] pair = replicatedPair();
        String etag = given().contentType("text/plain").header("opc-meta-owner", "it").body("replicated")
            .when().put("/n/" + NS + "/b/" + pair[0] + "/o/a.txt")
            .then().statusCode(200).extract().header("etag");

        given().header("Host", PHOENIX)
            .when().get("/n/" + NS + "/b/" + pair[1] + "/o/a.txt")
            .then().statusCode(200)
                .header("etag", equalTo(etag))
                .header("opc-meta-owner", equalTo("it"))
                .body(equalTo("replicated"));
        given().when().get("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies/" + policyId(pair[0]))
            .then().statusCode(200).body("timeLastSync", notNullValue());

        given().when().delete("/n/" + NS + "/b/" + pair[0] + "/o/a.txt").then().statusCode(204);
        given().header("Host", PHOENIX)
            .when().get("/n/" + NS + "/b/" + pair[1] + "/o/a.txt")
            .then().statusCode(404).body("code", equalTo("ObjectNotFound"));
    }

    @Test
    void theDestinationIsReadOnly() {
        String[] pair = replicatedPair();

        given().header("Host", PHOENIX).contentType("text/plain").body("direct")
            .when().put("/n/" + NS + "/b/" + pair[1] + "/o/direct.txt")
            .then().statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"))
                .body("message", equalTo("Bucket '" + pair[1] + "' is the destination of a replication policy and"
                        + " is read-only. Make it writable to change its contents."));
        given().header("Host", PHOENIX)
            .when().delete("/n/" + NS + "/b/" + pair[1])
            .then().statusCode(409).body("code", equalTo("Conflict"));
    }

    @Test
    void theSourceCannotBeDeletedWhileItReplicates() {
        String[] pair = replicatedPair();

        given().when().delete("/n/" + NS + "/b/" + pair[0])
            .then().statusCode(403)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("BucketReplicationEnabled"))
                .body("message", equalTo("Bucket named '" + pair[0] + "' has replication enabled. "));
    }

    @Test
    void aSecondPolicyOnTheSourceIsAConflict() {
        String[] pair = replicatedPair();
        String other = unique("it-repl-other");
        createBucket(PHOENIX, other);

        createPolicy(pair[0], "us-phoenix-1", other)
            .statusCode(409)
            .body("code", equalTo("ReplicationPolicyConflict"))
            .body("message", equalTo("Replication policy exists for bucket '" + pair[0] + "' in namespace '"
                    + NS + "'."));
    }

    @Test
    void createPolicy_rejectsBadInput() {
        String source = unique("it-repl-bad");
        createBucket("localhost", source);

        given().contentType("application/json").body(Map.of("name", "p", "destinationBucketName", "x"))
            .when().post("/n/" + NS + "/b/" + source + "/replicationPolicies")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        createPolicy(source, "xx-nowhere-1", "x").statusCode(400).body("code", equalTo("InvalidParameter"));
        createPolicy(source, "us-phoenix-1", unique("missing")).statusCode(404).body("code", equalTo("BucketNotFound"));
        given().when().get("/n/" + NS + "/b/" + source + "/replicationPolicies/no-such-id")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void deletePolicy_makesBothBucketsOrdinaryAgain() {
        String[] pair = replicatedPair();

        given().when().delete("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies/" + policyId(pair[0]))
            .then().statusCode(204).header("opc-request-id", notNullValue());

        given().when().get("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies")
            .then().statusCode(200).body("$", empty());
        given().when().get("/n/" + NS + "/b/" + pair[0]).then().body("replicationEnabled", equalTo(false));
        given().header("Host", PHOENIX).when().get("/n/" + NS + "/b/" + pair[1])
            .then().body("isReadOnly", equalTo(false));
        given().when().delete("/n/" + NS + "/b/" + pair[0]).then().statusCode(204);
    }

    @Test
    void makeBucketWritable_leavesTheSourcePolicyInClientError() {
        String[] pair = replicatedPair();

        given().header("Host", PHOENIX)
            .when().post("/n/" + NS + "/b/" + pair[1] + "/actions/makeBucketWritable")
            .then().statusCode(200).header("opc-request-id", notNullValue());

        given().header("Host", PHOENIX).when().get("/n/" + NS + "/b/" + pair[1])
            .then().body("isReadOnly", equalTo(false));
        given().header("Host", PHOENIX).when().get("/n/" + NS + "/b/" + pair[1] + "/replicationSources")
            .then().statusCode(200).body("$", empty());
        given().when().get("/n/" + NS + "/b/" + pair[0] + "/replicationPolicies")
            .then().statusCode(200)
                .body("[0].status", equalTo("CLIENT_ERROR"))
                .body("[0].statusMessage", containsString("made writable"));
        given().header("Host", PHOENIX).contentType("text/plain").body("now writable")
            .when().put("/n/" + NS + "/b/" + pair[1] + "/o/direct.txt")
            .then().statusCode(200);
    }
}
