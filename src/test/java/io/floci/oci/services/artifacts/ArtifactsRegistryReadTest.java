package io.floci.oci.services.artifacts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import io.floci.oci.services.objectstorage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Image reads and deletes against a small in-process stand-in for the {@code registry:2} sidecar. */
class ArtifactsRegistryReadTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..artifactsread";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..artifactsread";
    private static final String BACKEND = "/v2/iad/testns/app";
    private static final String IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    private static final String IMAGE_INDEX = "application/vnd.oci.image.index.v1+json";

    private record Stored(String mediaType, String body) {
    }

    private final Map<String, String> tags = new LinkedHashMap<>();
    private final Map<String, Stored> manifests = new LinkedHashMap<>();
    private volatile int deleteStatus = 202;

    private HttpServer server;
    private ArtifactsService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::handle);
        server.start();

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.services().artifacts().mock()).thenReturn(false);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        lenient().when(objectStorage.namespaceOf(anyString())).thenReturn("testns");
        ArtifactsRegistryManager registry = mock(ArtifactsRegistryManager.class);
        when(registry.isStarted()).thenReturn(true);
        when(registry.endpoint()).thenReturn("127.0.0.1:" + server.getAddress().getPort());
        when(registry.httpClient()).thenReturn(HttpClient.newHttpClient());
        service = new ArtifactsService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, objectStorage,
                registry);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals(BACKEND + "/tags/list")) {
            String list = String.join(",", tags.keySet().stream().map(t -> "\"" + t + "\"").toList());
            reply(exchange, 200, null, null, "{\"name\":\"iad/testns/app\",\"tags\":[" + list + "]}");
            return;
        }
        String prefix = BACKEND + "/manifests/";
        if (!path.startsWith(prefix)) {
            reply(exchange, 404, null, null, "{}");
            return;
        }
        String reference = path.substring(prefix.length());
        String digest = tags.getOrDefault(reference, reference);
        if ("DELETE".equals(method)) {
            if (deleteStatus / 100 == 2) {
                manifests.remove(digest);
                tags.values().removeIf(digest::equals);
            }
            reply(exchange, deleteStatus, null, null, "");
            return;
        }
        Stored stored = manifests.get(digest);
        if (stored == null) {
            reply(exchange, 404, null, null, "{}");
            return;
        }
        reply(exchange, 200, stored.mediaType(), digest, stored.body());
    }

    private static void reply(HttpExchange exchange, int status, String type, String digest, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (type != null) {
            exchange.getResponseHeaders().add("Content-Type", type);
        }
        if (digest != null) {
            exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String imageManifest(long... layerSizes) {
        List<String> layers = new ArrayList<>();
        for (int i = 0; i < layerSizes.length; i++) {
            layers.add("{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":" + layerSizes[i]
                    + ",\"digest\":\"sha256:layer" + layerSizes[i] + "x" + i + "\"}");
        }
        return "{\"schemaVersion\":2,\"mediaType\":\"" + IMAGE_MANIFEST + "\",\"layers\":["
                + String.join(",", layers) + "]}";
    }

    private StoredContainerRepository repository() {
        return service.createRepository(Map.of("compartmentId", COMPARTMENT, "displayName", "app"));
    }

    private void push(StoredContainerRepository r, String tag, String digest, Stored manifest) {
        manifests.put(digest, manifest);
        if (tag != null) {
            tags.put(tag, digest);
        }
        service.recordPush(r.getId(), digest);
    }

    @Test
    void retaggedImageStaysListedUntagged() {
        StoredContainerRepository r = repository();
        push(r, "latest", "sha256:old", new Stored(IMAGE_MANIFEST, imageManifest(10)));
        push(r, "latest", "sha256:new", new Stored(IMAGE_MANIFEST, imageManifest(20)));

        List<Map<String, Object>> images = service.images(service.getRepository(r.getId()));
        assertEquals(List.of("sha256:new", "sha256:old"), images.stream().map(i -> i.get("digest")).toList());
        assertEquals("app:latest", images.get(0).get("displayName"));
        assertEquals("app:unknown@sha256:old", images.get(1).get("displayName"));
        assertEquals("sha256:old", service.getImage((String) images.get(1).get("id")).get("digest"));
    }

    @Test
    void imageIndexCountsItsPlatformLayersAndHidesItsPlatformManifests() {
        StoredContainerRepository r = repository();
        push(r, null, "sha256:amd64", new Stored(IMAGE_MANIFEST, imageManifest(100, 5)));
        push(r, null, "sha256:arm64", new Stored(IMAGE_MANIFEST, imageManifest(90)));
        String index = "{\"schemaVersion\":2,\"mediaType\":\"" + IMAGE_INDEX + "\",\"manifests\":["
                + "{\"mediaType\":\"" + IMAGE_MANIFEST + "\",\"digest\":\"sha256:amd64\",\"size\":1},"
                + "{\"mediaType\":\"" + IMAGE_MANIFEST + "\",\"digest\":\"sha256:arm64\",\"size\":1}]}";
        push(r, "v1", "sha256:index", new Stored(IMAGE_INDEX, index));

        List<Map<String, Object>> images = service.images(service.getRepository(r.getId()));
        assertEquals(1, images.size());
        assertEquals("sha256:index", images.get(0).get("digest"));
        assertEquals(3, ((List<?>) images.get(0).get("layers")).size());
        assertEquals(195L, ((Number) images.get(0).get("layersSizeInBytes")).longValue());
        assertEquals(195L, service.stats(service.getRepository(r.getId())).layersSizeInBytes());
        assertFalse(images.get(0).containsKey("childDigests"));

        service.deleteImage((String) images.get(0).get("id"));
        assertTrue(manifests.isEmpty());
        assertTrue(service.images(service.getRepository(r.getId())).isEmpty());
    }

    @Test
    void failedRegistryDeleteIsAnErrorAndKeepsTheRepository() {
        StoredContainerRepository r = repository();
        push(r, "v1", "sha256:one", new Stored(IMAGE_MANIFEST, imageManifest(1)));
        deleteStatus = 500;

        OciException e = assertThrows(OciException.class, () -> service.deleteRepository(r.getId(), null));
        assertEquals("ExternalServerInvalidResponse", e.getCode());
        assertEquals(503, e.getHttpStatus());
        assertEquals("app", service.getRepository(r.getId()).getDisplayName());

        deleteStatus = 202;
        service.deleteRepository(r.getId(), null);
        assertTrue(service.findByName("app").isEmpty());
    }

    @Test
    void unreachableRegistryIsAnError() {
        StoredContainerRepository r = repository();
        push(r, "v1", "sha256:one", new Stored(IMAGE_MANIFEST, imageManifest(1)));
        server.stop(0);

        OciException e = assertThrows(OciException.class, () -> service.images(service.getRepository(r.getId())));
        assertEquals("ExternalServerUnreachable", e.getCode());
    }

    @Test
    void pushIntoARepositoryDeletedMeanwhileDoesNotBringItBack() {
        StoredContainerRepository r = repository();
        service.deleteRepository(r.getId(), null);

        service.recordPush(r.getId(), "sha256:late");
        assertTrue(service.findByName("app").isEmpty());
    }
}
