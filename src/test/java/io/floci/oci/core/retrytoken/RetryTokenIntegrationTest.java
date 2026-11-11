package io.floci.oci.core.retrytoken;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class RetryTokenIntegrationTest {

    private static final String NS = "floci-test";

    @Inject
    EmulatorConfig config;

    private static String token(String label) {
        return label + "-" + System.nanoTime();
    }

    private ExtractableResponse<Response> createCompartment(String name, String retryToken) {
        return given()
                .contentType("application/json")
                .header("opc-retry-token", retryToken)
                .body(Map.of("compartmentId", config.defaultTenancyId(), "name", name, "description", "retry"))
            .when().post("/20160918/compartments")
            .then().extract();
    }

    private long compartmentsNamed(String name) {
        List<String> names = given()
                .queryParam("compartmentId", config.defaultTenancyId())
            .when().get("/20160918/compartments")
            .then().statusCode(200)
                .extract().jsonPath().getList("name", String.class);
        return names.stream().filter(name::equals).count();
    }

    @Test
    void aRetryWithTheSameTokenReplaysTheFirstResponse() {
        String name = "retry-replay-" + System.nanoTime();
        String retryToken = token("replay");

        ExtractableResponse<Response> first = createCompartment(name, retryToken);
        ExtractableResponse<Response> retry = createCompartment(name, retryToken);

        assertEquals(200, first.statusCode());
        assertEquals(200, retry.statusCode());
        assertEquals(first.jsonPath().getString("id"), retry.jsonPath().getString("id"));
        assertEquals(first.header("etag"), retry.header("etag"));
        assertEquals(first.contentType(), retry.contentType());
        assertNotEquals(first.header("opc-request-id"), retry.header("opc-request-id"),
                "every HTTP request keeps a request id of its own");
        assertEquals(1, compartmentsNamed(name));
    }

    @Test
    void anAsyncCreateReplaysItsWorkRequest() {
        String compartment = TestCompartments.create("retryqueue");
        String retryToken = token("queue");
        Map<String, Object> body = Map.of("displayName", "retry-queue-" + System.nanoTime(),
                "compartmentId", compartment);

        String first = given().contentType("application/json").header("opc-retry-token", retryToken).body(body)
            .when().post("/20210201/queues")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String retry = given().contentType("application/json").header("opc-retry-token", retryToken).body(body)
            .when().post("/20210201/queues")
            .then().statusCode(202).extract().header("opc-work-request-id");

        assertEquals(first, retry);
        List<String> queues = given().queryParam("compartmentId", compartment)
            .when().get("/20210201/queues")
            .then().statusCode(200).extract().jsonPath().getList("items.id", String.class);
        assertEquals(1, queues.size());
    }

    @Test
    void differentTokensAndNoTokenEachCreateTheirOwnResource() {
        String name = "retry-distinct-" + System.nanoTime();

        String a = createCompartment(name + "-a", token("a")).jsonPath().getString("id");
        String b = createCompartment(name + "-b", token("b")).jsonPath().getString("id");
        String c = given().contentType("application/json")
                .body(Map.of("compartmentId", config.defaultTenancyId(), "name", name + "-c", "description", "x"))
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().jsonPath().getString("id");

        assertNotEquals(a, b);
        assertNotEquals(b, c);
    }

    @Test
    void theSameTokenWithADifferentRequestIsAConflict() {
        String retryToken = token("conflict");
        createCompartment("retry-conflict-" + System.nanoTime(), retryToken);

        given()
                .contentType("application/json")
                .header("opc-retry-token", retryToken)
                .body(Map.of("compartmentId", config.defaultTenancyId(), "name", "another-" + System.nanoTime(),
                        "description", "retry"))
            .when().post("/20160918/compartments")
            .then()
                .statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"))
                .body("message", equalTo("The opc-retry-token was already used with a different request."));
    }

    @Test
    void fieldOrderDoesNotMakeARetryADifferentRequest() {
        String name = "retry-order-" + System.nanoTime();
        String retryToken = token("order");
        String tenancy = config.defaultTenancyId();

        String first = given().contentType("application/json").header("opc-retry-token", retryToken)
                .body("{\"compartmentId\":\"" + tenancy + "\",\"name\":\"" + name + "\",\"description\":\"d\"}")
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().jsonPath().getString("id");
        String retry = given().contentType("application/json").header("opc-retry-token", retryToken)
                .body("{\"description\":\"d\",\"name\":\"" + name + "\",\"compartmentId\":\"" + tenancy + "\"}")
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().jsonPath().getString("id");

        assertEquals(first, retry);
    }

    @Test
    void aFailedRequestDoesNotUseUpItsToken() {
        String retryToken = token("failed");
        given().contentType("application/json").header("opc-retry-token", retryToken)
                .body(Map.of("compartmentId", config.defaultTenancyId(), "description", "no name"))
            .when().post("/20160918/compartments")
            .then().statusCode(400);

        String name = "retry-after-failure-" + System.nanoTime();
        String created = createCompartment(name, retryToken).jsonPath().getString("id");
        String replayed = createCompartment(name, retryToken).jsonPath().getString("id");

        assertEquals(created, replayed);
        assertEquals(1, compartmentsNamed(name));
    }

    @Test
    void aTokenIsScopedToItsPath() {
        String retryToken = token("paths");
        String suffix = String.valueOf(System.nanoTime());

        createCompartment("retry-path-" + suffix, retryToken);
        given().contentType("application/json").header("opc-retry-token", retryToken)
                .body(Map.of("name", "retry-path-user-" + suffix, "description", "u", "email", "u@example.com"))
            .when().post("/20160918/users")
            .then().statusCode(200).body("name", equalTo("retry-path-user-" + suffix));
    }

    @Test
    void aTokenIsScopedToItsTenancy() {
        String other = "ocid1.tenancy.oc1..retrytokenothertenancy" + System.nanoTime();
        String signedAsOther = "Signature version=\"1\",keyId=\"" + other + "/ocid1.user.oc1..retryuser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\",signature=\"ZmFrZQ==\"";
        String retryToken = token("tenancy");
        String name = "retry-tenancy-" + System.nanoTime();

        String mine = createCompartment(name, retryToken).jsonPath().getString("id");
        String theirs = given().contentType("application/json").header("opc-retry-token", retryToken)
                .header("Authorization", signedAsOther)
                .body(Map.of("compartmentId", other, "name", name, "description", "retry"))
            .when().post("/20160918/compartments")
            .then().statusCode(200).extract().jsonPath().getString("id");

        assertNotEquals(mine, theirs);
    }

    @Test
    void binaryUploadsAreNotReplayed() {
        String bucket = "retry-bucket-" + System.nanoTime();
        given().contentType("application/json")
                .body(Map.of("name", bucket, "compartmentId", TestCompartments.create("retrybucket")))
            .when().post("/n/" + NS + "/b")
            .then().statusCode(200);
        String retryToken = token("put");

        for (String content : List.of("first", "second")) {
            given().contentType("application/octet-stream").header("opc-retry-token", retryToken)
                    .body(content.getBytes())
                .when().put("/n/" + NS + "/b/" + bucket + "/o/object.txt")
                .then().statusCode(200);
        }

        given()
            .when().get("/n/" + NS + "/b/" + bucket + "/o/object.txt")
            .then().statusCode(200).body(equalTo("second"));
    }

    @Test
    void concurrentRetriesCreateOneResource() throws Exception {
        String name = "retry-concurrent-" + System.nanoTime();
        String retryToken = token("concurrent");
        CountDownLatch start = new CountDownLatch(1);

        List<CompletableFuture<String>> calls = List.of(1, 2, 3).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return createCompartment(name, retryToken).jsonPath().getString("id");
                }))
                .toList();
        start.countDown();

        List<String> ids = calls.stream().map(CompletableFuture::join).distinct().toList();
        assertEquals(1, ids.size(), () -> "one compartment for one token, got " + ids);
        assertEquals(1, compartmentsNamed(name));
    }
}
