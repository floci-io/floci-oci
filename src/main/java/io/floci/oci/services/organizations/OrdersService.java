package io.floci.oci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.organizations.model.StoredOrderActivation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant Manager orders. An activation token is a JWT (RFC 7519) issued by Oracle's order
 * system; floci-oci has no order system, so it accepts any well-formed JWT without checking its
 * signature (as it never checks request signatures) and builds the order from it: the claims
 * {@code orderNumber}, {@code adminEmail}, {@code dataCenterRegion} and {@code iat} are used when
 * present.
 * An order is activated once, into the tenancy that asks.
 */
@ApplicationScoped
public class OrdersService {

    private static final Logger LOG = Logger.getLogger(OrdersService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String PENDING = "PENDING";
    static final String ACTIVATED = "ACTIVATED";

    private final StorageBackend<String, StoredOrderActivation> activations;
    private final WorkRequestService workRequests;
    private final OciContext ociContext;

    @Inject
    public OrdersService(StorageFactory storageFactory, WorkRequestService workRequests, OciContext ociContext) {
        this(storageFactory.createGlobal("organizations", "organizations-order-activations.json",
                new TypeReference<Map<String, StoredOrderActivation>>() {}), workRequests, ociContext);
    }

    OrdersService(StorageBackend<String, StoredOrderActivation> activations, WorkRequestService workRequests,
                  OciContext ociContext) {
        this.activations = activations;
        this.workRequests = workRequests;
        this.ociContext = ociContext;
    }

    /** The order the token names, {@code PENDING} until it is activated. */
    public Map<String, Object> getOrder(String activationToken) {
        JsonNode claims = claims(activationToken);
        String hash = digest(activationToken);
        String state = findActivation(hash).isPresent() ? ACTIVATED : PENDING;
        Instant start = issuedAt(claims);

        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("name", "CLOUDCM");
        sku.put("quantity", 1);
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("spmSubscriptionId", "SPM-" + hash.substring(0, 12).toUpperCase(Locale.ROOT));
        subscription.put("service", "CLOUDCM");
        subscription.put("startDate", start.toString());
        subscription.put("endDate", subscriptionEnd(start).toString());
        subscription.put("skus", List.of(sku));

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("orderNumber", claim(claims, "orderNumber", orderNumber(hash)));
        wire.put("adminEmail", claim(claims, "adminEmail", "admin@example.com"));
        wire.put("orderState", state);
        wire.put("subscriptions", List.of(subscription));
        String region = claim(claims, "dataCenterRegion", null);
        if (region != null) {
            wire.put("dataCenterRegion", region);
        }
        return wire;
    }

    /** Activates the order into the caller's tenancy. Returns the work request id. */
    public synchronized String activateOrder(String activationToken, String compartmentId) {
        JsonNode claims = claims(activationToken);
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        String caller = ociContext.tenancyId();
        if (!compartmentId.equals(caller)) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: tenancy " + compartmentId);
        }
        String hash = digest(activationToken);
        if (findActivation(hash).isPresent()) {
            throw OciException.conflict("The order is already activated");
        }
        String orderNumber = claim(claims, "orderNumber", orderNumber(hash));
        StoredOrderActivation activation = new StoredOrderActivation();
        activation.setTokenHash(hash);
        activation.setOrderNumber(orderNumber);
        activation.setCompartmentId(caller);
        activation.setTimeActivated(Instant.now().toString());
        TenancyAwareStorageBackend.putIn(activations, caller, hash, activation);
        LOG.infov("activateOrder {0} into {1}", orderNumber, caller);
        return workRequests.succeeded(OrganizationsService.WR_SERVICE, "ACTIVATE_ORDER_EXISTING_TENANCY", caller,
                List.of(WorkRequestService.resource("order", "UPDATED", orderNumber, null)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Optional<StoredOrderActivation> findActivation(String hash) {
        return TenancyAwareStorageBackend.scanAll(activations).stream()
                .filter(a -> hash.equals(a.getTokenHash()))
                .findFirst();
    }

    /**
     * The token's claims. A JWT is three dot-separated base64url segments: a JSON object header,
     * a JSON object payload (the claims) and a signature, which is not checked.
     */
    private static JsonNode claims(String activationToken) {
        String[] parts = activationToken == null ? new String[0] : activationToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw notAJwt();
        }
        jsonObject(parts[0]);
        return jsonObject(parts[1]);
    }

    private static JsonNode jsonObject(String segment) {
        try {
            JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
            if (node == null || !node.isObject()) {
                throw notAJwt();
            }
            return node;
        } catch (IllegalArgumentException | IOException e) {
            // Not base64url or not JSON: the client sent something that is not a JWT, which is a
            // 400 for it to fix, not an emulator fault worth logging.
            throw notAJwt();
        }
    }

    private static OciException notAJwt() {
        return OciException.invalidParameter("activationToken must be a JWT (RFC 7519)");
    }

    /** The {@code iat} claim as the start of the subscription, or the epoch when absent or out of range. */
    private static Instant subscriptionEnd(Instant start) {
        return start.plus(365, ChronoUnit.DAYS);
    }

    private static Instant issuedAt(JsonNode claims) {
        JsonNode iat = claims.path("iat");
        if (!iat.canConvertToLong()) {
            return Instant.EPOCH;
        }
        try {
            Instant start = Instant.ofEpochSecond(iat.asLong()).truncatedTo(ChronoUnit.DAYS);
            subscriptionEnd(start);
            return start;
        } catch (DateTimeException e) {
            // iat beyond Instant's range, or its end date would be: treat it as absent rather
            // than fail the read.
            return Instant.EPOCH;
        }
    }

    /** A textual claim, or {@code fallback} when it is absent, blank or not a string. */
    private static String claim(JsonNode claims, String name, String fallback) {
        JsonNode value = claims.path(name);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String orderNumber(String hash) {
        return String.valueOf(Long.parseUnsignedLong(hash.substring(0, 12), 16) % 100_000_000L);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
