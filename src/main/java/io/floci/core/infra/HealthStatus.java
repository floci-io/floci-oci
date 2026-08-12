package io.floci.core.infra;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Model representing health check status probes.
 */
public class HealthStatus {
    private final String status;
    private final Map<String, Object> details;
    private final Instant timestamp;

    public HealthStatus(String status, Map<String, Object> details) {
        this.status = status;
        this.details = details != null ? new HashMap<>(details) : new HashMap<>();
        this.timestamp = Instant.now();
    }

    public static HealthStatus up(Map<String, Object> details) {
        return new HealthStatus("UP", details);
    }

    public static HealthStatus down(Map<String, Object> details) {
        return new HealthStatus("DOWN", details);
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
