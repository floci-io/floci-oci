package io.floci.oci.services.queue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class QueueRestIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..itqueuecompartment";

    /** Creates a queue through the wire contract: 202 + opc-work-request-id, then WR poll. */
    private String createQueue(String name) {
        String workRequestId = given()
                .contentType("application/json")
                .body(Map.of("displayName", name, "compartmentId", COMPARTMENT))
            .when().post("/20210201/queues")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", startsWith("ocid1."))
                .extract().header("opc-work-request-id");

        // Terraform's flow: first GetWorkRequest must already carry the queue OCID.
        return given()
            .when().get("/20210201/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("operationType", equalTo("CREATE_QUEUE"))
                .body("timeFinished", notNullValue())
                .body("resources[0].entityType", equalTo("QUEUE"))
                .body("resources[0].actionType", equalTo("CREATED"))
                .extract().path("resources[0].identifier");
    }

    @Test
    void queueCrudRoundtrip() {
        String queueId = createQueue("it-queue-" + System.nanoTime());

        given()
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(queueId))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("messagesEndpoint", notNullValue())
                .body("retentionInSeconds", equalTo(86400))
                .body("visibilityInSeconds", equalTo(30));

        // Wrapped list — {"items":[...]}, unlike Identity's bare arrays.
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20210201/queues")
            .then().statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.find { it.id == '" + queueId + "' }.messagesEndpoint", notNullValue());

        given()
            .contentType("application/json")
            .body(Map.of("displayName", "it-renamed"))
            .when().put("/20210201/queues/" + queueId)
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue());

        given()
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(200).body("displayName", equalTo("it-renamed"));

        given()
            .when().delete("/20210201/queues/" + queueId)
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue());

        given()
            .when().get("/20210201/queues/" + queueId)
            .then().statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void messageLifecycleThroughTheDataPlane() {
        String queueId = createQueue("it-messages-" + System.nanoTime());
        String base = "/20210201/queues/" + queueId + "/messages";

        given()
            .contentType("application/json")
            .body(Map.of("messages", List.of(
                    Map.of("content", "hello queue"),
                    Map.of("content", "second", "metadata", Map.of("channelId", "orders")))))
            .when().post(base)
            .then().statusCode(200)
                .body("messages", hasSize(2))
                .body("messages[0].id", equalTo(1))
                .body("messages[0].expireAfter", notNullValue());

        String receipt = given()
                .queryParam("timeoutInSeconds", 0)
                .queryParam("limit", 10)
            .when().get(base)
            .then().statusCode(200)
                .body("messages", hasSize(2))
                .body("messages[0].content", equalTo("hello queue"))
                .body("messages[0].deliveryCount", equalTo(1))
                .body("messages[1].metadata.channelId", equalTo("orders"))
                .extract().path("messages[0].receipt");

        // Both are now in flight.
        given()
            .queryParam("timeoutInSeconds", 0)
            .when().get(base)
            .then().statusCode(200).body("messages", hasSize(0));

        given()
            .when().get("/20210201/queues/" + queueId + "/stats")
            .then().statusCode(200)
                .body("queue.inFlightMessages", equalTo(2))
                .body("queue.visibleMessages", equalTo(0))
                .body("dlq.visibleMessages", equalTo(0));

        given()
            .when().delete(base + "/" + receipt)
            .then().statusCode(204);

        given()
            .when().get("/20210201/queues/" + queueId + "/channels")
            .then().statusCode(200)
                .body("items", equalTo(List.of("orders")));

        given().when().delete("/20210201/queues/" + queueId).then().statusCode(202);
    }

    @Test
    void updateMessageAndBatchOperations() {
        String queueId = createQueue("it-batch-" + System.nanoTime());
        String base = "/20210201/queues/" + queueId + "/messages";

        given().contentType("application/json")
            .body(Map.of("messages", List.of(Map.of("content", "batch-me"))))
            .when().post(base).then().statusCode(200);

        String receipt = given()
                .queryParam("timeoutInSeconds", 0)
            .when().get(base)
            .then().statusCode(200)
                .extract().path("messages[0].receipt");

        given()
            .contentType("application/json")
            .body(Map.of("visibilityInSeconds", 300))
            .when().put(base + "/" + receipt)
            .then().statusCode(200)
                .body("id", equalTo(1))
                .body("visibleAfter", notNullValue());

        given()
            .contentType("application/json")
            .body(Map.of("entries", List.of(
                    Map.of("receipt", receipt), Map.of("receipt", "bogus"))))
            .when().post(base + "/actions/deleteMessages")
            .then().statusCode(200)
                .body("serverFailures", equalTo(1))
                .body("entries", hasSize(2))
                .body("entries[1].errorCode", equalTo(404));

        given().when().delete("/20210201/queues/" + queueId).then().statusCode(202);
    }

    @Test
    void purgeActionEmptiesTheQueue() {
        String queueId = createQueue("it-purge-" + System.nanoTime());
        given().contentType("application/json")
            .body(Map.of("messages", List.of(Map.of("content", "gone-soon"))))
            .when().post("/20210201/queues/" + queueId + "/messages")
            .then().statusCode(200);

        given()
            .contentType("application/json")
            .body(Map.of("purgeType", "BOTH"))
            .when().post("/20210201/queues/" + queueId + "/actions/purge")
            .then().statusCode(202)
                .header("opc-work-request-id", notNullValue());

        given()
            .when().get("/20210201/queues/" + queueId + "/stats")
            .then().statusCode(200)
                .body("queue.visibleMessages", equalTo(0));

        given().when().delete("/20210201/queues/" + queueId).then().statusCode(202);
    }

    @Test
    void queueWorkRequestListIsWrappedAndScoped() {
        String queueId = createQueue("it-wr-" + System.nanoTime());
        given()
            .queryParam("compartmentId", COMPARTMENT)
            .when().get("/20210201/workRequests")
            .then().statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.findAll { it.operationType == 'CREATE_QUEUE' }.size()",
                        greaterThanOrEqualTo(1));
        given().when().delete("/20210201/queues/" + queueId).then().statusCode(202);
    }
}
