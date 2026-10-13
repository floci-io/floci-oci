package io.floci.oci.core.common;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Carries the caller's tenancy, user, region and base URL into work that runs after the request
 * has returned (a detached function invocation). Such work runs on another thread with no request
 * scope, where every tenancy- or region-partitioned read and write would otherwise fall back to
 * the configured defaults.
 */
@ApplicationScoped
public class RequestContextPropagation {

    private final RequestContext requestContext;

    @Inject
    public RequestContextPropagation(RequestContext requestContext) {
        this.requestContext = requestContext;
    }

    /**
     * Captures the current request's context, on the request thread, and returns a task that runs
     * {@code task} inside a fresh request context carrying the same values.
     */
    public Runnable withCurrentContext(Runnable task) {
        String tenancyId = requestContext.getTenancyId();
        String userId = requestContext.getUserId();
        String region = requestContext.getRegion();
        String baseUrl = requestContext.getBaseUrl();
        return () -> {
            ManagedContext context = Arc.container().requestContext();
            context.activate();
            try {
                RequestContext propagated = Arc.container().instance(RequestContext.class).get();
                propagated.setTenancyId(tenancyId);
                propagated.setUserId(userId);
                propagated.setRegion(region);
                propagated.setBaseUrl(baseUrl);
                task.run();
            } finally {
                context.terminate();
            }
        };
    }
}
