package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerExecTest {

    private static final String CONTAINER = "floci-oci-db";
    private static final String EXEC_ID = "exec-1";

    private DockerClient dockerClient;
    private ExecStartCmd startCmd;
    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn(EXEC_ID);
        when(createCmd.exec()).thenReturn(created);
        when(dockerClient.execCreateCmd(CONTAINER)).thenReturn(createCmd);
        startCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd(EXEC_ID)).thenReturn(startCmd);
        manager = new ContainerLifecycleManager(dockerClient, mock(ImageCacheService.class),
                mock(ContainerDetector.class), mock(PortAllocator.class), mock(EmulatorConfig.class));
    }

    @Test
    void execReturnsTheExitCodeAndBothStreams() {
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(frame(StreamType.STDOUT, "1 row"));
            callback.onNext(frame(StreamType.STDERR, "NOTICE: done"));
            callback.onComplete();
            return callback;
        });
        when(dockerClient.inspectExecCmd(EXEC_ID).exec().getExitCodeLong()).thenReturn(3L);

        ExecResult result = manager.exec(CONTAINER, List.of("PGPASSWORD=x"), List.of("psql", "-c", "select 1"),
                Duration.ofSeconds(5));

        assertEquals(new ExecResult(3, "1 row", "NOTICE: done"), result);
    }

    @Test
    void execKeepsStderrWhenTheCommandTimesOut() {
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(frame(StreamType.STDOUT, "partial"));
            callback.onNext(frame(StreamType.STDERR, "FATAL: connection refused"));
            return callback;
        });

        ExecResult result = manager.exec(CONTAINER, null, List.of("psql"), Duration.ofMillis(50));

        assertEquals(new ExecResult(-1, "partial", "FATAL: connection refused\ntimed out after 0s"), result);
    }

    private static Frame frame(StreamType stream, String text) {
        return new Frame(stream, text.getBytes(StandardCharsets.UTF_8));
    }
}
