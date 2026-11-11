package io.floci.oci.core.retrytoken;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.common.OpcRequestIdFilter;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Honours {@code opc-retry-token}: "A token that uniquely identifies a request so it can be retried in
 * case of a timeout or server error without risk of executing that same action again. Retry tokens
 * expire after 24 hours" (oci-go-sdk, on every operation that declares the header).
 *
 * <p>Applies to every write (POST, PUT, PATCH, DELETE) carrying the header. Real clients send it only
 * on the operations that declare it: oci-go-sdk fills a token on each of them, oci-python-sdk and the
 * CLI whenever retries are on, oci-java-sdk by default. A token is scoped to the caller's tenancy, the
 * region, the method and the path, so it never spans two resources or two actions.
 *
 * <ul>
 *   <li>A completed 2xx request is recorded; a retry with the same token and the same request gets the
 *       recorded status, headers and body again, with a request id of its own.</li>
 *   <li>The same token with a different request (body compared as canonical JSON, so field order does
 *       not matter, plus the query string) is {@code 409 Conflict}. OCI does not state this case for the
 *       emulated services; generativeai and mysql describe it as a 409 conflict.</li>
 *   <li>A request that fails is not recorded, so the token stays usable for the retry that fixes it.</li>
 *   <li>A retry that arrives while the first request is still running waits for it, so concurrent SDK
 *       retries create one resource.</li>
 * </ul>
 *
 * <p>Not emulated: {@code 409 InvalidatedRetryToken}, which real OCI returns when the resource the token
 * created has since been deleted. A replay within 24 hours returns the recorded response.
 *
 * <p>Post-matching, so it runs on the worker thread blocking endpoints use, after the body-free checks
 * (service enabled, home region) and before the body is deserialized; reading the body in a
 * {@code @PreMatching} filter would block the IO thread.
 */
@Provider
@Priority(Priorities.USER + 200)
public class RetryTokenFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(RetryTokenFilter.class);

    static final String RETRY_TOKEN = "opc-retry-token";
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long IN_FLIGHT_WAIT_SECONDS = 30;
    private static final String PENDING = RetryTokenFilter.class.getName() + ".pending";
    private static final Set<String> WRITE_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    /** Per-response headers that a replay must not repeat; the rest are part of the answer. */
    private static final Set<String> NOT_REPLAYED = Set.of(
            OpcRequestIdFilter.OPC_REQUEST_ID, OpcRequestIdFilter.OPC_CLIENT_REQUEST_ID,
            HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT), HttpHeaders.CONTENT_LENGTH.toLowerCase(Locale.ROOT),
            HttpHeaders.DATE.toLowerCase(Locale.ROOT), "access-control-allow-origin",
            "access-control-allow-credentials", "access-control-expose-headers", "vary");
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Requests with a token still being served, by tenancy-qualified key. */
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> IN_FLIGHT = new ConcurrentHashMap<>();

    /** Injected lazily: this filter is instantiated during Quarkus static init. */
    @Inject
    // Qualified: jakarta.ws.rs.ext.Provider is imported for the @Provider annotation.
    jakarta.inject.Provider<RetryTokenStore> store;

    @Inject
    jakarta.inject.Provider<OciContext> ociContext;

    @Inject
    jakarta.inject.Provider<ObjectMapper> objectMapper;

    private record Pending(String key, String inFlightKey, String fingerprint, CompletableFuture<Void> done) {
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String token = ctx.getHeaderString(RETRY_TOKEN);
        if (token == null || token.isBlank() || !WRITE_METHODS.contains(ctx.getMethod()) || !isJson(ctx)) {
            return;
        }
        Optional<byte[]> body = bufferBody(ctx);
        if (body.isEmpty()) {
            return;
        }
        String key = sha256(ociContext.get().region() + "\n" + ctx.getMethod() + "\n"
                + ctx.getUriInfo().getPath() + "\n" + token);
        String inFlightKey = ociContext.get().tenancyId() + "/" + key;
        String fingerprint = fingerprint(body.get(), ctx.getUriInfo().getQueryParameters());

        while (true) {
            Optional<StoredRetryReceipt> receipt = store.get().find(key);
            if (receipt.isPresent()) {
                ctx.abortWith(receipt.get().fingerprint().equals(fingerprint)
                        ? replay(receipt.get())
                        : conflict("The opc-retry-token was already used with a different request."));
                return;
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> running = IN_FLIGHT.putIfAbsent(inFlightKey, mine);
            if (running == null) {
                ctx.setProperty(PENDING, new Pending(key, inFlightKey, fingerprint, mine));
                return;
            }
            if (!awaitCompletion(running)) {
                ctx.abortWith(conflict("A request with this opc-retry-token is still in progress."));
                return;
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!(requestContext.getProperty(PENDING) instanceof Pending pending)) {
            return;
        }
        try {
            int status = responseContext.getStatus();
            if (status >= 200 && status < 300) {
                record(pending, responseContext);
            }
        } finally {
            IN_FLIGHT.remove(pending.inFlightKey(), pending.done());
            pending.done().complete(null);
        }
    }

    private void record(Pending pending, ContainerResponseContext response) {
        Optional<byte[]> body = serialize(response.getEntity());
        if (body.isEmpty()) {
            LOG.debugv("Not recording opc-retry-token response: entity type {0} cannot be replayed",
                    response.getEntity().getClass().getName());
            return;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : response.getStringHeaders().entrySet()) {
            if (!NOT_REPLAYED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                headers.put(header.getKey(), List.copyOf(header.getValue()));
            }
        }
        MediaType type = response.getMediaType();
        store.get().save(pending.key(), pending.fingerprint(), response.getStatus(),
                type == null ? null : type.toString(), headers,
                body.get().length == 0 ? null : Base64.getEncoder().encodeToString(body.get()));
    }

    /**
     * The response body as it went out: entities are often live {@code Stored*} objects a later update
     * mutates, so they are serialized now, with the mapper the REST layer writes them with.
     */
    private Optional<byte[]> serialize(Object entity) {
        if (entity == null) {
            return Optional.of(new byte[0]);
        }
        if (entity instanceof byte[] bytes) {
            return Optional.of(bytes.clone());
        }
        if (entity instanceof String text) {
            return Optional.of(text.getBytes(StandardCharsets.UTF_8));
        }
        if (entity instanceof InputStream || entity instanceof StreamingOutput) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.get().writeValueAsBytes(entity));
        } catch (JsonProcessingException e) {
            LOG.debugv("Not recording opc-retry-token response: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Response replay(StoredRetryReceipt receipt) {
        Response.ResponseBuilder builder = Response.status(receipt.status());
        receipt.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (receipt.body() != null) {
            builder.entity(Base64.getDecoder().decode(receipt.body()));
            if (receipt.contentType() != null) {
                builder.type(receipt.contentType());
            }
        }
        return builder.build();
    }

    private static Response conflict(String message) {
        return Response.status(409)
                .type(MediaType.APPLICATION_JSON)
                .entity(new OciErrorResponse("Conflict", message))
                .build();
    }

    /** Waits for the request already holding the token; false when it is still running after the wait. */
    private static boolean awaitCompletion(CompletableFuture<Void> running) {
        try {
            running.get(IN_FLIGHT_WAIT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            LOG.debugv("opc-retry-token request still running after {0}s", IN_FLIGHT_WAIT_SECONDS);
            return false;
        } catch (ExecutionException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Reads the body and puts it back for the resource method. Empty when it is larger than
     * {@link #MAX_BODY_BYTES}: such a request is served as if it had no token.
     */
    private static Optional<byte[]> bufferBody(ContainerRequestContext ctx) {
        InputStream in = ctx.getEntityStream();
        byte[] read;
        try {
            read = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the request body", e);
        }
        if (read.length > MAX_BODY_BYTES) {
            ctx.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(read), in));
            LOG.debugv("Ignoring opc-retry-token on a body over {0} bytes", MAX_BODY_BYTES);
            return Optional.empty();
        }
        ctx.setEntityStream(new ByteArrayInputStream(read));
        return Optional.of(read);
    }

    /** JSON or no declared type: binary uploads are never buffered. */
    private static boolean isJson(ContainerRequestContext ctx) {
        MediaType type = ctx.getMediaType();
        return type == null || type.getSubtype().equalsIgnoreCase("json") || type.getSubtype().endsWith("+json");
    }

    /** SHA-256 of the body as canonical JSON (keys sorted at every level) and the sorted query string. */
    static String fingerprint(byte[] body, MultivaluedMap<String, String> query) {
        Map<String, List<String>> sortedQuery = new TreeMap<>();
        query.forEach((name, values) -> {
            List<String> copy = new ArrayList<>(values);
            copy.sort(null);
            sortedQuery.put(name, copy);
        });
        return sha256(canonicalBody(body) + "\n" + sortedQuery);
    }

    static String canonicalBody(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        try {
            JsonNode tree = CANONICAL.readTree(body);
            return CANONICAL.writeValueAsString(CANONICAL.treeToValue(tree, Object.class));
        } catch (IOException notJson) {
            // A body that is not JSON is compared byte for byte.
            return Base64.getEncoder().encodeToString(body);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
