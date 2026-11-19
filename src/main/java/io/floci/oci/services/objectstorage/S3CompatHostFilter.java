package io.floci.oci.services.objectstorage;

import io.floci.oci.core.auth.AccessKeyResolver;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Routes Amazon S3 Compatibility API requests to {@link S3CompatResource}. OCI serves that API on
 * its own host, {@code <namespace>.compat.objectstorage.<region>.<domain>}, path style
 * ({@code /<bucket>/<object>}), so the namespace travels in the Host and a request is told apart by
 * the Host alone (Object/Tasks/s3compatibleapi.htm). Only a request whose Host has that shape is
 * rewritten, onto the internal {@code /_s3compat/<namespace>} root; every other request is left
 * exactly as it came. Runs after {@code SignatureAuthFilter}, which has already taken the region
 * from the same Host and the tenancy from the SigV4 access key.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 10)
public class S3CompatHostFilter implements ContainerRequestFilter {

    static final String ROOT = "/_s3compat/";

    @Override
    public void filter(ContainerRequestContext ctx) {
        // Rewritten whether or not Object Storage is enabled: a disabled service answers 503 on the
        // S3 resource, and an S3 host never reaches a native route.
        Optional<String> namespace = namespaceOf(ctx.getHeaderString(HttpHeaders.HOST));
        if (namespace.isEmpty()) {
            return;
        }
        URI request = ctx.getUriInfo().getRequestUri();
        String rawPath = request.getRawPath() == null || request.getRawPath().isEmpty() ? "/" : request.getRawPath();
        String rewritten = ROOT + namespace.get() + rawPath
                + (request.getRawQuery() == null ? "" : "?" + request.getRawQuery());
        ctx.setRequestUri(ctx.getUriInfo().getBaseUri(), ctx.getUriInfo().getBaseUri().resolve(rewritten.substring(1)));
    }

    static Optional<String> namespaceOf(String host) {
        if (host == null) {
            return Optional.empty();
        }
        Matcher m = AccessKeyResolver.S3_COMPAT_HOST.matcher(host);
        return m.find() ? Optional.of(m.group(1).toLowerCase()) : Optional.empty();
    }
}
