package org.floci.core.infra;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured diagnostic event for system auditing and telemetry.
 */
public class DiagnosticEvent {
    private final String eventId;
    private final String eventType;
    private final String message;
    private final Instant timestamp;
    private final Map<String, String> tags;

    public DiagnosticEvent(String eventId, String eventType, String message, Instant timestamp, Map<String, String> tags) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.message = message != null ? message : "";
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.tags = tags != null ? Collections.unmodifiableMap(new HashMap<>(tags)) : Collections.emptyMap();
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
