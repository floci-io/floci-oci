package io.floci.oci.core.common.docker;

import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerStorageHelperTest {

    @Test
    void namesCarryTheOciPrefixWithoutNamespace() {
        assertEquals("floci-oci-fnserver", ContainerStorageHelper.dockerName(config(""), "fnserver"));
        assertEquals("floci-oci-fnserver-id1", ContainerStorageHelper.resourceName("fnserver", null, "id1"));
        assertEquals("floci-oci-fnserver-vol1", ContainerStorageHelper.resourceName(config(""), "fnserver", "vol1", "id1"));
    }

    @Test
    void namespaceLandsBetweenCloudAndServiceTokens() {
        EmulatorConfig config = config(" run/one ");

        assertEquals("floci-oci-run-one-fnserver", ContainerStorageHelper.dockerName(config, "fnserver"));
        assertEquals("floci-oci-run-one-fnserver-id1", ContainerStorageHelper.resourceName(config, "fnserver", null, "id1"));
        assertEquals("floci-oci-run-one-fnserver-vol1", ContainerStorageHelper.resourceName(config, "fnserver", "vol1", "id1"));
    }

    @Test
    void alreadyPrefixedNamesAreNormalized() {
        assertEquals("floci-oci-fnserver", ContainerStorageHelper.dockerName(config(""), "floci-oci-fnserver"));
        assertEquals("floci-oci-fnserver", ContainerStorageHelper.dockerName(config(""), "floci-fnserver"));
        assertEquals("floci-oci-run-one-fnserver", ContainerStorageHelper.dockerName(config("run-one"), "floci-oci-fnserver"));
        assertEquals("floci-oci-run-one-fnserver", ContainerStorageHelper.dockerName(config("run-one"), "floci-fnserver"));
    }

    @Test
    void defaultLabelsIdentifyThisEmulator() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci"),
                ContainerStorageHelper.defaultLabels(config("")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci", "floci_namespace", "run-one"),
                ContainerStorageHelper.defaultLabels(config(" run/one ")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci"),
                ContainerStorageHelper.defaultLabels(null));
    }

    @Test
    void hostResourcePathsIncludeNamespaceWhenConfigured() {
        EmulatorConfig config = config("run-one");

        assertEquals(Path.of("/tmp/floci/run-one/fnserver/id1"), ContainerStorageHelper.hostResourcePath(config, "fnserver", "id1"));
    }

    @Test
    void unsafeNamespaceSegmentsAreIgnored() {
        EmulatorConfig config = config("..");

        assertEquals(Path.of("/tmp/floci/fnserver/id1"), ContainerStorageHelper.hostResourcePath(config, "fnserver", "id1"));
        assertEquals("floci-oci-fnserver-id1", ContainerStorageHelper.resourceName(config, "fnserver", null, "id1"));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-oci"),
                ContainerStorageHelper.defaultLabels(config));
    }

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        when(storage.hostPersistentPath()).thenReturn("/tmp/floci");
        return config;
    }
}
