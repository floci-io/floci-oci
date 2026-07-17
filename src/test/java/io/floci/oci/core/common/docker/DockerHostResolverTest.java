package io.floci.oci.core.common.docker;

import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerHostResolverTest {

    @Test
    void resolve_usesDetectedContainerNetworkIpWhenRunningInContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        CurrentContainerNetworkResolver currentContainerNetworkResolver =
                mock(CurrentContainerNetworkResolver.class);

        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.hostOverride()).thenReturn(Optional.empty());
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(currentContainerNetworkResolver.resolveContainerIp()).thenReturn(Optional.of("172.24.0.2"));

        DockerHostResolver resolver =
                new DockerHostResolver(config, containerDetector, currentContainerNetworkResolver);

        assertEquals("172.24.0.2", resolver.resolve());
    }
}
