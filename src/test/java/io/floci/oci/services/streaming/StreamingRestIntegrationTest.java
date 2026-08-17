package io.floci.oci.services.streaming;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class StreamingRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..itstreamcompartment";

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    /** CreateStream is dual-mode: full body AND opc-work-request-id. */
    private String createStream(String name, int partitions) {
        var response = given()
                .contentType("application/json")
                .body(Map.of("name", name, "partitions", partitions, "compartmentId", COMPARTMENT))
            .when().post("/20180418/streams")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-work-request-id", startsWith("ocid1."))
                .body("id", startsWith("ocid1.stream."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("messagesEndpoint", notNullValue())
                .body("retentionInHours", equalTo(24));

        String streamId = response.extract().path("id");
        String workRequestId = response.extract().header("opc-work-request-id");

        // Terraform reads the stream OCID off the WR resources.
        given()
            .when().get("/20180418/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].identifier", equalTo(streamId))
                .body("timeFinished", notNullValue());
        return streamId;
    }

    @Test
    void streamCrudRoundtrip() {
        String streamId = createStream("it-stream-" + System.nanoTime(), 2);

        given()
            .when().get("/20180418/streams/" + streamId)
            .then().statusCode(200)
                .body("partitions", equalTo(2))
                .body("retentionInHours", equalTo(24));

        // Bare-array list; StreamSummary must NOT carry retentionInHours.
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20180418/streams")
            .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("find { it.id == '" + streamId + "' }.retentionInHours", nullValue())
                .body("find { it.id == '" + streamId + "' }.messagesEndpoint", notNullValue());

        given()
            .when().delete("/20180418/streams/" + streamId)
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue());

        given()
            .when().get("/20180418/streams/" + streamId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void produceAndConsumeWithCursorChain() {
        String streamId = createStream("it-consume-" + System.nanoTime(), 1);
        String base = "/20180418/streams/" + streamId;

        given()
            .contentType("application/json")
            .body(Map.of("messages", List.of(
                    Map.of("key", b64("k1"), "value", b64("first")),
                    Map.of("value", b64("second")))))
            .when().post(base + "/messages")
            .then().statusCode(200)
                .body("failures", equalTo(0))
                .body("entries", hasSize(2))
                .body("entries[0].partition", equalTo("0"))
                .body("entries[0].offset", equalTo(0))
                .body("entries[0].timestamp", notNullValue());

        String cursor = given()
                .contentType("application/json")
                .body(Map.of("partition", "0", "type", "TRIM_HORIZON"))
            .when().post(base + "/cursors")
            .then().statusCode(200)
                .body("value", notNullValue())
                .extract().path("value");

        // Bare array body + opc-next-cursor header.
        String nextCursor = given()
                .queryParam("cursor", cursor)
                .queryParam("limit", 1)
            .when().get(base + "/messages")
            .then().statusCode(200)
                .header("opc-next-cursor", notNullValue())
                .body("size()", equalTo(1))
                .body("[0].value", equalTo(b64("first")))
                .body("[0].offset", equalTo(0))
                .body("[0].partition", equalTo("0"))
            .extract().header("opc-next-cursor");

        given()
            .queryParam("cursor", nextCursor)
            .when().get(base + "/messages")
            .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].value", equalTo(b64("second")));
    }

    @Test
    void groupCursorCommitAndResume() {
        String streamId = createStream("it-group-" + System.nanoTime(), 2);
        String base = "/20180418/streams/" + streamId;

        given().contentType("application/json")
            .body(Map.of("messages", List.of(
                    Map.of("value", b64("a")), Map.of("value", b64("b")))))
            .when().post(base + "/messages").then().statusCode(200);

        String cursor = given()
                .contentType("application/json")
                .body(Map.of("groupName", "workers", "type", "TRIM_HORIZON"))
            .when().post(base + "/groupCursors")
            .then().statusCode(200).extract().path("value");

        String nextCursor = given()
                .queryParam("cursor", cursor)
            .when().get(base + "/messages")
            .then().statusCode(200)
                .body("size()", equalTo(2))
            .extract().header("opc-next-cursor");

        // Commit: cursor in the query string, empty body, Cursor response.
        given()
            .queryParam("cursor", nextCursor)
            .when().post(base + "/commit")
            .then().statusCode(200).body("value", notNullValue());

        given()
            .when().get(base + "/groups/workers")
            .then().statusCode(200)
                .body("groupName", equalTo("workers"))
                .body("reservations", hasSize(2));

        // A new group cursor resumes past the committed messages.
        String resumed = given()
                .contentType("application/json")
                .body(Map.of("groupName", "workers", "type", "TRIM_HORIZON"))
            .when().post(base + "/groupCursors")
            .then().statusCode(200).extract().path("value");

        given()
            .queryParam("cursor", resumed)
            .when().get(base + "/messages")
            .then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void heartbeatKeepsCursorAlive() {
        String streamId = createStream("it-heartbeat-" + System.nanoTime(), 1);
        String cursor = given()
                .contentType("application/json")
                .body(Map.of("partition", "0", "type", "LATEST"))
            .when().post("/20180418/streams/" + streamId + "/cursors")
            .then().statusCode(200).extract().path("value");

        given()
            .queryParam("cursor", cursor)
            .when().post("/20180418/streams/" + streamId + "/heartbeat")
            .then().statusCode(200).body("value", equalTo(cursor));
    }

    @Test
    void invalidCursorIs400() {
        String streamId = createStream("it-badcursor-" + System.nanoTime(), 1);
        given()
            .queryParam("cursor", "not-a-cursor")
            .when().get("/20180418/streams/" + streamId + "/messages")
            .then().statusCode(400)
                .body("code", equalTo("InvalidParameter"));
    }
}
