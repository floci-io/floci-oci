package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where a client is told to reach a sidecar's published port, per {@code docker.endpoint-mode}. */
class ContainerLifecycleManagerClientEndpointTest {

    private EmulatorConfig config;
    private ContainerDetector containerDetector;
    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().dockerHost()).thenReturn("unix:///var/run/docker.sock");
        when(config.docker().endpointMode()).thenReturn(EmulatorConfig.DockerEndpointMode.AUTO);
        containerDetector = mock(ContainerDetector.class);
        manager = new ContainerLifecycleManager(mock(DockerClient.class), mock(ImageCacheService.class),
                containerDetector, mock(PortAllocator.class), config);
    }

    @Test
    void onTheHostClientsUseLocalhostAndThePublishedPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        assertEquals(new EndpointInfo("localhost", 15432), manager.clientEndpoint("floci-oci-db", 5432, 15432));
    }

    @Test
    void inAContainerClientsUseTheSidecarNameAndItsOwnPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        assertEquals(new EndpointInfo("floci-oci-db", 5432), manager.clientEndpoint("floci-oci-db", 5432, 15432));
    }

    @Test
    void publishedModeAlwaysUsesTheDaemonHostAndThePublishedPort() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.docker().endpointMode()).thenReturn(EmulatorConfig.DockerEndpointMode.PUBLISHED);

        assertEquals(new EndpointInfo("localhost", 15432), manager.clientEndpoint("floci-oci-db", 5432, 15432));
    }

    @Test
    void theDaemonHostIsTheHostOfATcpDaemonAndLocalhostOtherwise() {
        assertEquals("docker.internal.example", ContainerLifecycleManager.daemonHostname("tcp://docker.internal.example:2375"));
        assertEquals("localhost", ContainerLifecycleManager.daemonHostname("unix:///var/run/docker.sock"));
        assertEquals("localhost", ContainerLifecycleManager.daemonHostname(null));
        assertEquals("localhost", ContainerLifecycleManager.daemonHostname("not a uri"));
    }
}
