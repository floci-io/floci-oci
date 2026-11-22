package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.objectstorage.model.StoredLifecyclePolicy;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs every bucket's object lifecycle policy on a fixed interval
 * ({@code floci-oci.services.objectstorage.lifecycle-sweep-interval-seconds}, a minute by default). Real OCI
 * "runs the lifecycle policy once a day" (usinglifecyclepolicies.htm); the emulator runs it far more often so a
 * rule takes effect soon after an object becomes due, which is the same contract with less waiting.
 */
@ApplicationScoped
public class ObjectLifecycleSweeper {

    private static final Logger LOG = Logger.getLogger(ObjectLifecycleSweeper.class);

    private final ObjectStorageService service;
    private final EmulatorConfig config;
    private final RequestContextPropagation contextPropagation;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    @Inject
    public ObjectLifecycleSweeper(ObjectStorageService service, EmulatorConfig config,
                                  RequestContextPropagation contextPropagation, Clock clock) {
        this.service = service;
        this.config = config;
        this.contextPropagation = contextPropagation;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.services().objectstorage().enabled()) {
            return;
        }
        long seconds = Math.max(1, config.services().objectstorage().lifecycleSweepIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("objectstorage-lifecycle").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::sweep, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void sweep() {
        sweep(clock.instant());
    }

    /** One pass over every tenancy and region that has a lifecycle policy. */
    void sweep(Instant now) {
        try {
            StorageBackend<String, StoredLifecyclePolicy> policies = service.lifecyclePolicyStore();
            if (!(policies instanceof TenancyAwareStorageBackend<StoredLifecyclePolicy> tenancyAware)) {
                service.applyLifecycle(now);
                return;
            }
            for (String partition : tenancyAware.tenancies()) {
                String region = TenancyAwareStorageBackend.regionOf(partition).orElse(config.defaultRegion());
                contextPropagation.runAs(TenancyAwareStorageBackend.tenancyOf(partition), region,
                        () -> service.applyLifecycle(now));
            }
        } catch (RuntimeException e) {
            LOG.warnv("Object lifecycle sweep failed: {0}", e.getMessage());
        }
    }
}
