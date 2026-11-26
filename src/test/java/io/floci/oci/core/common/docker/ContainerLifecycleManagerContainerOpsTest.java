package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RestartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerContainerOpsTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private EmulatorConfig config;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }

    @Test
    void inspectIsEmptyForAMissingContainer() {
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("gone")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new NotFoundException("No such container"));

        assertEquals(Optional.empty(), manager.inspect("gone"));
    }

    @Test
    void inspectReturnsWhatDockerReports() {
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(dockerClient.inspectContainerCmd("web")).thenReturn(cmd);
        when(cmd.exec()).thenReturn(response);

        assertSame(response, manager.inspect("web").orElseThrow());
    }

    @Test
    void stopAndRestartPassTheTimeout() {
        StopContainerCmd stop = mock(StopContainerCmd.class);
        RestartContainerCmd restart = mock(RestartContainerCmd.class);
        when(dockerClient.stopContainerCmd("web")).thenReturn(stop);
        when(stop.withTimeout(5)).thenReturn(stop);
        when(dockerClient.restartContainerCmd("web")).thenReturn(restart);
        when(restart.withTimeout(5)).thenReturn(restart);

        manager.stop("web", 5);
        manager.restart("web", 5);

        verify(stop).exec();
        verify(restart).exec();
    }

    @Test
    void logsOfAMissingContainerAreEmpty() {
        LogContainerCmd cmd = mock(LogContainerCmd.class);
        when(dockerClient.logContainerCmd("gone")).thenReturn(cmd);
        when(cmd.withStdOut(true)).thenReturn(cmd);
        when(cmd.withStdErr(true)).thenReturn(cmd);
        when(cmd.exec(any())).thenThrow(new NotFoundException("No such container"));

        assertEquals("", manager.logs("gone", 1024, Duration.ofSeconds(1)));
    }
}
