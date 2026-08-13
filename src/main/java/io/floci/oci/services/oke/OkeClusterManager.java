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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver for real-mode rancher/k3s Docker sidecar containers managing local Kubernetes clusters.
 */
@ApplicationScoped
public class OkeClusterManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(OkeClusterManager.class);
    private static final int K3S_CONTAINER_PORT = 6443;

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

        int basePort = config.services().oke().apiServerBasePort();
        int maxPort = config.services().oke().apiServerMaxPort();
        int hostPort = portAllocator.allocate(basePort, maxPort);
        cluster.setHostPort(hostPort);

        String nameSlug = cluster.getId();
        String containerName = ContainerStorageHelper.dockerName(config, "oke-" + nameSlug);
        String volumeName = ContainerStorageHelper.dockerName(config, "oke-vol-" + nameSlug);

        activeClusters.put(cluster.getId(), new ActiveClusterRef(containerName, volumeName, hostPort));

        try {
            lifecycleManager.removeIfExists(containerName);

            ContainerSpec spec = containerBuilder.newContainer(config.services().oke().defaultImage())
                    .withName(containerName)
                    .withEnv("K3S_KUBECONFIG_MODE", "644")
                    .withPortBinding(K3S_CONTAINER_PORT, hostPort)
                    .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                    .build();

            lifecycleManager.createAndStart(spec);
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
