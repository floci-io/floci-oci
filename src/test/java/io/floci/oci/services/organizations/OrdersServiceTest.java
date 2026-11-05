package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class OrdersServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..orders";

    private final AtomicReference<String> caller = new AtomicReference<>(TENANCY);
    private WorkRequestService workRequests;
    private OrdersService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new OrdersService(new TenancyAwareStorageBackend<>(new InMemoryStorage<>(), caller::get),
                workRequests, context);
    }

    static String jwt(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void getOrderUsesTheTokenClaims() {
        Map<String, Object> order = service.getOrder(jwt(
                "{\"orderNumber\":\"12345\",\"adminEmail\":\"buyer@example.com\",\"dataCenterRegion\":\"us-phoenix-1\","
                        + "\"iat\":1767225600}"));

        assertEquals("12345", order.get("orderNumber"));
        assertEquals("buyer@example.com", order.get("adminEmail"));
        assertEquals("us-phoenix-1", order.get("dataCenterRegion"));
        assertEquals("PENDING", order.get("orderState"));
        Map<?, ?> subscription = (Map<?, ?>) ((List<?>) order.get("subscriptions")).getFirst();
        assertEquals("2026-01-01T00:00:00Z", subscription.get("startDate"));
        assertNotNull(subscription.get("endDate"));
        assertFalse(((List<?>) subscription.get("skus")).isEmpty());
    }

    @Test
    void missingClaimsAreDerivedFromTheToken() {
        String token = jwt("{}");

        Map<String, Object> order = service.getOrder(token);

        assertNotNull(order.get("orderNumber"));
        assertEquals(order.get("orderNumber"), service.getOrder(token).get("orderNumber"), "stable for a token");
        assertFalse(order.containsKey("dataCenterRegion"));
    }

    @Test
    void anOrderActivatesOnce() {
        String token = jwt("{\"orderNumber\":\"777\"}");

        String workRequestId = service.activateOrder(token, TENANCY);

        assertEquals("ACTIVATE_ORDER_EXISTING_TENANCY", workRequests.get(workRequestId).getOperationType());
        assertEquals("777", workRequests.get(workRequestId).getResources().getFirst().getIdentifier());
        assertEquals("ACTIVATED", service.getOrder(token).get("orderState"));
        caller.set("ocid1.tenancy.oc1..another");
        assertEquals("ACTIVATED", service.getOrder(token).get("orderState"), "visible to whoever holds the token");
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.activateOrder(token, "ocid1.tenancy.oc1..another")).getCode());
    }

    @Test
    void tokensAndCompartmentsAreValidated() {
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.getOrder("not-a-jwt")).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.getOrder("a.%%%.c")).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.activateOrder(jwt("{}"), null)).getCode());
        assertEquals(404, assertThrows(OciException.class,
                () -> service.activateOrder(jwt("{}"), "ocid1.tenancy.oc1..someoneelse")).getHttpStatus());
    }

    @Test
    void malformedClaimsFallBackInsteadOfFailing() {
        Map<String, Object> order = service.getOrder(jwt("{\"iat\":9e18,\"dataCenterRegion\":{\"name\":\"x\"},"
                + "\"orderNumber\":42}"));

        Map<?, ?> subscription = (Map<?, ?>) ((List<?>) order.get("subscriptions")).getFirst();
        assertEquals("1970-01-01T00:00:00Z", subscription.get("startDate"));
        assertFalse(order.containsKey("dataCenterRegion"), "a non-text claim is ignored");
        assertNotNull(order.get("orderNumber"));
    }

    @Test
    void anIatWhoseEndDateWouldNotFitFallsBackToTheEpoch() {
        long lastInstantSecond = 31556889864403199L;
        Map<String, Object> order = service.getOrder(jwt("{\"iat\":" + lastInstantSecond + "}"));

        Map<?, ?> subscription = (Map<?, ?>) ((List<?>) order.get("subscriptions")).getFirst();
        assertEquals("1970-01-01T00:00:00Z", subscription.get("startDate"));
        assertEquals("1971-01-01T00:00:00Z", subscription.get("endDate"));
    }

    @Test
    void theHeaderMustBeAJsonObjectToo() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = encoder.encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.getOrder("x." + payload + ".sig")).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.getOrder("." + payload + ".sig")).getCode());
    }
}
