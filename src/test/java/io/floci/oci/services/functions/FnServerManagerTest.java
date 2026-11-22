package io.floci.oci.services.functions;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FnServerManagerTest {

    @Mock
    private ContainerLifecycleManager lifecycleManager;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private CurrentContainerNetworkResolver networkResolver;

    private EmulatorConfig config;
    private FnServerManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(config.services().functions().serverImage()).thenReturn("fnproject/fnserver:latest");
        lenient().when(config.services().functions().serverBasePort()).thenReturn(8080);
        lenient().when(config.services().functions().serverMaxPort()).thenReturn(8099);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");

        ContainerBuilder containerBuilder = new ContainerBuilder(config, mock(DockerHostResolver.class), null);
        manager = new FnServerManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                networkResolver, config);
    }

    @Test
    void ensureStartedLabelsFnServerWithoutResourceId() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("fn-1", Map.of()));

        manager.ensureStarted();

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals(
                Map.of("io.floci", "oci", "io.floci.service", "functions"),
                spec.getValue().labels());
    }

    @Test
    void onTheHostFnServerIsReachedOnTheDaemonHost() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("fn-1", Map.of()));
        when(lifecycleManager.daemonHost()).thenReturn("docker.example");

        manager.ensureStarted();

        assertEquals("http://docker.example:8081", manager.baseUrl());
    }

    @Test
    void inAContainerFnServerIsReachedByItsIpOnTheSharedNetwork() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("fn-1", Map.of()));
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        when(lifecycleManager.resolveEndpoint("fn-1", 8080, "bridge"))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.17.0.6", 8080));

        manager.ensureStarted();

        assertEquals("http://172.17.0.6:8080", manager.baseUrl());
    }

    @Test
    void aConfiguredNetworkIsPreferredOverTheDetectedOne() {
        when(config.services().dockerNetwork()).thenReturn(Optional.of("floci_oci_default"));
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("fn-1", Map.of()));
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(lifecycleManager.resolveEndpoint("fn-1", 8080, "floci_oci_default"))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("fd00::6", 8080));

        manager.ensureStarted();

        assertEquals("http://[fd00::6]:8080", manager.baseUrl());
        verify(networkResolver, never()).resolveNetworkName();
    }

    @Test
    void aFailedStartReleasesItsPort() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("Bind for 0.0.0.0:8081 failed: port is already allocated"));

        assertThrows(RuntimeException.class, () -> manager.ensureStarted());

        verify(portAllocator).release(8081);
        assertFalse(manager.isStarted());
    }

    @Test
    void aFailedVolumeSetupReleasesItsPort() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081);
        doThrow(new RuntimeException("docker daemon unreachable"))
                .when(lifecycleManager).ensureVolume(any());

        assertThrows(RuntimeException.class, () -> manager.ensureStarted());

        verify(portAllocator).release(8081);
        verify(lifecycleManager, never()).createAndStart(any());
        assertFalse(manager.isStarted());
    }

    @Test
    void aFailedAddressLookupRemovesTheContainerSoTheNextCallStartsAgain() {
        when(portAllocator.allocate(8080, 8099)).thenReturn(8081, 8082);
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("fn-1", Map.of()),
                        new ContainerLifecycleManager.ContainerInfo("fn-2", Map.of()));
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        when(lifecycleManager.resolveEndpoint("fn-1", 8080, "bridge"))
                .thenThrow(new RuntimeException("docker inspect timed out"));
        when(lifecycleManager.resolveEndpoint("fn-2", 8080, "bridge"))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.17.0.7", 8080));

        assertThrows(RuntimeException.class, () -> manager.ensureStarted());

        verify(lifecycleManager).stopAndRemove("fn-1", null);
        verify(portAllocator).release(8081);
        assertFalse(manager.isStarted());

        manager.ensureStarted();

        assertEquals("http://172.17.0.7:8080", manager.baseUrl());
    }
}
