package io.floci.oci.services.containerinstances;

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
        verify(lifecycle).ensureVolume(volume);
        verify(spec).withNamedVolume(volume, "/data", false);
        verify(lifecycle).startCreated(eq("created-id"), any());
        assertEquals(manager.containerName(container), container.getDockerName());
    }

    @Test
    void configFilesAreCopiedIntoTheContainerBeforeItStarts() {
        String data = Base64.getEncoder().encodeToString("port=8080\n".getBytes(StandardCharsets.UTF_8));
        StoredContainerInstance instance = instance(List.of(Map.of("name", "conf", "volumeType", "CONFIGFILE",
                "configs", List.of(Map.of("fileName", "app.conf", "data", data, "path", "nested")))));
        StoredContainer container = container(Map.of(
                "volumeMounts", List.of(Map.of("volumeName", "conf", "mountPath", "/etc/app"))));

        manager.startPod(instance, List.of(container), image -> image);

        assertEquals(1, copied.size());
        assertArrayEquals("port=8080\n".getBytes(StandardCharsets.UTF_8), copied.get(0).get("etc/app/nested/app.conf"));
        verify(lifecycle).copyToContainer(eq("created-id"), any(), eq("/"));
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
    void aFailedStartRemovesWhatWasStarted() {
        StoredContainerInstance instance = instance(List.of());
        StoredContainer container = container(Map.of());
        when(lifecycle.create(any())).thenThrow(new IllegalStateException("no such image"));

        assertThrows(IllegalStateException.class, () -> manager.startPod(instance, List.of(container), i -> i));

        verify(lifecycle, atLeastOnce()).removeIfExists(manager.podName(instance));
        verify(lifecycle, atLeastOnce()).removeIfExists(manager.containerName(container));
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
        return new String(tar, offset, end - offset, StandardCharsets.US_ASCII);
    }

    @Test
    void stopStopsTheContainersBeforeThePauseContainer() {
        StoredContainerInstance instance = instance(List.of());
        StoredContainer container = container(Map.of());
        manager.stopPod(instance, List.of(container));
        ArgumentCaptor<String> stopped = ArgumentCaptor.forClass(String.class);
        verify(lifecycle, times(2)).stop(stopped.capture(), eq(5));
        assertEquals(List.of(manager.containerName(container), manager.podName(instance)), stopped.getAllValues());
    }
}
