package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.common.RequestContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Pattern;

/**
 * A tenancy can only use the regions it is subscribed to: a request to any other region, or to
 * one outside the tenancy's realm, is answered with 401 NotAuthenticated. Oracle's error catalog
 * lists an unsubscribed region among that error's causes; the realm case documents no code. The
 * home region is always subscribed; CreateRegionSubscription adds another.
 *
 * <p>Runs after {@code SignatureAuthFilter}, which has already set the tenancy and region.
 * Emulator endpoints, pre-authenticated requests, CreateRegionSubscription and CORS preflights are
 * exempt: a browser sends the preflight without {@code Authorization}, so it would be checked
 * against the default tenancy rather than the caller, and the signed request is checked anyway. With
 * Identity disabled nothing could subscribe a region, so nothing is checked.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 10)
public class RegionSubscriptionFilter implements ContainerRequestFilter {

    private static final String CORS_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final Pattern CREATE_SUBSCRIPTION =
            Pattern.compile("20160918/tenancies/[^/]+/regionSubscriptions/?");

    /** Injected lazily: this filter is instantiated during Quarkus static init. */
    @Inject
    // Qualified: jakarta.ws.rs.ext.Provider is imported for the @Provider annotation.
    jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    jakarta.inject.Provider<IdentityService> identityProvider;

    @Inject
    RequestContext requestContext;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!configProvider.get().services().identity().enabled() || exempt(ctx)) {
            return;
        }
        String region = requestContext.getRegion();
        if (!identityProvider.get().isSubscribed(region)) {
            ctx.abortWith(Response.status(401)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new OciErrorResponse("NotAuthenticated",
                            "The tenancy is not subscribed to region " + region + "."))
                    .build());
        }
    }

    private static boolean exempt(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.equals("health") || path.startsWith("_floci-oci") || path.startsWith("p/")
                || (HttpMethod.POST.equals(ctx.getMethod()) && CREATE_SUBSCRIPTION.matcher(path).matches())
                || (HttpMethod.OPTIONS.equals(ctx.getMethod()) && ctx.getHeaderString(CORS_REQUEST_METHOD) != null);
    }
}
