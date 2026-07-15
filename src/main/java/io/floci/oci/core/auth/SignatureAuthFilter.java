package io.floci.oci.core.auth;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.common.RequestContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/**
 * Populates {@link RequestContext} with the tenancy/user parsed from the OCI Signature
 * {@code Authorization} header, falling back to the configured default tenancy.
 *
 * <p>When {@code floci-oci.auth.require-signature=true}, requests whose Authorization
 * header is missing or structurally malformed are rejected with 401 NotAuthenticated.
 * Emulator-internal endpoints ({@code /health}, {@code /_floci-oci/…}) and Object Storage
 * pre-authenticated requests ({@code /p/…}) are always exempt.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class SignatureAuthFilter implements ContainerRequestFilter {

    /**
     * Injected lazily: this filter is instantiated during Quarkus static init,
     * before runtime config mappings are registered.
     */
    @Inject
    jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    RequestContext requestContext;

    @Override
    public void filter(ContainerRequestContext ctx) {
        EmulatorConfig config = configProvider.get();
        String path = ctx.getUriInfo().getPath();
        boolean exempt = path.equals("health") || path.startsWith("_floci-oci")
                || path.startsWith("p/");

        String authorization = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        Optional<AuthContext> auth = OciSignatureParser.parse(authorization);

        if (auth.isPresent()) {
            requestContext.setTenancyId(auth.get().tenancyId());
            requestContext.setUserId(auth.get().userId());
        } else {
            if (!exempt && config.auth().requireSignature()) {
                ctx.abortWith(Response.status(401)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new OciErrorResponse("NotAuthenticated",
                                "The required authorization signature is missing or invalid."))
                        .build());
                return;
            }
            requestContext.setTenancyId(config.defaultTenancyId());
        }
        requestContext.setRegion(config.defaultRegion());
    }
}
