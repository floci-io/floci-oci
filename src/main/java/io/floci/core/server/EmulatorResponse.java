package io.floci.core.server;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Standardized response representation across cloud provider emulators.
 */
public class EmulatorResponse {
    private final int statusCode;
    private final Map<String, String> headers;
    private final byte[] body;

    public EmulatorResponse(int statusCode, Map<String, String> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            this.headers.putAll(headers);
        }
        this.body = body != null ? body : new byte[0];
    }

    public static EmulatorResponse ok(String content) {
        return new EmulatorResponse(200, Map.of("Content-Type", "text/plain"), content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public static EmulatorResponse json(int statusCode, String jsonContent) {
        return new EmulatorResponse(statusCode, Map.of("Content-Type", "application/json"), jsonContent != null ? jsonContent.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public static EmulatorResponse of(int statusCode, String body) {
        return new EmulatorResponse(statusCode, Map.of("Content-Type", "text/plain"), body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public byte[] getBody() {
        return body.clone();
    }

    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
