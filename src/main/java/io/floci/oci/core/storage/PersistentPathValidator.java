package io.floci.oci.core.storage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ServiceConfigAccess;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates at boot that the persistent storage path is usable whenever any enabled service
 * runs with a non-memory storage mode. Without this check the failure surfaces lazily and
 * confusingly: services silently lose data at flush time or answer with opaque 500s.
 */
@ApplicationScoped
public class PersistentPathValidator {

    private final ServiceRegistry registry;
    private final ServiceConfigAccess serviceConfigAccess;
    private final EmulatorConfig config;

    @Inject
    public PersistentPathValidator(ServiceRegistry registry, ServiceConfigAccess serviceConfigAccess,
            EmulatorConfig config) {
        this.registry = registry;
        this.serviceConfigAccess = serviceConfigAccess;
        this.config = config;
    }

    /**
     * @throws IllegalStateException when persistence is enabled but the path is unusable
     */
    public void validateAtBoot() {
        List<ServiceDescriptor> persistent = registry.all().stream()
                .filter(d -> d.enabled() && d.supportsStorage()
                        && !"memory".equals(serviceConfigAccess.storageMode(d.storageKey())))
                .toList();
        if (persistent.isEmpty()) {
            return;
        }

        Path root = Path.of(config.storage().persistentPath());
        try {
            probeWritable(root);
        } catch (IOException | SecurityException e) {
            String services = persistent.stream()
                    .map(d -> d.storageKey() + "=" + serviceConfigAccess.storageMode(d.storageKey()))
                    .distinct()
                    .limit(8)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalStateException(
                    "Persistent storage path '" + root.toAbsolutePath()
                            + "' is not writable, but non-memory storage is enabled (" + services
                            + "). Fix the volume mount permissions (it may be read-only or root-owned),"
                            + " or point FLOCI_OCI_STORAGE_PERSISTENT_PATH at a writable directory.", e);
        }
    }

    static void probeWritable(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path probe = Files.createTempFile(dir, ".floci-write-probe", null);
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            // The write itself succeeded, so the path is writable; a failed cleanup
            // must not abort boot as a false "not writable".
            probe.toFile().deleteOnExit();
        }
    }
}
