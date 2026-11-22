package io.floci.oci.services.artifacts;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactsRegistryManagerTest {

    private ContainerLifecycleManager lifecycle;
    private ContainerDetector detector;
    private CurrentContainerNetworkResolver networkResolver;
    private PortAllocator ports;
    private ArtifactsRegistryManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.services().artifacts().registryImage()).thenReturn("registry:2");
        lenient().when(config.services().artifacts().registryBasePort()).thenReturn(5200);
        lenient().when(config.services().artifacts().registryMaxPort()).thenReturn(5299);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c1", Map.of()));
        lenient().when(lifecycle.daemonHost()).thenReturn("docker.example");
        detector = mock(ContainerDetector.class);
        networkResolver = mock(CurrentContainerNetworkResolver.class);
        ports = mock(PortAllocator.class);
        when(ports.allocate(anyInt(), anyInt())).thenReturn(5201);
        manager = new ArtifactsRegistryManager(builder, lifecycle, detector, ports, networkResolver, config);
    }

    @Test
    void onTheHostTheRegistryIsReachedOnTheDaemonHost() {
        assertEquals(-1, manager.hostPort());
        manager.ensureStarted();
        assertEquals("docker.example:5201", manager.endpoint());
        assertEquals(5201, manager.hostPort());
    }

    @Test
    void inAContainerTheRegistryIsReachedByItsIpOnTheSharedNetwork() {
        when(detector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        when(lifecycle.resolveEndpoint("c1", 5000, "bridge"))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 5000));
        manager.ensureStarted();
        assertEquals("172.17.0.5:5000", manager.endpoint());
    }

    @Test
    void stopKeepsTheImageVolumeAndResetRemovesIt() {
        manager.ensureStarted();
        manager.stop();
        verify(lifecycle).stopAndRemove(eq("c1"), any());
        verify(lifecycle, never()).removeVolume(anyString());
        verify(ports).release(5201);

        manager.ensureStarted();
        manager.reset();
        verify(lifecycle).removeVolume(anyString());
    }
}
