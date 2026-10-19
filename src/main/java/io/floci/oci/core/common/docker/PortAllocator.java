package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Allocates host ports for sidecar port bindings (the OKE k3s API server, the Fn server,
 * PostgreSQL). A port counts as taken when this process reserved it, when another Docker
 * container already publishes it, or when it cannot be bound here.
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);

    // Ports reserved by this process but not yet bound by Docker.
    // Prevents TOCTOU races when multiple containers are launched concurrently.
    private final Set<Integer> reserved = new ConcurrentSkipListSet<>();

    // Null for an allocator built without Docker (unit tests), which then only probes locally.
    private final DockerClient dockerClient;

    public PortAllocator() {
        this(null);
    }

    @Inject
    public PortAllocator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * Atomically finds and reserves a free TCP port within the specified range.
     * The port is held in-memory until {@link #release(int)} is called, preventing
     * concurrent callers from picking the same port before Docker binds it.
     *
     * @param basePort the lowest port number to try (inclusive)
     * @param maxPort  the highest port number to try (inclusive)
     * @return a reserved free port within the range
     * @throws RuntimeException if no free port is available in the range
     */
    public synchronized int allocate(int basePort, int maxPort) {
        // One Docker call per allocation, not per candidate: this runs under the allocator's lock.
        Set<Integer> published = publishedHostPorts();
        for (int port = basePort; port <= maxPort; port++) {
            if (!reserved.contains(port) && !published.contains(port) && isPortFree(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}", String.valueOf(port), String.valueOf(basePort), String.valueOf(maxPort));
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    /**
     * Marks a port as reserved without probing whether it is free. Used on restart to
     * re-reserve host ports already held by surviving containers (e.g. persisted EC2
     * port forwards) so the allocator does not hand them out again.
     */
    public synchronized void markReserved(int port) {
        reserved.add(port);
    }

    /**
     * Releases a previously allocated port back to the pool.
     * Should be called when the Docker container that was using the port is removed.
     */
    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", String.valueOf(port));
        }
    }

    /**
     * Checks if a specific port is currently free, by binding it in this process's own network
     * namespace. From inside a container that cannot see a port another container publishes on
     * the host, which is why {@link #allocate} also asks Docker.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    public boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every host port a Docker container publishes, running or not: a created container that has
     * not started yet already holds its binding. Empty without a Docker client, and when Docker
     * cannot be asked, so a failed lookup never blocks an allocation.
     */
    Set<Integer> publishedHostPorts() {
        if (dockerClient == null) {
            return Set.of();
        }
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            Set<Integer> published = new HashSet<>();
            for (Container container : containers) {
                ContainerPort[] ports = container.getPorts();
                if (ports == null) {
                    continue;
                }
                for (ContainerPort port : ports) {
                    if (port.getPublicPort() != null) {
                        published.add(port.getPublicPort());
                    }
                }
            }
            return published;
        } catch (RuntimeException e) {
            LOG.debugv("Could not list the ports Docker containers publish: {0}", e.getMessage());
            return Set.of();
        }
    }
}
