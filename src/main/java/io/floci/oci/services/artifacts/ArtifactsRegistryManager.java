package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
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
 * self-healing {@link #ensureStarted()}, one cheap {@link #isReady()} probe, a {@link #stop()} that
 * removes the container and releases the host port, and a {@link #reset()} that also removes the
 * volume. The volume outlives {@code stop()}, so pushed images survive a restart of the container or
 * of floci-oci itself.
 */
@ApplicationScoped
public class ArtifactsRegistryManager {

    private static final Logger LOG = Logger.getLogger(ArtifactsRegistryManager.class);
    private static final int REGISTRY_PORT = 5000;
    private static final String BASE_NAME = "ocir-registry";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final CurrentContainerNetworkResolver networkResolver;
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
                                    CurrentContainerNetworkResolver networkResolver, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.networkResolver = networkResolver;
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
            String volume = volumeName();
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
        endpoint = reachableEndpoint(info.containerId(), port);
        LOG.infov("OCIR registry started ({0}) at {1}", containerId, endpoint);
    }

    /**
     * Where floci-oci reaches the registry. In a container that is the sidecar's IP on the network
     * they share, which also works on Docker's default {@code bridge}, where container names do
     * not resolve. On the host it is the published port on the Docker daemon's host, which is not
     * {@code localhost} when the daemon is remote.
     */
    private String reachableEndpoint(String id, int port) {
        if (!containerDetector.isRunningInContainer()) {
            return lifecycleManager.daemonHost() + ":" + port;
        }
        String network = config.services().dockerNetwork()
                .filter(n -> !n.isBlank())
                .or(networkResolver::resolveNetworkName)
                .orElse(null);
        ContainerLifecycleManager.EndpointInfo info = lifecycleManager.resolveEndpoint(id, REGISTRY_PORT, network);
        String host = info.host().contains(":") ? "[" + info.host() + "]" : info.host();
        return host + ":" + info.port();
    }

    /**
     * Starts the registry if needed and waits, bounded, until it serves. Only the requests that need
     * the registry call this: the data plane, and the image reads and deletes of repositories that
     * have had a push.
     */
    public void ensureReady() {
        ensureStarted();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (!isReady()) {
            if (System.nanoTime() > deadline) {
                throw OciException.serviceUnavailable("The OCIR registry container did not become ready");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw OciException.serviceUnavailable("Interrupted while waiting for the OCIR registry");
            }
        }
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

    /** Removes the container and releases its port. The image volume is kept. */
    public synchronized void stop() {
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
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

    /** {@link #stop()}, then removes the volume holding every pushed image. */
    public synchronized void reset() {
        stop();
        lifecycleManager.removeVolume(volumeName());
    }

    private String volumeName() {
        return ContainerStorageHelper.dockerName(config, BASE_NAME) + "-data";
    }
}
