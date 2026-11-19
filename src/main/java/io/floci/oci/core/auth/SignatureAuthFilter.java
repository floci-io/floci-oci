package io.floci.oci.core.auth;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciErrorResponse;
import io.floci.oci.core.common.Regions;
import io.floci.oci.core.common.RequestContext;
import io.floci.oci.core.tenancy.TenancyRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
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
 * {@code Authorization} header, falling back to the configured default tenancy, and with the
 * region named by the {@code Host} header, falling back to the tenancy's home region (which is
 * the configured default region for a tenancy not registered yet). A regional {@code Host} also
 * becomes the base of the URLs returned to the client, so following them stays in that region.
 *
 * <p>When {@code floci-oci.auth.require-signature=true}, requests whose Authorization
 * header is missing or structurally malformed are rejected with 401 NotAuthenticated.
 * Emulator-internal endpoints ({@code /health}, {@code /_floci/…}, {@code /_floci-oci/…}) and Object Storage
 * pre-authenticated requests ({@code /p/…}) are always exempt.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class SignatureAuthFilter implements ContainerRequestFilter {

    /** The token endpoint the OCIR data plane advertises in its Bearer challenge. */
    public static final String OCIR_TOKEN_PATH = "20180419/docker/token";

    /**
     * Injected lazily: this filter is instantiated during Quarkus static init,
     * before runtime config mappings are registered.
     */
    @Inject
    // Qualified: jakarta.ws.rs.ext.Provider is imported for the @Provider annotation.
    jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    jakarta.inject.Provider<TenancyRegistry> tenancies;

    @Inject
    RequestContext requestContext;

    /** Resolves S3 Compatibility API access keys; unresolvable when Identity serves none. */
    @Inject
    Instance<AccessKeyResolver> accessKeys;

    @Override
    public void filter(ContainerRequestContext ctx) {
        EmulatorConfig config = configProvider.get();
        boolean exempt = isExempt(ctx.getUriInfo().getPath());

        String authorization = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        Optional<AuthContext> auth = OciSignatureParser.parse(authorization);
        // SigV4 credentials count only on the S3 Compatibility API host: a native route keeps its
        // OCI signature requirement whatever S3-looking header or query it carries.
        Optional<String> s3AccessKey = AccessKeyResolver.isS3CompatHost(ctx.getHeaderString(HttpHeaders.HOST))
                ? AccessKeyResolver.sigV4AccessKey(authorization)
                        .or(() -> AccessKeyResolver.presignedAccessKey(
                                ctx.getUriInfo().getQueryParameters().getFirst("X-Amz-Credential")))
                : Optional.empty();
        if (auth.isEmpty() && s3AccessKey.isPresent() && accessKeys.isResolvable()) {
            auth = accessKeys.get().resolve(s3AccessKey.get());
        }

        if (auth.isPresent()) {
            requestContext.setTenancyId(auth.get().tenancyId());
            requestContext.setUserId(auth.get().userId());
            // Pins the home region on first sight, so a tenancy that only reads keeps it across
            // a later default-region change.
            tenancies.get().get(auth.get().tenancyId());
        } else {
            // An S3 Compatibility API request is signed with SigV4; an access key no Customer
            // Secret Key owns still reaches the default tenancy, since signatures are never checked.
            if (!exempt && s3AccessKey.isEmpty() && config.auth().requireSignature()) {
                ctx.abortWith(Response.status(401)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new OciErrorResponse("NotAuthenticated",
                                "The required authorization signature is missing or invalid."))
                        .build());
                return;
            }
            requestContext.setTenancyId(config.defaultTenancyId());
        }
        String host = ctx.getHeaderString(HttpHeaders.HOST);
        Optional<String> hostRegion = Regions.inHost(host);
        // A host that names no region (localhost) reaches the tenancy's home region.
        requestContext.setRegion(hostRegion.orElseGet(
                () -> tenancies.get().homeRegionOrDefault(requestContext.getTenancyId())));
        if (hostRegion.isPresent()) {
            requestContext.setBaseUrl(ctx.getUriInfo().getBaseUri().getScheme() + "://" + host);
        }
    }

    /**
     * Whether {@code path} needs no signature: the health probe, the emulator's own
     * {@code /_floci} and {@code /_floci-oci} endpoints (which k3s and Docker health checks call unsigned), and Object
     * Storage pre-authenticated requests, whose token is the credential. The request path
     * arrives with its leading slash.
     */
    static boolean isExempt(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return relative.equals("health")
                || relative.equals("_floci") || relative.startsWith("_floci/")
                || relative.equals("_floci-oci") || relative.startsWith("_floci-oci/")
                || relative.startsWith("p/")
                // Docker clients speak the registry protocol and never sign OCI requests.
                || relative.equals("v2") || relative.startsWith("v2/")
                || relative.equals(OCIR_TOKEN_PATH);
    }
}
