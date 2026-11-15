package io.floci.oci.lifecycle;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.lifecycle.inithook.InitializationHook;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Floci control plane, served under {@code /_floci}, the prefix every Floci
 * emulator shares, and under the legacy {@code /_floci-oci}, which stays as a permanent alias.
 */
@Path("{prefix:(_floci-oci|_floci)}")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    private static final Logger LOG = Logger.getLogger(EmulatorInfoController.class);
    static final int MANIFEST_VERSION = 1;
    static final int CONSOLE_CONTRACT = 2;
    static final String CLOUD = "oci";
    static final String PEM_FILE = "application/x-pem-file";
    private static final String CERT_FILES_PROPERTY = "quarkus.http.ssl.certificate.files";
    private static final Pattern PEM_CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+?-----END CERTIFICATE-----");

    private final ServiceRegistry serviceRegistry;
    private final InitLifecycleState initLifecycleState;
    private final String version;

    private final StorageFactory storageFactory;
    private final Instance<Resettable> resettables;
    private final EmulatorConfig config;

    @Inject
    public EmulatorInfoController(ServiceRegistry serviceRegistry,
                                  InitLifecycleState initLifecycleState,
                                  StorageFactory storageFactory,
                                  Instance<Resettable> resettables,
                                  EmulatorConfig config) {
        this.serviceRegistry = serviceRegistry;
        this.initLifecycleState = initLifecycleState;
        this.storageFactory = storageFactory;
        this.resettables = resettables;
        this.config = config;
        this.version = resolveVersion();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(healthBody(serviceRegistry.getServices(), version)).build();
    }

    /** The health body {@code /health} and both control-plane prefixes share. */
    static Map<String, Object> healthBody(Map<String, String> services, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("cloud", CLOUD);
        body.put("services", services);
        body.put("edition", "community");
        body.put("original_edition", "floci-always-free");
        body.put("version", version);
        return body;
    }

    @GET
    @Path("/init")
    public Response init() {
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("boot", initLifecycleState.isBootCompleted());
        completed.put("start", initLifecycleState.isStartCompleted());
        completed.put("ready", initLifecycleState.isReadyCompleted());
        completed.put("shutdown", initLifecycleState.isShutdownStarted());

        Map<String, Object> scripts = new LinkedHashMap<>();
        for (InitializationHook hook : InitializationHook.values()) {
            scripts.put(hook.getResponseKey(), initLifecycleState.getScripts(hook).stream()
                    .map(r -> Map.of("script", r.script(), "state", r.state(), "return_code", r.returnCode()))
                    .toList());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completed", completed);
        body.put("scripts", scripts);
        return Response.ok(body).build();
    }

    /** The Floci Console Contract v2 manifest. A reader must ignore keys it does not know. */
    @GET
    @Path("/info")
    public Response info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest_version", MANIFEST_VERSION);
        body.put("console_contract", CONSOLE_CONTRACT);
        body.put("cloud", CLOUD);
        body.put("emulator", "floci-oci");
        body.put("version", version);
        body.put("edition", "community");
        body.put("original_edition", "floci-always-free");
        body.put("endpoint", config.effectiveBaseUrl());
        body.put("health_url", "/_floci/health");
        body.put("tls", Map.of("enabled", config.tls().enabled()));
        body.put("services", manifestServices(serviceRegistry.getServices()));

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("tenancy_id", config.defaultTenancyId());
        defaults.put("compartment_id", config.defaultTenancyId());
        defaults.put("region", config.defaultRegion());
        defaults.put("namespace", config.defaultNamespace());
        body.put("defaults", defaults);

        body.put("ui", Map.of("enabled", false));
        return Response.ok(body).build();
    }

    /** The health map's {@code running} and {@code available}, as the manifest's {@code enabled} and {@code disabled}. */
    static Map<String, String> manifestServices(Map<String, String> healthServices) {
        Map<String, String> services = new TreeMap<>();
        healthServices.forEach((name, status) -> services.put(name, "running".equals(status) ? "enabled" : "disabled"));
        return services;
    }

    /**
     * The certificate clients must trust: the PEM certificates the HTTPS endpoint serves, never a private
     * key the certificate file may also hold. It answers both a client asking for PEM and one asking for
     * JSON, since the 404 explaining why there is no certificate is JSON.
     */
    @GET
    @Path("/tls-cert")
    @Produces({PEM_FILE, MediaType.APPLICATION_JSON})
    public Response tlsCert() {
        boolean tlsEnabled = config.tls().enabled();
        Optional<String> pem = tlsEnabled ? currentCertificatePem() : Optional.empty();
        if (pem.isPresent()) {
            return Response.ok(pem.get()).type(PEM_FILE).build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tlsEnabled", tlsEnabled);
        if (!tlsEnabled) {
            body.put("error", "TLS is not enabled");
            body.put("message", "floci-oci is serving plain HTTP only. Set FLOCI_OCI_TLS_ENABLED=true and "
                    + "restart to serve HTTPS on the same port.");
        } else {
            body.put("error", "TLS certificate not available yet");
            body.put("message", "TLS is enabled but no certificate is available. "
                    + "Check the startup logs for certificate generation or read errors.");
        }
        return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Optional<String> currentCertificatePem() {
        Optional<String> certFile = ConfigProvider.getConfig().getOptionalValue(CERT_FILES_PROPERTY, String.class);
        if (certFile.isEmpty() || certFile.get().isBlank()) {
            return Optional.empty();
        }
        try {
            return certificatesOnly(Files.readString(Paths.get(certFile.get().split(",")[0].trim())));
        } catch (IOException e) {
            LOG.warnv("Could not read the TLS certificate {0}: {1}", certFile.get(), e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<String> certificatesOnly(String pem) {
        StringBuilder certificates = new StringBuilder();
        Matcher matcher = PEM_CERTIFICATE.matcher(pem);
        while (matcher.find()) {
            certificates.append(matcher.group()).append('\n');
        }
        return certificates.isEmpty() ? Optional.empty() : Optional.of(certificates.toString());
    }

    @GET
    @Path("/diagnose")
    public Response diagnose() {
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/config")
    public Response config() {
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/state/reset")
    public Response reset() {
        performReset();
        return Response.ok(Map.of("status", "OK")).build();
    }

    @POST
    @Path("/state/nuke")
    public Response nuke() {
        return reset();
    }

    private void performReset() {
        for (Resettable r : resettables) {
            r.clear();
        }
        storageFactory.clearAll();
    }

    static String resolveVersion() {
        String env = System.getenv("FLOCI_OCI_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }
}
