package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Docker --cap-add and --device reach the HostConfig of the created container. */
class ContainerLifecycleManagerCapabilitiesTest {

    private DockerClient dockerClient;
    private EmulatorConfig config;
    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        manager = new ContainerLifecycleManager(dockerClient, mock(ImageCacheService.class),
                mock(ContainerDetector.class), config);
    }

    @Test
    void capabilitiesAndDevicesAreAddedWithoutPrivilegedMode() {
        ContainerSpec spec = builder()
                .withCapAdd("SYS_ADMIN", "NET_ADMIN", "SYS_ADMIN")
                .withDevice("/dev/fuse")
                .build();
        assertEquals(List.of("SYS_ADMIN", "NET_ADMIN"), spec.capAdd());
        assertEquals(List.of("/dev/fuse"), spec.devices());

        HostConfig hostConfig = create(spec);

        assertArrayEquals(new Capability[]{Capability.SYS_ADMIN, Capability.NET_ADMIN}, hostConfig.getCapAdd());
        Device fuse = hostConfig.getDevices()[0];
        assertEquals("/dev/fuse", fuse.getPathOnHost());
        assertEquals("/dev/fuse", fuse.getPathInContainer());
        assertEquals("rwm", fuse.getcGroupPermissions());
        assertNotEquals(Boolean.TRUE, hostConfig.getPrivileged());
    }

    @Test
    void aContainerWithoutThemGetsNone() {
        HostConfig hostConfig = create(builder().build());

        assertNull(hostConfig.getCapAdd());
        assertNull(hostConfig.getDevices());
    }

    @Test
    void anUnknownCapabilityOrARelativeDeviceIsRejectedByTheBuilder() {
        assertThrows(IllegalArgumentException.class, () -> builder().withCapAdd("CAP_SYS_ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> builder().withDevice("dev/fuse"));
    }

    private ContainerBuilder.Builder builder() {
        return new ContainerBuilder(config, mock(DockerHostResolver.class), null).newContainer("busybox:stable");
    }

    private HostConfig create(ContainerSpec spec) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);

        manager.create(spec);

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}
