package io.floci.oci.services.objectstorage;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.common.OciException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@link ObjectStorageService#requireNamespace} to every {@code /n/{namespaceName}/…}
 * request in one place, after {@code SignatureAuthFilter} has set the calling tenancy.
 * Pre-authenticated requests ({@code /p/…}) carry no signature and are not checked here.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 20)
public class NamespaceFilter implements ContainerRequestFilter {

    private static final Pattern NAMESPACE_PATH = Pattern.compile("^/?n/([^/]+)(/.*)?$");

    /** Injected lazily: this filter is instantiated during Quarkus static init. */
    @Inject
    // Qualified: jakarta.ws.rs.ext.Provider is imported for the @Provider annotation.
    jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    jakarta.inject.Provider<ObjectStorageService> serviceProvider;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!configProvider.get().services().objectstorage().enabled()) {
            return;
        }
        Matcher matcher = NAMESPACE_PATH.matcher(ctx.getUriInfo().getPath());
        if (!matcher.matches()) {
            return;
        }
        try {
            serviceProvider.get().requireNamespace(matcher.group(1));
        } catch (OciException e) {
            ctx.abortWith(Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new OciErrorResponse(e.getCode(), e.getMessage()))
                    .build());
        }
    }
}
