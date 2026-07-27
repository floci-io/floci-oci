package io.floci.oci.services.functions;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Driver for the shared {@code fnproject/fnserver} sidecar — the open-source engine
 * behind OCI Functions. One container serves every application; it spawns the actual
 * function containers itself via the mounted Docker socket.
 *
 * <p>Deliberately flag-free (the {@code mock()} gate lives in {@link FunctionsService})
 * and never blocks a request thread beyond the lazy first start. Lifecycle follows the
 * shared-sidecar shape: lazy idempotent {@link #ensureStarted()}, self-healing when the
 * container died underneath us, {@link PortAllocator#release(int)} on stop.
 */
@ApplicationScoped
public class FnServerManager {

    private static final Logger LOG = Logger.getLogger(FnServerManager.class);
    private static final int FN_PORT = 8080;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String containerId;
    private volatile String baseUrl;
    private volatile int hostPort = -1;

    @Inject
    public FnServerManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerDetector containerDetector,
                           PortAllocator portAllocator,
                           EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /** Lazily starts (or restarts) the shared fnserver container. Idempotent. */
    public synchronized void ensureStarted() {
        if (containerId != null && lifecycleManager.isContainerRunning(containerId)) {
            return;
        }
        if (containerId != null) {
            LOG.warn("fnserver container died — restarting");
            stop();
        }
        String name = ContainerStorageHelper.dockerName(config, "floci-oci-fnserver");
        lifecycleManager.removeIfExists(name);
        int port = portAllocator.allocate(
                config.services().functions().serverBasePort(),
                config.services().functions().serverMaxPort());
        // fnserver hands each function container a unix socket under its iofs directory.
        // Function containers are siblings on the SAME daemon, so the directory is shared
        // through a NAMED VOLUME whose name doubles as the daemon-side bind source
        // (FN_IOFS_DOCKER_PATH=<volumeName>) — the recipe from fnproject/fn's own Makefile.
        // Host bind-mounts break here: unix sockets don't survive Docker Desktop's shared
        // filesystems, failing every invoke with "Container failed to initialize".
        String iofsVolume = name + "-iofs";
        lifecycleManager.ensureVolume(iofsVolume);
        ContainerSpec spec = containerBuilder.newContainer(config.services().functions().serverImage())
                .withName(name)
                .withEnv("FN_LOG_LEVEL", "info")
                .withEnv("FN_IOFS_PATH", "/iofs")
                .withEnv("FN_IOFS_DOCKER_PATH", iofsVolume)
                .withPortBinding(FN_PORT, port)
                .withBind("/var/run/docker.sock", "/var/run/docker.sock")
                .withNamedVolume(iofsVolume, "/iofs")
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();
        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        containerId = info.containerId();
        hostPort = port;
        baseUrl = containerDetector.isRunningInContainer()
                ? "http://" + name + ":" + FN_PORT
                : "http://localhost:" + port;
        LOG.infof("fnserver started (%s) at %s", containerId, baseUrl);
    }

    /** One cheap readiness probe — the service's poller decides how often to ask. */
    public boolean isReady() {
        if (baseUrl == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/version"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isStarted() {
        return containerId != null;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public HttpClient httpClient() {
        return http;
    }

    public synchronized void stop() {
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
                lifecycleManager.removeVolume(
                        ContainerStorageHelper.dockerName(config, "floci-oci-fnserver") + "-iofs");
            } catch (Exception e) {
                LOG.warnv("Failed to stop fnserver container: {0}", e.getMessage());
            }
            containerId = null;
            baseUrl = null;
        }
        if (hostPort > 0) {
            portAllocator.release(hostPort);
            hostPort = -1;
        }
    }
}
