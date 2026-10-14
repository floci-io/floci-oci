package io.floci.oci.services.identity;

import io.floci.oci.core.tenancy.TenancyRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM writes are only accepted at the tenancy's home region. The routes are read from
 * {@link IdentityController} itself, so a write added there is checked without touching this test.
 */
@QuarkusTest
class IdentityHomeRegionRestIntegrationTest {

    private static final String TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";
    private static final String PHOENIX_IDENTITY = "identity.us-phoenix-1.oci.oraclecloud.com";
    private static final String ASHBURN_IDENTITY = "identity.us-ashburn-1.oci.oraclecloud.com";

    @Inject
    TenancyRegistry tenancies;

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..ituser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    @Test
    void everyWriteOutsideTheHomeRegionIsNotAllowed() {
        List<Method> writes = writes(false);
        assertTrue(writes.size() >= 16, "expected the IAM writes, found " + writes.size());
        for (Method write : writes) {
            send(write, PHOENIX_IDENTITY)
                .then().statusCode(403)
                    .header("opc-request-id", notNullValue())
                    .body("code", equalTo("NotAllowed"))
                    .body("message", equalTo("This operation must be directed at the home region."));
        }
    }

    @Test
    void writesAtTheHomeRegionReachTheOperation() {
        for (Method write : writes(false)) {
            assertNotEquals(403, send(write, null).statusCode(), write.getName());
        }
    }

    @Test
    void writesFollowTheCallersOwnHomeRegion() {
        String child = tenancies.registerChild(TENANCY, "phoenix-writer", "us-phoenix-1").getId();
        for (Method write : writes(false)) {
            assertNotEquals(403, send(write, PHOENIX_IDENTITY, child).statusCode(), write.getName());
            send(write, ASHBURN_IDENTITY, child)
                .then().statusCode(403)
                    .body("code", equalTo("NotAllowed"));
        }
    }

    @Test
    void aSignedReadRegistersTheTenancy() {
        String tenancy = "ocid1.tenancy.oc1..itfirstseen" + System.nanoTime();
        assertTrue(tenancies.find(tenancy).isEmpty());

        given().header("Authorization", signedAs(tenancy)).queryParam("compartmentId", tenancy)
            .when().get("/20160918/compartments")
            .then().statusCode(200);

        assertEquals("us-ashburn-1", tenancies.find(tenancy).orElseThrow().getHomeRegion());
    }

    @Test
    void regionSubscriptionsAreTheOnlyWriteAcceptedInAnyRegion() {
        List<String> anyRegion = writes(true).stream().map(Method::getName).toList();
        assertEquals(List.of("createRegionSubscription"), anyRegion);
        assertNotEquals(403, send(writes(true).getFirst(), PHOENIX_IDENTITY).statusCode());
    }

    @Test
    void readsWorkOutsideTheHomeRegion() {
        given().header("Host", PHOENIX_IDENTITY).queryParam("compartmentId", TENANCY)
            .when().get("/20160918/compartments")
            .then().statusCode(200);
    }

    private static List<Method> writes(boolean anyRegion) {
        List<Method> writes = new ArrayList<>();
        for (Method method : IdentityController.class.getDeclaredMethods()) {
            boolean write = method.isAnnotationPresent(POST.class) || method.isAnnotationPresent(PUT.class)
                    || method.isAnnotationPresent(DELETE.class);
            if (write && method.isAnnotationPresent(AnyRegionWrite.class) == anyRegion) {
                writes.add(method);
            }
        }
        return writes;
    }

    /** Sends the route with placeholder path values and an empty body: the region check comes first. */
    private static Response send(Method route, String host) {
        return send(route, host, null);
    }

    /** As {@link #send(Method, String)}, signed as {@code tenancyId} when one is given. */
    private static Response send(Method route, String host, String tenancyId) {
        String path = "/20160918" + route.getAnnotation(Path.class).value()
                .replace("{tenancyId}", tenancyId == null ? TENANCY : tenancyId)
                .replaceAll("\\{[^}]+}", "ocid1.placeholder.oc1..none");
        String verb = route.isAnnotationPresent(POST.class) ? "POST"
                : route.isAnnotationPresent(PUT.class) ? "PUT" : "DELETE";
        RequestSpecification request = host == null ? given() : given().header("Host", host);
        if (tenancyId != null) {
            request = request.header("Authorization", signedAs(tenancyId));
        }
        return request
                .contentType("application/json")
                .body("{}")
            .when().request(verb, path);
    }
}
