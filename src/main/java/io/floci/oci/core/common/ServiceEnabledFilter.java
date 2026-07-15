package io.floci.oci.core.common;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/**
 * Rejects requests to disabled services with 503. The owning service is resolved through
 * the matched JAX-RS resource class via {@link ServiceRegistry#byResourceClass(Class)};
 * controllers not registered by any service pass through untouched.
 */
@Provider
public class ServiceEnabledFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    @Inject
    ServiceRegistry serviceRegistry;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return;
        }
        Optional<ServiceDescriptor> descriptor =
                serviceRegistry.byResourceClass(resourceInfo.getResourceClass());
        if (descriptor.isPresent() && !descriptor.get().enabled()) {
            requestContext.abortWith(Response.status(503)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new OciErrorResponse("ServiceUnavailable",
                            "Service " + descriptor.get().name() + " is not enabled."))
                    .build());
        }
    }
}
