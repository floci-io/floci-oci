package org.floci.core.infra;

/**
 * Interface for probing emulator runtime health status.
 */
public interface HealthCheckHandler {
    HealthStatus getHealthStatus();
}
