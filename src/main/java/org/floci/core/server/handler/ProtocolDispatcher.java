package org.floci.core.server.handler;

import org.floci.core.server.EmulatorRequest;
import org.floci.core.server.EmulatorResponse;
import org.floci.core.server.filter.FilterChain;
import org.floci.core.server.filter.FilterResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol dispatcher matching incoming requests against registered routing rules and running filter chains.
 */
public class ProtocolDispatcher {
    private final Map<RoutingRule, RequestHandler> routes = new ConcurrentHashMap<>();
    private final FilterChain filterChain;

    public ProtocolDispatcher(FilterChain filterChain) {
        this.filterChain = filterChain != null ? filterChain : new FilterChain();
    }

    public void registerHandler(RoutingRule rule, RequestHandler handler) {
        if (rule != null && handler != null) {
            routes.put(rule, handler);
        }
    }

    public void unregisterHandler(RoutingRule rule) {
        routes.remove(rule);
    }

    public EmulatorResponse dispatch(EmulatorRequest request) {
        if (request == null) {
            return EmulatorResponse.of(400, "Bad Request: Null request payload");
        }

        // 1. Run filter chain
        FilterResult filterResult = filterChain.execute(request);
        if (!filterResult.isContinueChain()) {
            return filterResult.getShortCircuitResponse();
        }

        // 2. Find matching handler
        boolean pathMatched = false;
        for (Map.Entry<RoutingRule, RequestHandler> entry : routes.entrySet()) {
            RoutingRule rule = entry.getKey();
            if (rule.matchesPath(request.getPath())) {
                pathMatched = true;
                if (rule.matchesMethod(request.getMethod())) {
                    return entry.getValue().handle(request);
                }
            }
        }

        // 3. Handle fallback responses
        if (pathMatched) {
            return EmulatorResponse.of(405, "Method Not Allowed");
        } else {
            return EmulatorResponse.of(404, "Not Found");
        }
    }
}
