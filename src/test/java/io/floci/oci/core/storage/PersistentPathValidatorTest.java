package io.floci.oci.core.storage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ServiceConfigAccess;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentPathValidatorTest {

    @TempDir
    Path tempDir;

    @Mock private ServiceRegistry registry;
    @Mock private ServiceConfigAccess serviceConfigAccess;
    @Mock private EmulatorConfig config;
    @Mock private EmulatorConfig.StorageConfig storageConfig;

    private PersistentPathValidator validator() {
        lenient().when(config.storage()).thenReturn(storageConfig);
        return new PersistentPathValidator(registry, serviceConfigAccess, config);
    }

    private static ServiceDescriptor descriptor(String name, boolean enabled, String storageKey) {
        return ServiceDescriptor.builder(name).enabled(enabled).storageKey(storageKey).build();
    }

    private void storageMode(String storageKey, String mode) {
        lenient().when(serviceConfigAccess.storageMode(storageKey)).thenReturn(mode);
    }

    @Test
    void memoryOnlyBootDoesNotTouchTheFilesystem() {
        when(registry.all()).thenReturn(List.of(
                descriptor("objectstorage", true, "objectstorage"),
                descriptor("identity", true, "identity")));
        storageMode("objectstorage", "memory");
        storageMode("identity", "memory");

        PersistentPathValidator validator = validator();
        validator.validateAtBoot();
    }

    @Test
    void nonMemoryModeCreatesPathAndLeavesNoProbeBehind() throws IOException {
        Path root = tempDir.resolve("data");
        when(registry.all()).thenReturn(List.of(descriptor("identity", true, "identity")));
        storageMode("identity", "hybrid");
        when(storageConfig.persistentPath()).thenReturn(root.toString());

        validator().validateAtBoot();

        assertTrue(Files.isDirectory(root));
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count(), "write probe must not be left behind");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void unwritablePathFailsWithActionableMessage() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores directory write permissions");
        Path readOnlyParent = tempDir.resolve("ro");
        Files.createDirectories(readOnlyParent);
        assertTrue(readOnlyParent.toFile().setWritable(false));
        try {
            Path root = readOnlyParent.resolve("data");
            when(registry.all()).thenReturn(List.of(descriptor("objectstorage", true, "objectstorage")));
            storageMode("objectstorage", "hybrid");
            when(storageConfig.persistentPath()).thenReturn(root.toString());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> validator().validateAtBoot());
            assertTrue(e.getMessage().contains(root.toAbsolutePath().toString()));
            assertTrue(e.getMessage().contains("FLOCI_OCI_STORAGE_PERSISTENT_PATH"));
            assertTrue(e.getMessage().contains("objectstorage=hybrid"));
        } finally {
            assertTrue(readOnlyParent.toFile().setWritable(true));
        }
    }

    @Test
    void persistentPathThatIsAFileFailsClearly() throws IOException {
        Path root = tempDir.resolve("data");
        Files.writeString(root, "not a directory");
        when(registry.all()).thenReturn(List.of(descriptor("objectstorage", true, "objectstorage")));
        storageMode("objectstorage", "persistent");
        when(storageConfig.persistentPath()).thenReturn(root.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator().validateAtBoot());
        assertTrue(e.getMessage().contains(root.toAbsolutePath().toString()));
    }

    @Test
    void disabledServicesDoNotTriggerValidation() {
        when(registry.all()).thenReturn(List.of(descriptor("objectstorage", false, "objectstorage")));

        validator().validateAtBoot();
    }
}
