package io.floci.oci.services.nosql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.nosql.model.StoredNosqlRow;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes rows whose time to live has passed, every
 * {@code floci-oci.services.nosql.ttl-sweep-interval-seconds}. Reads already hide an expired
 * row, so the sweep only reclaims storage.
 */
@ApplicationScoped
public class NosqlTtlSweeper {

    private static final Logger LOG = Logger.getLogger(NosqlTtlSweeper.class);

    private final NosqlService service;
    private final EmulatorConfig config;
    private final RequestContextPropagation contextPropagation;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    @Inject
    public NosqlTtlSweeper(NosqlService service, EmulatorConfig config,
                           RequestContextPropagation contextPropagation, Clock clock) {
        this.service = service;
        this.config = config;
        this.contextPropagation = contextPropagation;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.services().nosql().enabled()) {
            return;
        }
        long seconds = Math.max(1, config.services().nosql().ttlSweepIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("nosql-ttl").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::sweep, seconds, seconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One pass over every tenancy and region that holds rows. */
    void sweep() {
        long now = clock.millis();
        try {
            StorageBackend<String, StoredNosqlRow> rows = service.rowStore();
            if (!(rows instanceof TenancyAwareStorageBackend<StoredNosqlRow> tenancyAware)) {
                service.purgeExpired(now);
                return;
            }
            for (String partition : tenancyAware.tenancies()) {
                String region = TenancyAwareStorageBackend.regionOf(partition).orElse(config.defaultRegion());
                contextPropagation.runAs(TenancyAwareStorageBackend.tenancyOf(partition), region,
                        () -> service.purgeExpired(now));
            }
        } catch (RuntimeException e) {
            LOG.warnv("NoSQL TTL sweep failed: {0}", e.getMessage());
        }
    }
}
