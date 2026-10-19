package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * A {@code 0} host port means "any port". It must reach Docker as an empty binding so the daemon
 * picks a free port on its own host when it binds, instead of floci-oci probing in its own network
 * namespace, which says nothing about the Docker host and reserves nothing.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerPortBindingTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private EmulatorConfig config;

    @Mock
    private EmulatorConfig.DockerConfig dockerConfig;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }

    @Test
    void aZeroHostPortLeavesTheChoiceToDocker() {
        Ports.Binding binding = bindingSentFor(8080, 0);

        assertTrue(binding.getHostPortSpec() == null || binding.getHostPortSpec().isEmpty(),
                "a dynamic port must be sent without a host port, got " + binding.getHostPortSpec());
    }

    @Test
    void aFixedHostPortIsSentAsIs() {
        Ports.Binding binding = bindingSentFor(6443, 6450);

        assertEquals("6450", binding.getHostPortSpec());
    }

    private Ports.Binding bindingSentFor(int containerPort, int hostPort) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        ContainerSpec spec = new ContainerBuilder(config, mock(DockerHostResolver.class), null)
                .newContainer("busybox:stable")
                .withPortBinding(containerPort, hostPort)
                .build();

        manager.create(spec);

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        Ports.Binding[] bindings = hostConfig.getValue().getPortBindings().getBindings()
                .get(ExposedPort.tcp(containerPort));
        assertEquals(1, bindings.length);
        return bindings[0];
    }
}
