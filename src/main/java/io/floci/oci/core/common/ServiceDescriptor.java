package io.floci.oci.core.common;

import java.util.Set;

/**
 * Self-description of one emulated OCI service, registered by the service itself in an
 * {@code @Observes StartupEvent} method. Central consumers ({@link ServiceRegistry},
 * {@code ServiceEnabledFilter}, the storage layer) resolve service metadata through these
 * descriptors — adding a service must never require a service-keyed switch elsewhere.
 */
public record ServiceDescriptor(
        String name,
        boolean enabled,
        String storageKey,
        Set<Class<?>> resourceClasses
) {

    public boolean supportsStorage() {
        return storageKey != null;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private boolean enabled = true;
        private String storageKey;
        private Set<Class<?>> resourceClasses = Set.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder storageKey(String storageKey) {
            this.storageKey = storageKey;
            return this;
        }

        public Builder resourceClasses(Class<?>... classes) {
            this.resourceClasses = Set.of(classes);
            return this;
        }

        public ServiceDescriptor build() {
            return new ServiceDescriptor(name, enabled, storageKey, resourceClasses);
        }
    }
}
