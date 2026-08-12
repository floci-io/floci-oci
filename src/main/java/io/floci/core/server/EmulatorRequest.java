package io.floci.core.server;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Standardized request representation across cloud provider emulators.
 */
public class EmulatorRequest {
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private final byte[] body;
    private final RequestContext context;

    public EmulatorRequest(String method, String path, Map<String, String> headers, Map<String, String> queryParameters, byte[] body, RequestContext context) {
        this.method = method != null ? method.toUpperCase() : "GET";
        this.path = path != null ? path : "/";
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            this.headers.putAll(headers);
        }
        this.queryParameters = queryParameters != null ? new HashMap<>(queryParameters) : new HashMap<>();
        this.body = body != null ? body : new byte[0];
        this.context = context != null ? context : new RequestContext("HTTP/1.1");
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public String getQueryParameter(String name) {
        return queryParameters.get(name);
    }

    public Map<String, String> getQueryParameters() {
        return Collections.unmodifiableMap(queryParameters);
    }

    public byte[] getBody() {
        return body.clone();
    }

    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public RequestContext getContext() {
        return context;
    }
}
