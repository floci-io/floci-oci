package org.floci.core.server.handler;

import org.floci.core.server.EmulatorRequest;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rule for matching request paths, HTTP methods, and headers to a target RequestHandler.
 */
public class RoutingRule {
    private final String httpMethod;
    private final String pathPrefix;
    private final Pattern pathPattern;

    public RoutingRule(String httpMethod, String pathPrefix) {
        this.httpMethod = httpMethod != null ? httpMethod.toUpperCase() : "*";
        this.pathPrefix = pathPrefix != null ? pathPrefix : "/";
        this.pathPattern = Pattern.compile("^" + Pattern.quote(this.pathPrefix) + "(.*)$");
    }

    public boolean matchesPath(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        return requestPath.startsWith(pathPrefix) || pathPattern.matcher(requestPath).matches();
    }

    public boolean matchesMethod(String requestMethod) {
        if ("*".equals(this.httpMethod)) {
            return true;
        }
        return this.httpMethod.equalsIgnoreCase(requestMethod);
    }

    public boolean matches(EmulatorRequest request) {
        if (request == null) {
            return false;
        }
        return matchesPath(request.getPath()) && matchesMethod(request.getMethod());
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoutingRule that = (RoutingRule) o;
        return Objects.equals(httpMethod, that.httpMethod) && Objects.equals(pathPrefix, that.pathPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(httpMethod, pathPrefix);
    }
}
