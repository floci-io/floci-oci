package io.floci.oci.services.functions;

import io.floci.oci.core.common.OciContext;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
class FunctionsDetachedInvokeIntegrationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..itdetachedtenancy";
    private static final String USER = "ocid1.user.oc1..itdetacheduser";
    private static final String PHOENIX_FUNCTIONS = "functions.us-phoenix-1.oci.oraclecloud.com";
    private static final String FUNCTION_ID = "ocid1.fnfunc.oc1.phx.itdetached";
    private static final String AUTH = "Signature version=\"1\",keyId=\"" + TENANCY + "/" + USER + "/aa:bb\","
            + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
            + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";

    @InjectMock
    FunctionsService service;

    @Inject
    OciContext ociContext;

    @Test
    void detachedInvokeRunsAsTheCallerAfterTheResponse() throws Exception {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(Map.of("regionKey", "PHX"))
            .when().post("/20160918/tenancies/" + TENANCY + "/regionSubscriptions")
            .then().statusCode(anyOf(is(200), is(409)));
        CountDownLatch responded = new CountDownLatch(1);
        CompletableFuture<String> seen = new CompletableFuture<>();
        when(service.invoke(eq(FUNCTION_ID), any(), any())).thenAnswer(invocation -> {
            responded.await(10, TimeUnit.SECONDS);
            seen.complete(ociContext.tenancyId() + " " + ociContext.region());
            return new FunctionsService.InvokeResult(new byte[0], "application/json");
        });

        given()
                .header("Host", PHOENIX_FUNCTIONS)
                .header("Authorization", AUTH)
                .header("fn-invoke-type", "detached")
                .body("x".getBytes())
            .when().post("/20181201/functions/" + FUNCTION_ID + "/actions/invoke")
            .then().statusCode(202);
        responded.countDown();

        assertEquals(TENANCY + " us-phoenix-1", seen.get(10, TimeUnit.SECONDS));
    }
}
