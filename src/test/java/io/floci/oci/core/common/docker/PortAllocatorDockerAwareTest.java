package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A port another container publishes on the Docker host is free as far as this process's own
 * socket probe can tell when floci-oci runs in a container, so only the Docker API can rule it
 * out. Two floci-oci instances on one host collided this way on the first port of every range.
 */
class PortAllocatorDockerAwareTest {

    private static DockerClient dockerPublishing(Integer... publicPorts) {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);
        ContainerPort[] ports = new ContainerPort[publicPorts.length];
        for (int i = 0; i < publicPorts.length; i++) {
            ports[i] = new ContainerPort().withPublicPort(publicPorts[i]);
        }
        Container sibling = mock(Container.class);
        when(sibling.getPorts()).thenReturn(ports);
        when(listCmd.exec()).thenReturn(List.of(sibling));
        return dockerClient;
    }

    @Test
    void allocateSkipsAPortAnotherContainerPublishes() {
        PortAllocator allocator = new PortAllocator(dockerPublishing(19900));

        int allocated = allocator.allocate(19900, 19999);

        assertNotEquals(19900, allocated);
        allocator.release(allocated);
    }

    @Test
    void allocateAsksDockerOncePerAllocation() {
        DockerClient dockerClient = dockerPublishing(19900, 19901, 19902);
        PortAllocator allocator = new PortAllocator(dockerClient);

        int allocated = allocator.allocate(19900, 19999);

        assertEquals(19903, allocated, "the first port neither published nor bound");
        verify(dockerClient, times(1)).listContainersCmd();
        allocator.release(allocated);
    }

    @Test
    void aDockerErrorDoesNotBlockAllocation() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("docker daemon unreachable"));
        PortAllocator allocator = new PortAllocator(dockerClient);

        int allocated = allocator.allocate(19900, 19999);

        assertEquals(19900, allocated);
        allocator.release(allocated);
    }

    @Test
    void withoutDockerOnlyTheLocalProbeCounts() {
        PortAllocator allocator = new PortAllocator();

        int allocated = allocator.allocate(19900, 19999);

        assertEquals(19900, allocated);
        allocator.release(allocated);
    }
}
