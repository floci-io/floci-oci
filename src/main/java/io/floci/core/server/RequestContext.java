package io.floci.core.server;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates contextual request metadata including execution ID, tenant/account context, and custom attributes.
 */
public class RequestContext {
    private final String requestId;
    private final String protocol;
    private final Instant startTime;
    private final Map<String, Object> attributes;

    public RequestContext(String protocol) {
        this.requestId = UUID.randomUUID().toString();
        this.protocol = protocol != null ? protocol : "HTTP/1.1";
        this.startTime = Instant.now();
        this.attributes = new HashMap<>();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getProtocol() {
        return protocol;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setAttribute(String key, Object value) {
        if (key != null) {
            attributes.put(key, value);
        }
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> clazz) {
        Object val = attributes.get(key);
        return clazz.isInstance(val) ? (T) val : null;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
