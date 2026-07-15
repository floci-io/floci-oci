package io.floci.oci.core.common;

import io.floci.oci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves per-service storage settings from configuration. Services without an explicit
 * {@code floci-oci.storage.services.<key>} entry inherit the global storage mode and the
 * default flush interval — no service-keyed switches.
 */
@ApplicationScoped
public class ServiceConfigAccess {

    private static final long DEFAULT_FLUSH_INTERVAL_MS = 5000L;

    private final EmulatorConfig config;

    @Inject
    public ServiceConfigAccess(EmulatorConfig config) {
        this.config = config;
    }

    public String storageMode(String storageKey) {
        return serviceStorage(storageKey)
                .flatMap(EmulatorConfig.ServiceStorageConfig::mode)
                .orElse(config.storage().mode());
    }

    public long storageFlushInterval(String storageKey) {
        return serviceStorage(storageKey)
                .map(EmulatorConfig.ServiceStorageConfig::flushIntervalMs)
                .orElse(DEFAULT_FLUSH_INTERVAL_MS);
    }

    private Optional<EmulatorConfig.ServiceStorageConfig> serviceStorage(String storageKey) {
        return Optional.ofNullable(config.storage().services().get(storageKey));
    }
}
