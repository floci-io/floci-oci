package io.floci.oci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.ServiceConfigAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates {@link TenancyAwareStorageBackend} instances based on configuration.
 * Every backend is wrapped in an tenancy-aware decorator so resources are automatically
 * namespaced by the tenancy OCID of the calling credential.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;
    private final OciContext ociContext;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess,
                          OciContext ociContext) {
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
        this.ociContext = ociContext;
    }

    /**
     * Create a regional storage backend for the given service. Keys are prefixed with
     * {@code <tenancy>:<region>} of the current request, so resources in one region are
     * invisible from another, as on real OCI. Entries persisted before region partitioning
     * are moved into the default region on creation.
     *
     * @param serviceName   the service name (objectstorage, queue, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                TypeReference<Map<String, V>> typeReference) {
        return create(serviceName, fileName, typeReference, true);
    }

    /**
     * Create a global storage backend, partitioned by tenancy only: for resources that exist
     * in every region of the realm, such as Identity's compartments, users and policies.
     */
    public <V> StorageBackend<String, V> createGlobal(String serviceName, String fileName,
                                                      TypeReference<Map<String, V>> typeReference) {
        return create(serviceName, fileName, typeReference, false);
    }

    private synchronized <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                              TypeReference<Map<String, V>> typeReference,
                                                              boolean regional) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        // Reuse an existing backend for the same file. Handing out a second backend bound to the
        // same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
        // after the active instance and clobbers persisted state (issue #1921).
        StorageBackend<?, ?> existing = backendsByPath.get(filePath);
        if (existing != null) {
            LOG.debugv("Reusing existing {0} storage for service {1} (file: {2})", mode, serviceName, filePath);
            @SuppressWarnings("unchecked")
            StorageBackend<String, V> typed = (StorageBackend<String, V>) existing;
            return typed;
        }

        LOG.debugv("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(filePath, typeReference);
            case "hybrid" -> {
                HybridStorage<String, V> hybrid = new HybridStorage<>(filePath, typeReference, flushInterval);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                WalStorage<String, V> wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };

        inner.load();

        TenancyAwareStorageBackend<V> backend;
        if (regional) {
            backend = new TenancyAwareStorageBackend<>(inner,
                    () -> TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(),
                            ociContext.region()));
            int moved = backend.migrateToRegion(config.defaultRegion());
            if (moved > 0) {
                LOG.infov("Moved {0} {1} entries into region {2}", moved, fileName,
                        config.defaultRegion());
            }
        } else {
            backend = new TenancyAwareStorageBackend<>(inner, ociContext::tenancyId);
        }
        allBackends.add(backend);
        backendsByPath.put(filePath, backend);
        return backend;
    }

    /** Load all storage backends from disk. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /** Shutdown all managed backends (stop schedulers, close connections). */
    public synchronized void shutdownAll() {
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        flushAll();
    }

    private String resolveMode(String serviceName) {
        return serviceConfigAccess.storageMode(serviceName);
    }

    private long resolveFlushInterval(String serviceName) {
        return serviceConfigAccess.storageFlushInterval(serviceName);
    }
}
