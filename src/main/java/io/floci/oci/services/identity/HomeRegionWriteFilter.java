package io.floci.oci.services.identity;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.tenancy.TenancyRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * IAM resources can only be changed in the tenancy's home region: every write
 * {@link IdentityController} serves is answered with 403 NotAllowed when it is sent to another
 * region's endpoint. Reads work in every region, and so does a write marked
 * {@link AnyRegionWrite}.
 *
 * <p>The check keys on the matched resource class, so a write added to the controller is
 * covered without a call of its own. Runs after {@code ServiceEnabledFilter}, which has already
 * answered for a disabled Identity service.
 */
@Provider
@Priority(Priorities.USER + 100)
public class HomeRegionWriteFilter implements ContainerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    @Context
    ResourceInfo resourceInfo;

    /** Injected lazily: this filter is instantiated during Quarkus static init. */
    @Inject
    // Qualified: jakarta.ws.rs.ext.Provider is imported for the @Provider annotation.
    jakarta.inject.Provider<TenancyRegistry> tenancies;

    @Inject
    jakarta.inject.Provider<OciContext> ociContext;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (resourceInfo == null || resourceInfo.getResourceClass() != IdentityController.class
                || READ_METHODS.contains(ctx.getMethod())
                || resourceInfo.getResourceMethod().isAnnotationPresent(AnyRegionWrite.class)) {
            return;
        }
        String homeRegion = tenancies.get().current().getHomeRegion();
        if (!ociContext.get().region().equals(homeRegion)) {
            ctx.abortWith(Response.status(403)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new OciErrorResponse("NotAllowed",
                            "This operation must be directed at the home region."))
                    .build());
        }
    }
}
