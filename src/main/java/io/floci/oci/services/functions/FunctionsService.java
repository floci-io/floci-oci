package io.floci.oci.services.functions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.*;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * OCI Functions management plane + invocation. The community reference for a
 * Docker-sidecar service: state and the OCI wire contract live here; every container
 * interaction is gated on {@code !mock()} and delegated to the flag-free
 * {@link FnServerManager}. In mock mode the management plane is fully usable and
 * invocations return a synthetic body.
 */
@ApplicationScoped
public class FunctionsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(FunctionsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, StoredApplication> applications;
    private final StorageBackend<String, StoredFunction> functions;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final FnServerManager fnServer;

    @Inject
    public FunctionsService(StorageFactory storageFactory, EmulatorConfig config,
                            ServiceRegistry serviceRegistry, FnServerManager fnServer) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.fnServer = fnServer;
        this.applications = storageFactory.create("functions", "functions-applications.json",
                new TypeReference<Map<String, StoredApplication>>() {});
        this.functions = storageFactory.create("functions", "functions-functions.json",
                new TypeReference<Map<String, StoredFunction>>() {});
    }

    FunctionsService(StorageBackend<String, StoredApplication> applications,
                     StorageBackend<String, StoredFunction> functions,
                     EmulatorConfig config, FnServerManager fnServer) {
        this.applications = applications;
        this.functions = functions;
        this.config = config;
        this.serviceRegistry = null;
        this.fnServer = fnServer;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("functions")
                .enabled(config.services().functions().enabled())
                .storageKey("functions")
                .resourceClasses(FunctionsManagementController.class,
                        FunctionsInvokeController.class)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (!mock()) {
            fnServer.stop();
        }
    }

    /** {@code POST /_floci-oci/state/reset} must also tear the sidecar down. */
    @Override
    public void clear() {
        applications.clear();
        functions.clear();
        if (!mock()) {
            fnServer.stop();
        }
    }

    boolean mock() {
        return config.services().functions().mock();
    }

    // ── Applications ───────────────────────────────────────────────────────────

    public StoredApplication createApplication(String compartmentId, String displayName,
                                               List<String> subnetIds, Map<String, String> appConfig,
                                               String shape,
                                               Map<String, String> freeformTags,
                                               Map<String, Map<String, Object>> definedTags) {
        require(compartmentId, "compartmentId");
        require(displayName, "displayName");
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw OciException.missingParameter("Missing required parameter: subnetIds");
        }
        boolean duplicate = applications.scan(k -> true).stream()
                .anyMatch(a -> displayName.equals(a.getDisplayName())
                        && compartmentId.equals(a.getCompartmentId())
                        && "ACTIVE".equals(a.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("Application " + displayName + " already exists.");
        }
        StoredApplication app = new StoredApplication();
        app.setId(Ocids.generate("fnapp", config.defaultRealm(),
                Ocids.regionShort(config.defaultRegion())));
        app.setCompartmentId(compartmentId);
        app.setDisplayName(displayName);
        app.setLifecycleState("ACTIVE");
        app.setShape(shape != null ? shape : "GENERIC_X86");
        app.setSubnetIds(List.copyOf(subnetIds));
        app.setConfig(appConfig != null ? appConfig : Map.of());
        String now = Instant.now().toString();
        app.setTimeCreated(now);
        app.setTimeUpdated(now);
        app.setFreeformTags(freeformTags);
        app.setDefinedTags(definedTags);
        app.setEtag(Etags.newEtag());
        applications.put(app.getId(), app);
        LOG.infof("createApplication %s (%s)", displayName, app.getId());
        return app;
    }

    public StoredApplication getApplication(String applicationId) {
        return applications.get(applicationId)
                .orElseThrow(() -> notFound("application", applicationId));
    }

    public List<StoredApplication> listApplications(String compartmentId, String displayName,
                                                    String id) {
        require(compartmentId, "compartmentId");
        return applications.scan(k -> true).stream()
                .filter(a -> compartmentId.equals(a.getCompartmentId()))
                .filter(a -> displayName == null || displayName.equals(a.getDisplayName()))
                .filter(a -> id == null || id.equals(a.getId()))
                .sorted(Comparator.comparing(StoredApplication::getTimeCreated))
                .toList();
    }

    public StoredApplication updateApplication(String applicationId, Map<String, String> appConfig,
                                               Map<String, String> freeformTags,
                                               Map<String, Map<String, Object>> definedTags,
                                               String ifMatch) {
        StoredApplication app = getApplication(applicationId);
        Etags.checkIfMatch(ifMatch, app.getEtag());
        if (appConfig != null) {
            app.setConfig(appConfig);
        }
        if (freeformTags != null) {
            app.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            app.setDefinedTags(definedTags);
        }
        app.setTimeUpdated(Instant.now().toString());
        app.setEtag(Etags.newEtag());
        applications.put(applicationId, app);
        return app;
    }

    public void deleteApplication(String applicationId, String ifMatch) {
        StoredApplication app = getApplication(applicationId);
        Etags.checkIfMatch(ifMatch, app.getEtag());
        boolean hasFunctions = functions.scan(k -> true).stream()
                .anyMatch(f -> applicationId.equals(f.getApplicationId()));
        if (hasFunctions) {
            throw OciException.conflict(
                    "Application " + applicationId + " still contains functions.");
        }
        applications.delete(applicationId);
    }

    public void changeApplicationCompartment(String applicationId, String compartmentId,
                                             String ifMatch) {
        StoredApplication app = getApplication(applicationId);
        Etags.checkIfMatch(ifMatch, app.getEtag());
        require(compartmentId, "compartmentId");
        app.setCompartmentId(compartmentId);
        app.setTimeUpdated(Instant.now().toString());
        app.setEtag(Etags.newEtag());
        applications.put(applicationId, app);
        // Functions move implicitly with the application.
        functions.scan(k -> true).stream()
                .filter(f -> applicationId.equals(f.getApplicationId()))
                .forEach(f -> {
                    f.setCompartmentId(compartmentId);
                    functions.put(f.getId(), f);
                });
    }

    // ── Functions ──────────────────────────────────────────────────────────────

    public StoredFunction createFunction(String applicationId, String displayName, String image,
                                         Long memoryInMBs, Integer timeoutInSeconds,
                                         Map<String, String> fnConfig,
                                         Map<String, String> freeformTags,
                                         Map<String, Map<String, Object>> definedTags) {
        StoredApplication app = getApplication(applicationId);
        require(displayName, "displayName");
        if (memoryInMBs == null) {
            throw OciException.missingParameter("Missing required parameter: memoryInMBs");
        }
        boolean duplicate = functions.scan(k -> true).stream()
                .anyMatch(f -> applicationId.equals(f.getApplicationId())
                        && displayName.equals(f.getDisplayName()));
        if (duplicate) {
            throw OciException.conflict("Function " + displayName + " already exists.");
        }
        StoredFunction fn = new StoredFunction();
        fn.setId(Ocids.generate("fnfunc", config.defaultRealm(),
                Ocids.regionShort(config.defaultRegion())));
        fn.setApplicationId(applicationId);
        fn.setCompartmentId(app.getCompartmentId());
        fn.setDisplayName(displayName);
        fn.setLifecycleState("ACTIVE");
        fn.setImage(image);
        fn.setImageDigest(digestFor(image));
        fn.setShape(app.getShape());
        fn.setMemoryInMBs(memoryInMBs);
        fn.setTimeoutInSeconds(timeoutInSeconds != null ? timeoutInSeconds : 30);
        fn.setConfig(fnConfig != null ? fnConfig : Map.of());
        String now = Instant.now().toString();
        fn.setTimeCreated(now);
        fn.setTimeUpdated(now);
        fn.setFreeformTags(freeformTags);
        fn.setDefinedTags(definedTags);
        fn.setEtag(Etags.newEtag());
        functions.put(fn.getId(), fn);
        LOG.infof("createFunction %s (%s, image=%s)", displayName, fn.getId(), image);
        return fn;
    }

    public StoredFunction getFunction(String functionId) {
        return functions.get(functionId).orElseThrow(() -> notFound("function", functionId));
    }

    public List<StoredFunction> listFunctions(String applicationId, String displayName, String id) {
        require(applicationId, "applicationId");
        return functions.scan(k -> true).stream()
                .filter(f -> applicationId.equals(f.getApplicationId()))
                .filter(f -> displayName == null || displayName.equals(f.getDisplayName()))
                .filter(f -> id == null || id.equals(f.getId()))
                .sorted(Comparator.comparing(StoredFunction::getTimeCreated))
                .toList();
    }

    public StoredFunction updateFunction(String functionId, String image, Long memoryInMBs,
                                         Integer timeoutInSeconds, Map<String, String> fnConfig,
                                         Map<String, String> freeformTags,
                                         Map<String, Map<String, Object>> definedTags,
                                         String ifMatch) {
        StoredFunction fn = getFunction(functionId);
        Etags.checkIfMatch(ifMatch, fn.getEtag());
        if (image != null && !image.equals(fn.getImage())) {
            fn.setImage(image);
            // Terraform's CustomizeDiff demands the digest change with the image.
            fn.setImageDigest(digestFor(image));
            fn.setFnFnId(null); // re-mirror on next invoke
        }
        if (memoryInMBs != null) {
            fn.setMemoryInMBs(memoryInMBs);
        }
        if (timeoutInSeconds != null) {
            fn.setTimeoutInSeconds(timeoutInSeconds);
        }
        if (fnConfig != null) {
            fn.setConfig(fnConfig);
        }
        if (freeformTags != null) {
            fn.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            fn.setDefinedTags(definedTags);
        }
        fn.setTimeUpdated(Instant.now().toString());
        fn.setEtag(Etags.newEtag());
        functions.put(functionId, fn);
        return fn;
    }

    public void deleteFunction(String functionId, String ifMatch) {
        StoredFunction fn = getFunction(functionId);
        Etags.checkIfMatch(ifMatch, fn.getEtag());
        functions.delete(functionId);
    }

    public String invokeEndpoint() {
        return config.effectiveBaseUrl();
    }

    // ── Invocation ─────────────────────────────────────────────────────────────

    public record InvokeResult(byte[] body, String contentType) {
    }

    public InvokeResult invoke(String functionId, byte[] payload, String contentType) {
        StoredFunction fn = getFunction(functionId);
        if (mock()) {
            String body = "{\"message\":\"mock invocation of " + fn.getDisplayName() + "\"}";
            return new InvokeResult(body.getBytes(StandardCharsets.UTF_8), "application/json");
        }
        fnServer.ensureStarted();
        waitForFnServerReady();
        try {
            mirrorIntoFnServer(fn);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(fnServer.baseUrl() + "/invoke/" + fn.getFnFnId()))
                    .timeout(Duration.ofSeconds(fn.getTimeoutInSeconds() + 60L))
                    .header("Content-Type", contentType != null ? contentType : "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload != null ? payload : new byte[0]))
                    .build();
            HttpResponse<byte[]> response = fnServer.httpClient()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw OciException.internalServerError("Function invocation failed ("
                        + response.statusCode() + "): "
                        + new String(response.body(), StandardCharsets.UTF_8));
            }
            String responseType = response.headers().firstValue("Content-Type")
                    .orElse("application/octet-stream");
            return new InvokeResult(response.body(), responseType);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw OciException.internalServerError("Function invocation failed: " + e.getMessage());
        }
    }

    /** Bounded wait for the sidecar's first boot; only the invoke path pays it. */
    private void waitForFnServerReady() {
        long deadline = System.currentTimeMillis()
                + config.services().functions().startupTimeoutSeconds() * 1000L;
        while (!fnServer.isReady()) {
            if (System.currentTimeMillis() > deadline) {
                throw OciException.serviceUnavailable("fnserver sidecar did not become ready");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw OciException.serviceUnavailable("Interrupted waiting for fnserver");
            }
        }
    }

    /** Lazily creates the Fn app + fn mirroring this function's image/memory/timeout. */
    private void mirrorIntoFnServer(StoredFunction fn) throws Exception {
        StoredApplication app = getApplication(fn.getApplicationId());
        if (app.getFnAppId() == null) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("name", sanitize(app.getDisplayName()) + "-" + shortId(app.getId()));
            JsonNode created = fnPost("/v2/apps", body);
            app.setFnAppId(created.get("id").asText());
            applications.put(app.getId(), app);
        }
        if (fn.getFnFnId() == null) {
            if (fn.getImage() == null || fn.getImage().isBlank()) {
                throw OciException.invalidParameter("Function has no image to invoke.");
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("name", sanitize(fn.getDisplayName()) + "-" + shortId(fn.getId()));
            body.put("app_id", app.getFnAppId());
            body.put("image", fn.getImage());
            body.put("memory", fn.getMemoryInMBs());
            body.put("timeout", fn.getTimeoutInSeconds());
            ObjectNode fnConfig = body.putObject("config");
            fn.getConfig().forEach(fnConfig::put);
            JsonNode created = fnPost("/v2/fns", body);
            fn.setFnFnId(created.get("id").asText());
            functions.put(fn.getId(), fn);
        }
    }

    private JsonNode fnPost(String path, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(fnServer.baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = fnServer.httpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw OciException.internalServerError(
                    "fnserver " + path + " failed (" + response.statusCode() + "): " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Deterministic per-image digest that changes when the image changes (TF contract). */
    static String digestFor(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(image.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private static String shortId(String ocid) {
        return ocid.substring(Math.max(0, ocid.length() - 8));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
    }

    private static OciException notFound(String kind, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + kind + " " + id);
    }
}
