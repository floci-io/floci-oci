package io.floci.oci.core.common;

import java.util.Map;

/**
 * Receives the events other services emit (Object Storage bucket and object changes), so the
 * Events service can match them against rules without those services depending on it. Emitting
 * never fails the request that caused the event: an implementation logs and moves on.
 */
public interface OciEvents {

    void emit(Event event);

    /**
     * One state change, before it is wrapped in the CloudEvents envelope. {@code tags} carries
     * the resource's freeform and defined tags; Oracle's bucket example names the freeform field
     * {@code freeFormTags}, so the envelope builder decides the spelling.
     */
    record Event(String eventType, String source, String compartmentId, String resourceName,
                 String resourceId, Map<String, String> freeformTags,
                 Map<String, Map<String, Object>> definedTags, Map<String, Object> additionalDetails) {
    }
}
