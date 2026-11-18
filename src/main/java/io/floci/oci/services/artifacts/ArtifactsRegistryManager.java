package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Driver for the one shared {@code registry:2} container behind the OCIR data plane. Every
 * tenancy and region pushes into it; the data plane keeps them apart with a path prefix.
 *
 * <p>Flag-free: the {@code mock()} gate lives in {@link ArtifactsService}. Lazy, idempotent and
 * self-healing {@link #ensureStarted()}, one cheap {@link #isReady()} probe, and a {@link #stop()}
 * that removes the container and its volume and releases the host port.
 */
@ApplicationScoped
public class ArtifactsRegistryManager {

    private static final Logger LOG = Logger.getLogger(ArtifactsRegistryManager.class);
    private static final int REGISTRY_PORT = 5000;
    private static final String BASE_NAME = "ocir-registry";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile String containerId;
    private volatile String endpoint;
    private volatile int hostPort = -1;

    @Inject
    public ArtifactsRegistryManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    ContainerDetector containerDetector, PortAllocator portAllocator,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /** Starts (or restarts) the shared registry container. Idempotent. */
    public synchronized void ensureStarted() {
        if (containerId != null && lifecycleManager.isContainerRunning(containerId)) {
            return;
        }
        if (containerId != null) {
            LOG.warn("OCIR registry container died; restarting");
            stop();
        }
        String name = ContainerStorageHelper.dockerName(config, BASE_NAME);
        lifecycleManager.removeIfExists(name);
        int port = portAllocator.allocate(config.services().artifacts().registryBasePort(),
                config.services().artifacts().registryMaxPort());
        ContainerLifecycleManager.ContainerInfo info;
        try {
            String volume = name + "-data";
            lifecycleManager.ensureVolume(volume);
            ContainerSpec spec = containerBuilder.newContainer(config.services().artifacts().registryImage())
                    .withName(name)
                    .withEnv("REGISTRY_STORAGE_DELETE_ENABLED", "true")
                    .withPortBinding(REGISTRY_PORT, port)
                    .withNamedVolume(volume, "/var/lib/registry")
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withLogRotation()
                    // One registry serves every tenancy and region, so it names no resource.
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels("artifacts", null, null, null))
                    .build();
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            portAllocator.release(port);
            throw e;
        }
        containerId = info.containerId();
        hostPort = port;
        endpoint = containerDetector.isRunningInContainer()
                ? name + ":" + REGISTRY_PORT
                : "localhost:" + port;
        LOG.infov("OCIR registry started ({0}) at {1}", containerId, endpoint);
    }

    /** {@code GET /v2/} answers 200 (or 401 behind auth) once the registry is serving. */
    public boolean isReady() {
        if (endpoint == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + endpoint + "/v2/"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 || status == 401;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isStarted() {
        return containerId != null;
    }

    /** {@code host:port} of the registry as floci-oci reaches it. */
    public String endpoint() {
        return endpoint;
    }

    public HttpClient httpClient() {
        return http;
    }

    public synchronized void stop() {
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
                lifecycleManager.removeVolume(ContainerStorageHelper.dockerName(config, BASE_NAME) + "-data");
            } catch (RuntimeException e) {
                LOG.warnv("Failed to stop the OCIR registry container: {0}", e.getMessage());
            }
            containerId = null;
            endpoint = null;
        }
        if (hostPort > 0) {
            portAllocator.release(hostPort);
            hostPort = -1;
        }
    }
}
