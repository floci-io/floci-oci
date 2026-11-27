package io.floci.oci.services.containerinstances;

import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.services.containerinstances.model.StoredContainer;
import io.floci.oci.services.containerinstances.model.StoredContainerInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContainerInstanceManagerTest {

    private EmulatorConfig config;
    private ContainerBuilder builder;
    private ContainerBuilder.Builder spec;
    private ContainerLifecycleManager lifecycle;
    private ImageCacheService images;
    private ContainerInstanceManager manager;
    private final List<Map<String, byte[]>> copied = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.services().containerinstances().pauseImage()).thenReturn("registry.k8s.io/pause:3.10");
        builder = mock(ContainerBuilder.class);
        spec = mock(ContainerBuilder.Builder.class, invocation ->
                invocation.getMethod().getReturnType() == ContainerBuilder.Builder.class ? invocation.getMock() : null);
        lenient().when(builder.newContainer(anyString())).thenReturn(spec);
        lenient().when(builder.resolveImage(anyString())).thenAnswer(call -> call.getArgument(0));
        lenient().when(spec.build()).thenReturn(new ContainerSpec("busybox"));
        lifecycle = mock(ContainerLifecycleManager.class, RETURNS_DEEP_STUBS);
        lenient().when(lifecycle.create(any())).thenReturn("created-id");
        lenient().doAnswer(call -> {
            copied.add(untar(Files.readAllBytes(call.getArgument(1, Path.class))));
            return null;
        }).when(lifecycle).copyToContainer(anyString(), any(), anyString());
        images = mock(ImageCacheService.class);
        manager = new ContainerInstanceManager(config, builder, lifecycle, images);
    }

    private static StoredContainerInstance instance(List<Map<String, Object>> volumes) {
        StoredContainerInstance instance = new StoredContainerInstance();
        instance.setId("ocid1.computecontainerinstance.oc1.iad.unit");
        instance.setCompartmentId("ocid1.compartment.oc1..unit");
        instance.setRegion("us-ashburn-1");
        instance.getAttributes().put("volumes", volumes);
        return instance;
    }

    private static StoredContainer container(Map<String, Object> attributes) {
        StoredContainer container = new StoredContainer();
        container.setId("ocid1.computecontainer.oc1.iad.unit");
        container.setImageUrl("iad.ocir.localhost.floci.io/ns/app:1");
        container.setAttributes(new LinkedHashMap<>(attributes));
        return container;
    }

    @Test
    void containersJoinThePauseContainersNetworkAndGetTheirSettings() {
        StoredContainerInstance instance = instance(List.of(Map.of("name", "scratch", "volumeType", "EMPTYDIR",
                "backingStore", "EPHEMERAL_STORAGE")));
        StoredContainer container = container(Map.of(
                "command", List.of("httpd"), "arguments", List.of("-f", "-p", "8080"),
                "workingDirectory", "/srv", "environmentVariables", Map.of("MODE", "test"),
                "resourceConfig", Map.of("vcpusLimit", 0.5, "memoryLimitInGBs", 1.0),
                "volumeMounts", List.of(Map.of("volumeName", "scratch", "mountPath", "/data"))));

        manager.startPod(instance, List.of(container), image -> "localhost:5200/iad/ns/app:1");

        verify(builder).newContainer("registry.k8s.io/pause:3.10");
        verify(spec).withDockerNetwork(Optional.empty());
        verify(builder).newContainer("localhost:5200/iad/ns/app:1");
        verify(images).ensureImageExists("localhost:5200/iad/ns/app:1");
        verify(spec).withNetworkMode("container:" + manager.podName(instance));
        verify(spec).withEntrypoint(List.of("httpd"));
        verify(spec).withCmd(List.of("-f", "-p", "8080"));
        verify(spec).withWorkingDir("/srv");
        verify(spec).withEnv("MODE", "test");
        verify(spec).withNanoCpus(500_000_000L);
        verify(spec).withMemoryBytes(1024L * 1024L * 1024L);
        String volume = manager.volumeName(instance, "scratch");
        verify(lifecycle).ensureVolume(volume, Map.of());
        verify(spec).withNamedVolume(volume, "/data", false);
        verify(lifecycle).startCreated(eq("created-id"), any());
        assertEquals(manager.containerName(container), container.getDockerName());
    }

    @Test
    void configFilesAreSeededIntoTheVolumeBeforeAnyContainerStarts() {
        String data = Base64.getEncoder().encodeToString("port=8080\n".getBytes(StandardCharsets.UTF_8));
        StoredContainerInstance instance = instance(List.of(Map.of("name", "conf", "volumeType", "CONFIGFILE",
                "configs", List.of(Map.of("fileName", "app.conf", "data", data, "path", "nested")))));
        StoredContainer container = container(Map.of(
                "volumeMounts", List.of(Map.of("volumeName", "conf", "mountPath", "/etc/app"))));

        manager.startPod(instance, List.of(container), image -> image);

        String volume = manager.volumeName(instance, "conf");
        assertEquals(1, copied.size());
        assertArrayEquals("port=8080\n".getBytes(StandardCharsets.UTF_8),
                copied.get(0).get("floci-config/nested/app.conf"));
        InOrder order = inOrder(lifecycle, spec);
        order.verify(spec).withNamedVolume(volume, "/floci-config", false);
        order.verify(lifecycle).copyToContainer(eq("created-id"), any(), eq("/"));
        order.verify(lifecycle).forceRemove(manager.seedName(instance, "conf"));
        order.verify(spec).withNamedVolume(volume, "/etc/app", false);
        order.verify(lifecycle).startCreated(eq("created-id"), any());
    }

    @Test
    void aSeedHelperDockerCannotRemoveFailsTheStartAndIsRemovedBeforeItsVolume() {
        String data = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        StoredContainerInstance instance = instance(List.of(Map.of("name", "conf", "volumeType", "CONFIGFILE",
                "configs", List.of(Map.of("fileName", "a", "data", data)))));
        StoredContainer container = container(Map.of());
        String seed = manager.seedName(instance, "conf");
        String volume = manager.volumeName(instance, "conf");
        assertEquals(seed, manager.seedName(instance, "conf"));
        doNothing().doThrow(new InternalServerErrorException("removal failed")).doNothing()
                .when(lifecycle).forceRemove(seed);

        InternalServerErrorException e = assertThrows(InternalServerErrorException.class,
                () -> manager.startPod(instance, List.of(container), i -> i));

        assertTrue(e.getMessage().contains("removal failed"), e.getMessage());
        verify(lifecycle, never()).startCreated(any(), any());
        verify(lifecycle, never()).deleteVolume(anyString());

        manager.removePod(instance, List.of(container));
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).forceRemove(seed);
        order.verify(lifecycle).deleteVolume(volume);
    }

    @Test
    void aMemoryVolumeIsOneTmpfsVolumeSharedByEveryContainerThatMountsIt() {
        StoredContainerInstance instance = instance(List.of(Map.of("name", "shm", "volumeType", "EMPTYDIR",
                "backingStore", "MEMORY")));
        StoredContainer writer = container(Map.of(
                "volumeMounts", List.of(Map.of("volumeName", "shm", "mountPath", "/out"))));
        StoredContainer reader = container(Map.of(
                "volumeMounts", List.of(Map.of("volumeName", "shm", "mountPath", "/in"))));
        reader.setId("ocid1.computecontainer.oc1.iad.reader");

        manager.startPod(instance, List.of(writer, reader), image -> image);

        String volume = manager.volumeName(instance, "shm");
        verify(lifecycle, times(2)).ensureVolume(volume, Map.of("type", "tmpfs", "device", "tmpfs"));
        verify(spec).withNamedVolume(volume, "/out", false);
        verify(spec).withNamedVolume(volume, "/in", false);
        verify(spec, never()).withMount(any());
    }

    @Test
    void isReadOnlyMountsEveryVolumeTypeReadOnly() {
        String data = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        StoredContainerInstance instance = instance(List.of(
                Map.of("name", "shm", "volumeType", "EMPTYDIR", "backingStore", "MEMORY"),
                Map.of("name", "disk", "volumeType", "EMPTYDIR", "backingStore", "EPHEMERAL_STORAGE"),
                Map.of("name", "conf", "volumeType", "CONFIGFILE",
                        "configs", List.of(Map.of("fileName", "a", "data", data)))));
        StoredContainer container = container(Map.of("volumeMounts", List.of(
                Map.of("volumeName", "shm", "mountPath", "/shm", "isReadOnly", true),
                Map.of("volumeName", "disk", "mountPath", "/disk", "isReadOnly", true),
                Map.of("volumeName", "conf", "mountPath", "/conf", "isReadOnly", true))));

        manager.startPod(instance, List.of(container), image -> image);

        verify(spec).withNamedVolume(manager.volumeName(instance, "shm"), "/shm", true);
        verify(spec).withNamedVolume(manager.volumeName(instance, "disk"), "/disk", true);
        verify(spec).withNamedVolume(manager.volumeName(instance, "conf"), "/conf", true);
    }

    @Test
    void volumesWhoseNamesDifferOnlyInCaseOrPunctuationGetTheirOwnDockerVolume() {
        StoredContainerInstance instance = instance(List.of());
        String upper = manager.volumeName(instance, "Cache");
        String lower = manager.volumeName(instance, "cache");
        String punctuated = manager.volumeName(instance, "cache!");
        assertNotEquals(upper, lower);
        assertNotEquals(lower, punctuated);
        assertEquals(upper, manager.volumeName(instance, "Cache"));
    }

    @Test
    void basicCredentialsAreUsedForTheirRegistry() {
        StoredContainerInstance instance = instance(List.of());
        instance.getPullCredentials().add(new StoredContainerInstance.PullCredential("iad.ocir.io", "u", "p"));
        StoredContainer container = container(Map.of());
        container.setImageUrl("iad.ocir.io/ns/app:1");

        manager.startPod(instance, List.of(container), image -> image);

        verify(images).ensureImageExists("iad.ocir.io/ns/app:1", "u", "p");
    }

    @Test
    void onlyThePauseContainerSetsTheEmbeddedDnsTheOthersJoin() {
        ContainerBuilder.Builder pauseSpec = mock(ContainerBuilder.Builder.class, invocation ->
                invocation.getMethod().getReturnType() == ContainerBuilder.Builder.class ? invocation.getMock() : null);
        when(pauseSpec.build()).thenReturn(new ContainerSpec("registry.k8s.io/pause:3.10"));
        when(builder.newContainer("registry.k8s.io/pause:3.10")).thenReturn(pauseSpec);

        manager.startPod(instance(List.of()), List.of(container(Map.of())), image -> image);

        verify(pauseSpec).withEmbeddedDns();
        verify(spec, never()).withEmbeddedDns();
        verify(spec).withNetworkMode("container:" + manager.podName(instance(List.of())));
    }

    @Test
    void aFailedStartRemovesWhatWasStarted() {
        StoredContainerInstance instance = instance(List.of());
        StoredContainer container = container(Map.of());
        when(lifecycle.create(any())).thenThrow(new IllegalStateException("no such image"));

        assertThrows(IllegalStateException.class, () -> manager.startPod(instance, List.of(container), i -> i));

        verify(lifecycle).forceRemove(manager.podName(instance));
        verify(lifecycle).forceRemove(manager.containerName(container));
    }

    @Test
    void aFailedRebuildOfAStoppedPodKeepsItsVolumes() {
        StoredContainerInstance instance = instance(List.of(Map.of("name", "data", "volumeType", "EMPTYDIR")));
        StoredContainer container = container(Map.of());
        when(lifecycle.findByName(anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("pull failed")).when(images).ensureImageExists(anyString());

        assertThrows(IllegalStateException.class, () -> manager.restartPod(instance, List.of(container), i -> i));

        verify(lifecycle).forceRemove(manager.podName(instance));
        verify(lifecycle, never()).deleteVolume(anyString());
    }

    @Test
    void imagesArePulledThroughTheConfiguredMirror() {
        when(builder.resolveImage("busybox:1.36")).thenReturn("mirror.example/busybox:1.36");
        StoredContainer container = container(Map.of());
        container.setImageUrl("busybox:1.36");

        manager.startPod(instance(List.of()), List.of(container), image -> image);

        verify(images).ensureImageExists("mirror.example/busybox:1.36");
        verify(images, never()).ensureImageExists("busybox:1.36");
    }

    @Test
    void removeTriesEverythingAndThrowsWhatDockerCouldNotRemove() {
        StoredContainerInstance instance = instance(List.of(Map.of("name", "shm", "volumeType", "EMPTYDIR",
                "backingStore", "MEMORY")));
        StoredContainer container = container(Map.of());
        String name = manager.containerName(container);
        doThrow(new InternalServerErrorException("device busy")).when(lifecycle).forceRemove(name);

        InternalServerErrorException e = assertThrows(InternalServerErrorException.class,
                () -> manager.removePod(instance, List.of(container)));

        assertTrue(e.getMessage().contains("device busy"), e.getMessage());
        verify(lifecycle).forceRemove(manager.podName(instance));
        verify(lifecycle).deleteVolume(manager.volumeName(instance, "shm"));
    }

    @Test
    void stopToleratesGoneAndStoppedContainersButNotOtherFailures() {
        StoredContainerInstance instance = instance(List.of());
        StoredContainer container = container(Map.of());
        String name = manager.containerName(container);
        String pause = manager.podName(instance);
        doThrow(new NotModifiedException("already stopped")).when(lifecycle).stop(eq(name), anyInt());
        doThrow(new NotFoundException("gone")).when(lifecycle).stop(eq(pause), anyInt());
        manager.stopPod(instance, List.of(container));

        doThrow(new InternalServerErrorException("daemon error")).when(lifecycle).stop(eq(pause), anyInt());
        assertThrows(InternalServerErrorException.class, () -> manager.stopPod(instance, List.of(container)));
    }

    @Test
    void aStartOfAStoppedPodThatDockerRejectsFails() {
        StoredContainerInstance instance = instance(List.of());
        StoredContainer container = container(Map.of());
        String name = manager.containerName(container);
        when(lifecycle.findByName(anyString())).thenReturn(Optional.of(new Container()));
        doThrow(new InternalServerErrorException("cannot start")).when(lifecycle).start(name);

        assertThrows(InternalServerErrorException.class, () -> manager.restartPod(instance, List.of(container), i -> i));
    }

    @Test
    void registryHostIsTheFirstPathSegmentWhenItLooksLikeAHost() {
        assertEquals("iad.ocir.io", ContainerInstanceManager.registryHost("iad.ocir.io/ns/app:1"));
        assertEquals("localhost:5200", ContainerInstanceManager.registryHost("localhost:5200/iad/ns/app"));
        assertEquals("docker.io", ContainerInstanceManager.registryHost("busybox:1.36"));
        assertEquals("docker.io", ContainerInstanceManager.registryHost("library/busybox"));
    }

    @Test
    void longPathsUseTheUstarPrefix() {
        String path = "/" + "d".repeat(120) + "/file.txt";
        Map<String, byte[]> files = untar(ContainerInstanceManager.tar(Map.of(path, new byte[]{1, 2, 3})));
        assertArrayEquals(new byte[]{1, 2, 3}, files.get(path.substring(1)));
    }

    @Test
    void namesAreMeasuredInUtf8Bytes() {
        String fits = "/etc/" + "é".repeat(45) + ".conf";
        Map<String, byte[]> files = untar(ContainerInstanceManager.tar(Map.of(fits, new byte[]{7})));
        assertArrayEquals(new byte[]{7}, files.get(fits.substring(1)));

        String tooLong = "/etc/" + "é".repeat(60);
        assertThrows(IllegalArgumentException.class,
                () -> ContainerInstanceManager.tar(Map.of(tooLong, new byte[]{7})));
    }

    /** Reads back what {@link ContainerInstanceManager#tar} writes: name (with prefix) to content. */
    private static Map<String, byte[]> untar(byte[] tar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int offset = 0;
        while (offset + 512 <= tar.length && tar[offset] != 0) {
            String name = field(tar, offset, 100);
            String prefix = field(tar, offset + 345, 155);
            int size = Integer.parseInt(field(tar, offset + 124, 12).trim(), 8);
            long checksum = 0;
            for (int i = 0; i < 512; i++) {
                checksum += (i >= 148 && i < 156) ? ' ' : tar[offset + i] & 0xFF;
            }
            assertEquals(checksum, Long.parseLong(field(tar, offset + 148, 6), 8), "header checksum");
            byte[] data = new byte[size];
            System.arraycopy(tar, offset + 512, data, 0, size);
            files.put(prefix.isEmpty() ? name : prefix + "/" + name, data);
            offset += 512 + ((size + 511) / 512) * 512;
        }
        return files;
    }

    private static String field(byte[] tar, int offset, int length) {
        int end = offset;
        while (end < offset + length && tar[end] != 0) {
            end++;
        }
        return new String(tar, offset, end - offset, StandardCharsets.UTF_8);
    }

    @Test
    void stopStopsTheContainersBeforeThePauseContainerWithTheGracefulShutdownTimeout() {
        StoredContainerInstance instance = instance(List.of());
        instance.getAttributes().put("gracefulShutdownTimeoutInSeconds", 30L);
        StoredContainer container = container(Map.of());
        manager.stopPod(instance, List.of(container));
        ArgumentCaptor<String> stopped = ArgumentCaptor.forClass(String.class);
        verify(lifecycle, times(2)).stop(stopped.capture(), eq(30));
        assertEquals(List.of(manager.containerName(container), manager.podName(instance)), stopped.getAllValues());

        manager.restartContainer(instance, container);
        verify(lifecycle).restart(manager.containerName(container), 30);
    }
}
