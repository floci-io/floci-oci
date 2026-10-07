package io.floci.oci.services.functions;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        manager = new FnServerManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
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
                Map.of("io.floci", "oci", "io.floci.service", "functions", "io.floci.region", "us-ashburn-1"),
                spec.getValue().labels());
    }
}
