package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImageCacheServiceTest {

    private static final String IMAGE = "container-registry.oracle.com/database/adb-free:latest-26ai";

    private DockerClient docker;
    private ImageCacheService images;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().registryCredentials()).thenReturn(List.of());
        when(config.docker().imagePullTimeoutSeconds()).thenReturn(1);
        docker = mock(DockerClient.class);
        InspectImageCmd inspect = mock(InspectImageCmd.class);
        when(docker.inspectImageCmd(anyString())).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("No such image"));
        PullImageCmd pull = mock(PullImageCmd.class);
        when(docker.pullImageCmd(anyString())).thenReturn(pull);
        when(pull.withAuthConfig(any())).thenReturn(pull);
        // The daemon never reports the pull finished.
        when(pull.exec(any())).thenAnswer(call -> call.getArgument(0));
        images = new ImageCacheService(docker, config);
    }

    @Test
    void aPullThatOutlivesTheTimeoutFailsAndIsNotRecordedAsPulled() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> images.ensureImageExists(IMAGE));
        assertTrue(e.getMessage().contains("did not finish within 1 seconds"), e.getMessage());
        verify(docker, times(1)).pullImageCmd(IMAGE);

        assertThrows(IllegalStateException.class, () -> images.ensureImageExists(IMAGE));
        verify(docker, times(2)).pullImageCmd(IMAGE);
    }
}
