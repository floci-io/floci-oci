package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Every emulator-created container and volume must carry the shared Floci labels
 * ({@code floci=true}, {@code floci_emulator=floci-oci}, and {@code floci_namespace}
 * when configured) so a single Docker host running several Floci emulators can be
 * pruned per emulator. Asserting at the {@link ContainerLifecycleManager} choke point
 * covers every service by construction.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerLabelsTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private EmulatorConfig config;

    @Mock
    private EmulatorConfig.DockerConfig dockerConfig;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }

    @Test
    void createAppliesDefaultLabelsToEveryContainer() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager.create(new ContainerSpec("busybox:stable"));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci"),
                capturedLabels(createCmd));
    }

    @Test
    void createMergesSpecLabelsOverDefaults() {
        lenient().when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        CreateContainerCmd createCmd = stubCreateContainer();
        ContainerSpec spec = new ContainerBuilder(config, mock(DockerHostResolver.class), null)
                .newContainer("busybox:stable")
                .withLabel("floci_service", "functions")
                .build();

        manager.create(spec);

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci", "floci_service", "functions"),
                capturedLabels(createCmd));
    }

    @Test
    void createIncludesNamespaceLabelWhenConfigured() {
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));
        CreateContainerCmd createCmd = stubCreateContainer();

        manager.create(new ContainerSpec("busybox:stable"));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci", "floci_namespace", "run-one"),
                capturedLabels(createCmd));
    }

    @Test
    void ensureVolumeAppliesTheSameDefaultLabels() {
        InspectVolumeCmd inspectCmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("floci-oci-fnserver-iofs")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("No such volume"));
        CreateVolumeCmd createVolumeCmd = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(createVolumeCmd);

        manager.ensureVolume("floci-oci-fnserver-iofs");

        ArgumentCaptor<Map<String, String>> labels = labelsCaptor();
        verify(createVolumeCmd).withLabels(labels.capture());
        assertEquals(Map.of("floci", "true", "floci_emulator", "floci-oci"), labels.getValue());
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private Map<String, String> capturedLabels(CreateContainerCmd createCmd) {
        ArgumentCaptor<Map<String, String>> labels = labelsCaptor();
        verify(createCmd).withLabels(labels.capture());
        return labels.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> labelsCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
    }
}
