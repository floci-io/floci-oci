package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which of a sidecar's addresses floci-oci uses when it runs in a container itself. */
class ContainerLifecycleManagerContainerIpTest {

    private DockerClient dockerClient;
    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        manager = new ContainerLifecycleManager(dockerClient, mock(ImageCacheService.class), containerDetector,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
    }

    private static ContainerNetwork network(String ipv4, String ipv6) {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(ipv4);
        when(network.getGlobalIPv6Address()).thenReturn(ipv6);
        return network;
    }

    private void attachedTo(Map<String, ContainerNetwork> networks) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(networks);
        when(dockerClient.inspectContainerCmd("sidecar").exec()).thenReturn(inspect);
    }

    @Test
    void theSharedNetworksIpv4AddressIsPreferred() {
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("bridge", network("172.17.0.5", ""));
        networks.put("shared", network("172.20.0.5", "fd00::5"));
        attachedTo(networks);

        assertEquals(new EndpointInfo("172.20.0.5", 8080), manager.resolveEndpoint("sidecar", 8080, "shared"));
    }

    @Test
    void anIpv6OnlySharedNetworkGivesItsIpv6AddressNotTheBridgeOne() {
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("bridge", network("172.17.0.5", ""));
        networks.put("shared", network("", "fd00::5"));
        attachedTo(networks);

        assertEquals(new EndpointInfo("fd00::5", 8080), manager.resolveEndpoint("sidecar", 8080, "shared"));
    }
}
