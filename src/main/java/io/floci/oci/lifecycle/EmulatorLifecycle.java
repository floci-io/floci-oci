package io.floci.oci.lifecycle;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.PersistentPathValidator;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.lifecycle.inithook.InitializationHook;
import io.floci.oci.lifecycle.inithook.InitializationHooksRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * Emulator boot and shutdown orchestration. Services own their sidecars and pollers;
 * this class only drives the generic phases: init hooks, storage load/flush, banner.
 */
@ApplicationScoped
public class EmulatorLifecycle {

    private static final Logger LOG = Logger.getLogger(EmulatorLifecycle.class);
    private static final int HTTP_PORT = 4599;
    private static final int TLS_HTTP_BACKEND_PORT = 4510;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    Optional<String> appVersion = Optional.empty();

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final InitializationHooksRunner initializationHooksRunner;
    private final InitLifecycleState initLifecycleState;
    private final PersistentPathValidator persistentPathValidator;

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory,
                             ServiceRegistry serviceRegistry,
                             EmulatorConfig config,
                             InitializationHooksRunner initializationHooksRunner,
                             InitLifecycleState initLifecycleState,
                             PersistentPathValidator persistentPathValidator) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.initializationHooksRunner = initializationHooksRunner;
        this.initLifecycleState = initLifecycleState;
        this.persistentPathValidator = persistentPathValidator;
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.infof("=== OCI Local Emulator %s Starting ===", appVersion.orElse(""));
        LOG.infof("Endpoint:  http://0.0.0.0:%d", config.port());
        LOG.infof("Region:    %s  Tenancy: %s", config.defaultRegion(), config.defaultTenancyId());
        LOG.infov("Namespace: {0}", config.defaultNamespace());
        LOG.infov("Storage:   {0}  Path: {1}", config.storage().mode(), config.storage().persistentPath());
        LOG.infov("TLS:       {0}", config.tls().enabled()
                ? "enabled (HTTPS + HTTP dual mode)" : "disabled (HTTP only)");

        // BOOT hooks run before service initialization — scripts cannot use OCI APIs yet.
        try {
            initializationHooksRunner.run(InitializationHook.BOOT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Boot hook execution interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Boot hook execution failed", e);
        }
        initLifecycleState.markBootCompleted();

        persistentPathValidator.validateAtBoot();

        serviceRegistry.logEnabledServices();
        storageFactory.loadAll();

        boolean hasStart = initializationHooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = initializationHooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            initLifecycleState.markStartCompleted();
            initLifecycleState.markReadyCompleted();
            logReady();
        }
    }

    void onHttpStart(@ObservesAsync HttpServerStart event) {
        int expectedPort = config.tls().enabled() ? TLS_HTTP_BACKEND_PORT : HTTP_PORT;
        if (event.options().getPort() != expectedPort) {
            return;
        }
        boolean hasStart = initializationHooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = initializationHooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            return;
        }
        try {
            if (hasStart) {
                initializationHooksRunner.run(InitializationHook.START);
            }
            initLifecycleState.markStartCompleted();
            if (hasReady) {
                initializationHooksRunner.run(InitializationHook.READY);
            }
            initLifecycleState.markReadyCompleted();
            logReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Startup hook execution interrupted — shutting down", e);
        } catch (Exception e) {
            LOG.error("Startup hook execution failed — shutting down", e);
            Quarkus.asyncExit();
        }
    }

    /**
     * Testcontainers-style wait strategies poll the log for a line ending in "Ready." —
     * emit it alongside the banner so such tooling works out of the box.
     */
    private void logReady() {
        LOG.info("=== OCI Local Emulator Ready ===");
        LOG.info("Ready.");
    }

    void onPreShutdown(@Observes ShutdownDelayInitiatedEvent ignored) {
        LOG.info("=== OCI Local Emulator Shutting Down ===");
        initLifecycleState.markShutdownStarted();
        try {
            initializationHooksRunner.run(InitializationHook.STOP);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("STOP hook execution interrupted");
        } catch (Exception e) {
            LOG.warnv("STOP hooks failed: {0}", e.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        // Flush persisted state to disk FIRST, before any slow teardown (SIGKILL risk).
        storageFactory.flushAll();
        storageFactory.shutdownAll();
        LOG.info("=== OCI Local Emulator Stopped ===");
    }
}
