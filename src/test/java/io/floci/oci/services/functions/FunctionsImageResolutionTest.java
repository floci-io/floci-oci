package io.floci.oci.services.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What fnserver is told to run, against a stand-in fnserver that records each request. Like fnserver it
 * answers 409 to a second fn with the same name and 404 to an update of a fn it does not have
 * ({@code fn/api/models/fn.go}: ErrFnsExists, ErrFnsNotFound).
 */
class FunctionsImageResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPARTMENT = "ocid1.compartment.oc1..fnimagetest";
    private static final String OCIR_IMAGE = "iad.ocir.localhost.floci.io/testns/fn-hello:1";

    private record Call(String method, String path, JsonNode body) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> registryPort = new AtomicReference<>("5201");
    private final Map<String, String> fnIdsByName = new ConcurrentHashMap<>();
    private final AtomicInteger nextFnId = new AtomicInteger(1);
    /** Runs while fnserver holds an update request open, before it answers. */
    private final AtomicReference<Runnable> duringUpdate = new AtomicReference<>(() -> { });
    /** Runs while an image in the local registry is resolved, as a registry that is still starting would. */
    private final AtomicReference<Runnable> duringRegistryResolve = new AtomicReference<>(() -> { });
    private HttpServer fnserver;
    private FunctionsService service;

    @BeforeEach
    void setUp() throws IOException {
        fnserver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fnserver.createContext("/", this::handle);
        fnserver.start();

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRealm()).thenReturn("oc1");
        when(config.defaultRegion()).thenReturn("us-ashburn-1");
        when(config.services().functions().mock()).thenReturn(false);
        when(config.services().functions().startupTimeoutSeconds()).thenReturn(5);
        FnServerManager fnServer = mock(FnServerManager.class);
        when(fnServer.isReady()).thenReturn(true);
        when(fnServer.baseUrl()).thenReturn("http://127.0.0.1:" + fnserver.getAddress().getPort());
        when(fnServer.httpClient()).thenReturn(HttpClient.newHttpClient());
        service = new FunctionsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, fnServer,
                new WorkRequestService(new InMemoryStorage<>(), config),
                image -> {
                    if (!image.startsWith("iad.ocir.localhost.floci.io/")) {
                        return image;
                    }
                    duringRegistryResolve.get().run();
                    return "localhost:" + registryPort.get() + "/iad/"
                            + image.substring("iad.ocir.localhost.floci.io/".length());
                });
    }

    @AfterEach
    void tearDown() {
        fnserver.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        JsonNode body = request.length == 0 || path.startsWith("/invoke/") ? null : MAPPER.readTree(request);
        calls.add(new Call(method, path, body));
        if (method.equals("POST") && path.equals("/v2/fns")) {
            String name = body.get("name").asText();
            if (fnIdsByName.containsKey(name)) {
                respond(exchange, 409, "{\"message\":\"Fn with specified name already exists\"}");
                return;
            }
            String id = "fn-" + nextFnId.getAndIncrement();
            fnIdsByName.put(name, id);
            respond(exchange, 200, "{\"id\":\"" + id + "\"}");
        } else if (method.equals("PUT") && path.startsWith("/v2/fns/")) {
            duringUpdate.get().run();
            String id = path.substring("/v2/fns/".length());
            boolean known = fnIdsByName.containsValue(id);
            respond(exchange, known ? 200 : 404,
                    known ? "{\"id\":\"" + id + "\"}" : "{\"message\":\"Fn not found\"}");
        } else {
            respond(exchange, 200, path.startsWith("/invoke/") ? "hello" : "{\"id\":\"app-1\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] response = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void update(StoredFunction fn, String image, String digest) {
        service.updateFunction(fn.getId(), image, digest, null, null, null, null, null, null, null);
    }

    private byte[] invoke(StoredFunction fn) {
        return service.invoke(fn.getId(), new byte[0], null).body();
    }

    private StoredFunction function(String image) {
        StoredApplication app = service.createApplication(COMPARTMENT, "images", List.of("ocid1.subnet.oc1.iad.s"),
                null, null, null, null);
        return service.createFunction(app.getId(), "hello", image, 128L, 30, Map.of(), null, null);
    }

    private List<Call> callsTo(String method, String pathPrefix) {
        return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
    }

    @Test
    void fnserverRunsTheResolvedImageWhileTheApiKeepsTheStoredOne() {
        StoredFunction fn = function(OCIR_IMAGE);

        service.invoke(fn.getId(), new byte[0], null);

        assertEquals("localhost:5201/iad/testns/fn-hello:1",
                callsTo("POST", "/v2/fns").get(0).body().get("image").asText());
        assertEquals(OCIR_IMAGE, service.getFunction(fn.getId()).getImage());
    }

    @Test
    void aMovedRegistryPortRepointsTheMirroredFunction() {
        StoredFunction fn = function(OCIR_IMAGE);
        service.invoke(fn.getId(), new byte[0], null);

        service.invoke(fn.getId(), new byte[0], null);
        assertEquals(List.of(), callsTo("PUT", "/v2/fns/"));

        registryPort.set("5202");
        service.invoke(fn.getId(), new byte[0], null);

        List<Call> updates = callsTo("PUT", "/v2/fns/fn-1");
        assertEquals(1, updates.size());
        assertEquals("localhost:5202/iad/testns/fn-hello:1", updates.get(0).body().get("image").asText());
        assertEquals(1, callsTo("POST", "/v2/fns").size());
    }

    @Test
    void otherImagesReachFnserverAsStored() {
        StoredFunction fn = function("docker.io/fnproject/hello:latest");

        service.invoke(fn.getId(), new byte[0], null);

        assertEquals("docker.io/fnproject/hello:latest",
                callsTo("POST", "/v2/fns").get(0).body().get("image").asText());
    }

    @Test
    void anUpdatedImageUpdatesTheMirroredFnInPlace() {
        StoredFunction fn = function("docker.io/fnproject/hello:1");
        invoke(fn);

        update(fn, "docker.io/fnproject/hello:2", null);

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), invoke(fn));
        List<Call> updates = callsTo("PUT", "/v2/fns/fn-1");
        assertEquals(1, updates.size());
        assertEquals("docker.io/fnproject/hello:2", updates.get(0).body().get("image").asText());
        assertEquals(1, callsTo("POST", "/v2/fns").size());
        assertEquals("docker.io/fnproject/hello:2", service.getFunction(fn.getId()).getImage());
    }

    @Test
    void aNewDigestForTheSameTagUpdatesTheMirroredFn() {
        String digest = "sha256:" + "a".repeat(64);
        StoredFunction fn = function("docker.io/fnproject/hello:latest");
        invoke(fn);

        update(fn, "docker.io/fnproject/hello:latest", digest);
        invoke(fn);

        assertEquals("docker.io/fnproject/hello:latest@" + digest,
                callsTo("PUT", "/v2/fns/fn-1").get(0).body().get("image").asText());
        assertEquals(1, callsTo("POST", "/v2/fns").size());
    }

    @Test
    void aFnThatFnserverNoLongerHasIsCreatedAgain() {
        StoredFunction fn = function("docker.io/fnproject/hello:1");
        invoke(fn);
        fnIdsByName.clear();

        update(fn, "docker.io/fnproject/hello:2", null);

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), invoke(fn));
        assertEquals(1, callsTo("PUT", "/v2/fns/fn-1").size());
        List<Call> creates = callsTo("POST", "/v2/fns");
        assertEquals(2, creates.size());
        assertEquals("docker.io/fnproject/hello:2", creates.get(1).body().get("image").asText());
        assertEquals("fn-2", service.getFunction(fn.getId()).getFnFnId());
    }

    @Test
    void updatedSettingsReachTheMirroredFnWithRemovedConfigKeysCleared() {
        StoredApplication app = service.createApplication(COMPARTMENT, "settings", List.of("ocid1.subnet.oc1.iad.s"),
                null, null, null, null);
        StoredFunction fn = service.createFunction(app.getId(), "hello", "docker.io/fnproject/hello:1", 128L, 30,
                Map.of("KEPT", "1", "CHANGED", "old", "REMOVED", "x"), null, null);
        invoke(fn);
        invoke(fn);
        assertEquals(List.of(), callsTo("PUT", "/v2/fns/"));

        service.updateFunction(fn.getId(), null, null, 256L, 60, Map.of("KEPT", "1", "CHANGED", "new", "ADDED", "y"),
                null, null, null, null);
        invoke(fn);

        List<Call> updates = callsTo("PUT", "/v2/fns/fn-1");
        assertEquals(1, updates.size());
        JsonNode body = updates.get(0).body();
        assertEquals("docker.io/fnproject/hello:1", body.get("image").asText());
        assertEquals(256, body.get("memory").asInt());
        assertEquals(60, body.get("timeout").asInt());
        assertEquals(Map.of("KEPT", "1", "CHANGED", "new", "ADDED", "y", "REMOVED", ""),
                MAPPER.convertValue(body.get("config"), Map.class));
        assertEquals(1, callsTo("POST", "/v2/fns").size());

        invoke(fn);
        assertEquals(1, callsTo("PUT", "/v2/fns/fn-1").size());
    }

    @Test
    void anUpdateWhileFnserverIsBeingUpdatedIsSentOnTheNextInvoke() throws Exception {
        StoredFunction fn = function("docker.io/fnproject/hello:1");
        invoke(fn);
        update(fn, "docker.io/fnproject/hello:2", null);
        Thread racing = new Thread(() -> service.updateFunction(fn.getId(), null, null, null, null,
                Map.of("LATE", "yes"), null, null, null, null));
        duringUpdate.set(() -> {
            racing.start();
            try {
                // Long enough for an unsynchronised update to land before fnserver answers.
                racing.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        invoke(fn);
        duringUpdate.set(() -> { });
        racing.join();
        invoke(fn);

        List<Call> updates = callsTo("PUT", "/v2/fns/fn-1");
        assertEquals(2, updates.size());
        assertEquals(Map.of(), MAPPER.convertValue(updates.get(0).body().get("config"), Map.class));
        assertEquals(Map.of("LATE", "yes"), MAPPER.convertValue(updates.get(1).body().get("config"), Map.class));
    }

    @Test
    void aFunctionWaitingForTheRegistryDoesNotHoldUpOtherFunctions() throws Exception {
        StoredFunction waiting = function(OCIR_IMAGE);
        StoredFunction other = service.createFunction(waiting.getApplicationId(), "other", "docker.io/fnproject/hello:1",
                128L, 30, Map.of(), null, null);
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        AtomicBoolean registryWaitedOut = new AtomicBoolean();
        duringRegistryResolve.set(() -> {
            resolving.countDown();
            try {
                registryWaitedOut.set(!otherDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread slow = new Thread(() -> invoke(waiting));
        slow.start();
        assertTrue(resolving.await(5, TimeUnit.SECONDS));

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), invoke(other));
        update(other, "docker.io/fnproject/hello:2", null);
        otherDone.countDown();
        slow.join();

        assertFalse(registryWaitedOut.get());
        assertEquals("localhost:5201/iad/testns/fn-hello:1",
                callsTo("POST", "/v2/fns").getLast().body().get("image").asText());
    }
}
