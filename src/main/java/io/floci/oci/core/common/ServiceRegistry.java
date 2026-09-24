package io.floci.oci.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of emulated OCI services. Each service registers its own
 * {@link ServiceDescriptor} at startup; nothing here is keyed by service name.
 */
@ApplicationScoped
public class ServiceRegistry {

    private static final Logger LOG = Logger.getLogger(ServiceRegistry.class);

    private final Map<String, ServiceDescriptor> byName = new ConcurrentHashMap<>();
    private final Map<Class<?>, ServiceDescriptor> byResourceClass = new ConcurrentHashMap<>();

    public void register(ServiceDescriptor descriptor) {
        ServiceDescriptor previous = byName.putIfAbsent(descriptor.name(), descriptor);
        if (previous != null) {
            throw new IllegalStateException("Duplicate service registration: " + descriptor.name());
        }
        for (Class<?> resourceClass : descriptor.resourceClasses()) {
            byResourceClass.put(resourceClass, descriptor);
        }
    }

    public Optional<ServiceDescriptor> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Optional<ServiceDescriptor> byResourceClass(Class<?> resourceClass) {
        return Optional.ofNullable(byResourceClass.get(resourceClass));
    }

    public List<ServiceDescriptor> all() {
        return List.copyOf(byName.values());
    }

    public boolean isServiceEnabled(String name) {
        return byName(name).map(ServiceDescriptor::enabled).orElse(true);
    }

    public List<String> getEnabledServices() {
        List<String> enabled = new ArrayList<>();
        for (ServiceDescriptor descriptor : byName.values()) {
            if (descriptor.enabled()) {
                enabled.add(descriptor.name());
            }
        }
        enabled.sort(String::compareTo);
        return enabled;
    }

    /**
     * Returns all known services with their status: "running" if enabled, "available" if not.
     */
    public Map<String, String> getServices() {
        Map<String, String> services = new LinkedHashMap<>();
        byName.keySet().stream().sorted().forEach(name ->
                services.put(name, byName.get(name).enabled() ? "running" : "available"));
        return services;
    }

    public void logEnabledServices() {
        LOG.infov("Enabled services: {0}", getEnabledServices());
    }
}
