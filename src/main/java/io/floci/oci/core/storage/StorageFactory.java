package io.floci.oci.core.storage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.RequestContext;
import io.floci.oci.core.common.ServiceConfigAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess) {
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
    }

    /**
     * Create an tenancy-aware storage backend for the given service.
     * All keys are automatically prefixed with the current tenancy OCID derived from
     * the request signature. Async workers should use the {@code *ForTenancy} overloads
     * on {@link TenancyAwareStorageBackend} with the tenancy OCID stored on the resource model.
     *
     * @param serviceName   the service name (identity, objectstorage, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public synchronized <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference) {
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
                var hybrid = new HybridStorage<>(filePath, typeReference, flushInterval);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                var wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };

        inner.load();

        TenancyAwareStorageBackend<V> backend = new TenancyAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultTenancyId());
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
