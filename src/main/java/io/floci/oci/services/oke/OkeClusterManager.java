package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver for real-mode rancher/k3s Docker sidecar containers managing local Kubernetes clusters.
 */
@ApplicationScoped
public class OkeClusterManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(OkeClusterManager.class);
    private static final int K3S_CONTAINER_PORT = 6443;
    static final String API_TOKEN_ENV = "FLOCI_OKE_API_TOKEN";
    static final String TOKEN_DIR = "/var/lib/rancher/k3s/floci";
    static final String TOKEN_FILE = TOKEN_DIR + "/tokens.csv";

    /**
     * Writes the k3s static token file from {@link #API_TOKEN_ENV}, then execs k3s with the
     * container CMD. The image's own CMD is {@code agent}, which needs a server to join and exits
     * at once, so {@link #buildCmd()} always supplies {@code server}. The token travels as an env
     * var so nothing from the cluster record is ever interpolated into the script.
     */
    static final List<String> TOKEN_FILE_ENTRYPOINT = List.of("sh", "-c",
            "umask 077 && mkdir -p " + TOKEN_DIR
                    + " && printf '%s,floci-admin,floci-admin,system:masters\\n' \"$" + API_TOKEN_ENV + "\""
                    + " > " + TOKEN_FILE
                    + " && exec /bin/k3s \"$@\"");

    public record ActiveClusterRef(String containerName, String volumeName, int hostPort) {}

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    private final Map<String, ActiveClusterRef> activeClusters = new ConcurrentHashMap<>();

    @Inject
    public OkeClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             PortAllocator portAllocator,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    public void startCluster(StoredOkeCluster cluster) {
        if (config.services().oke().mock()) {
            cluster.setLifecycleState("ACTIVE");
            return;
        }
        if (cluster.getApiToken() == null || cluster.getApiToken().isBlank()) {
            throw new IllegalStateException("OKE cluster " + cluster.getId() + " has no API token");
        }

        int hostPort = cluster.getHostPort();
        if (hostPort > 0) {
            portAllocator.markReserved(hostPort);
        } else {
            int basePort = config.services().oke().apiServerBasePort();
            int maxPort = config.services().oke().apiServerMaxPort();
            hostPort = portAllocator.allocate(basePort, maxPort);
            cluster.setHostPort(hostPort);
        }

        String nameSlug = cluster.getId();
        String containerName = ContainerStorageHelper.dockerName(config, "oke-" + nameSlug);
        String volumeName = ContainerStorageHelper.dockerName(config, "oke-vol-" + nameSlug);

        activeClusters.put(cluster.getId(), new ActiveClusterRef(containerName, volumeName, hostPort));

        try {
            if (!lifecycleManager.isContainerRunning(containerName)) {
                lifecycleManager.removeIfExists(containerName);

                ContainerSpec spec = containerBuilder.newContainer(config.services().oke().defaultImage())
                        .withName(containerName)
                        .withEntrypoint(TOKEN_FILE_ENTRYPOINT)
                        .withCmd(buildCmd())
                        .withEnv("K3S_KUBECONFIG_MODE", "644")
                        .withEnv(API_TOKEN_ENV, cluster.getApiToken())
                        .withPortBinding(K3S_CONTAINER_PORT, hostPort)
                        .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                        .withPrivileged(true)
                        .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                                "oke", cluster.getId(), cluster.getCompartmentId(), config.defaultRegion()))
                        .build();

                lifecycleManager.createAndStart(spec);
            }
        } catch (Exception e) {
            activeClusters.remove(cluster.getId());
            try {
                lifecycleManager.removeIfExists(containerName);
                lifecycleManager.removeVolume(volumeName);
            } catch (Exception cleanupEx) {
                LOG.warnf(cleanupEx, "Failed to clean up container '%s' or volume '%s' after startup failure", containerName, volumeName);
            }
            portAllocator.release(hostPort);
            cluster.setHostPort(0);
            cluster.setLifecycleState("FAILED");
            LOG.errorf("Failed to start k3s sidecar container '%s' on port %d: %s", containerName, hostPort, e.getMessage());
            throw e;
        }

        cluster.setEndpoints(Map.of(
            "kubernetes", "https://127.0.0.1:" + hostPort,
            "privateEndpoint", "10.0.0.10:" + hostPort
        ));
        cluster.setLifecycleState("ACTIVE");
        LOG.infof("Started k3s sidecar container '%s' bound to host port %d", containerName, hostPort);
    }

    static List<String> buildServerArgs() {
        return List.of("server",
                "--disable=traefik",
                "--kube-apiserver-arg=token-auth-file=" + TOKEN_FILE);
    }

    /**
     * The CMD paired with {@link #TOKEN_FILE_ENTRYPOINT}: a {@code $0} placeholder, then the k3s
     * server args that the script's {@code "$@"} expands to.
     */
    static List<String> buildCmd() {
        List<String> cmd = new ArrayList<>();
        cmd.add("k3s");
        cmd.addAll(buildServerArgs());
        return cmd;
    }

    public void registerExistingCluster(StoredOkeCluster cluster) {
        if (cluster == null || cluster.getId() == null || config.services().oke().mock()) {
            return;
        }
        startCluster(cluster);
    }

    public void stopCluster(StoredOkeCluster cluster) {
        String nameSlug = cluster.getId();
        ActiveClusterRef ref = activeClusters.get(cluster.getId());
        String containerName = ref != null ? ref.containerName() : ContainerStorageHelper.dockerName(config, "oke-" + nameSlug);
        String volumeName = ref != null ? ref.volumeName() : ContainerStorageHelper.dockerName(config, "oke-vol-" + nameSlug);
        int hostPort = ref != null && ref.hostPort() > 0 ? ref.hostPort() : cluster.getHostPort();

        lifecycleManager.removeIfExists(containerName);

        try {
            removeVolumeIfConfigured(volumeName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove volume '%s' for cluster '%s'", volumeName, cluster.getId());
        }

        if (hostPort > 0) {
            portAllocator.release(hostPort);
        }
        activeClusters.remove(cluster.getId());
        LOG.infof("Stopped k3s sidecar container '%s' and released host port %d", containerName, hostPort);
    }

    @PreDestroy
    void shutdown() {
        clear();
    }

    @Override
    public void clear() {
        for (Map.Entry<String, ActiveClusterRef> entry : activeClusters.entrySet()) {
            ActiveClusterRef ref = entry.getValue();
            try {
                lifecycleManager.removeIfExists(ref.containerName());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s sidecar container '%s'", ref.containerName());
            }
            try {
                removeVolumeIfConfigured(ref.volumeName());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s volume '%s'", ref.volumeName());
            }
            try {
                portAllocator.release(ref.hostPort());
                LOG.infof("Cleaned up k3s sidecar container '%s' and released host port %d", ref.containerName(), ref.hostPort());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to release host port %d for container '%s'", ref.hostPort(), ref.containerName());
            }
        }
        activeClusters.clear();
    }

    private void removeVolumeIfConfigured(String volumeName) {
        boolean isMemory = "memory".equalsIgnoreCase(config.storage().mode());
        if (isMemory || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infof("Retained Docker volume '%s'. Remove manually: docker volume rm %s", volumeName, volumeName);
        }
    }
}
