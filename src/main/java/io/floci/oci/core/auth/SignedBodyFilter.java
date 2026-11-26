package io.floci.oci.core.auth;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the body of a request whose verified signature covers {@code x-content-sha256}: the header
 * must be the base64 SHA-256 of the body (oci-go-sdk {@code http_signer.go}, {@code GetBodyHash}), or
 * the request is 401 NotAuthenticated. The body is buffered and put back for the resource method.
 *
 * <p>Post-matching, so it runs on the worker thread that blocking endpoints use: reading the body in
 * the {@code @PreMatching} {@link SignatureAuthFilter} would block the IO thread. Unsigned bodies are
 * never buffered, and a signed body over {@link #MAX_SIGNED_BODY_BYTES} is refused rather than held in
 * memory (the SDKs sign only JSON-sized bodies; streamed uploads such as PutObject sign without the body
 * headers).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class SignedBodyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(SignedBodyFilter.class);

    /** The request property {@link SignatureAuthFilter} sets to the signed {@code x-content-sha256} value. */
    static final String EXPECTED_BODY_HASH = SignedBodyFilter.class.getName() + ".expected";
    static final int MAX_SIGNED_BODY_BYTES = 64 * 1024 * 1024;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(EXPECTED_BODY_HASH) instanceof String expected)) {
            return;
        }
        InputStream in = ctx.getEntityStream();
        byte[] body;
        try {
            body = in.readNBytes(MAX_SIGNED_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the signed request body", e);
        }
        if (body.length > MAX_SIGNED_BODY_BYTES) {
            reject(ctx, "a body covered by x-content-sha256 must not exceed "
                    + MAX_SIGNED_BODY_BYTES / (1024 * 1024) + " MiB");
            return;
        }
        String actual = SignatureVerifier.bodyHash(body);
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.trim().getBytes(StandardCharsets.US_ASCII))) {
            reject(ctx, "the body does not match x-content-sha256");
            return;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
    }

    private static void reject(ContainerRequestContext ctx, String reason) {
        LOG.debugv("Rejected the signed body of {0} {1}: {2}", ctx.getMethod(), ctx.getUriInfo().getPath(), reason);
        ctx.abortWith(SignatureAuthFilter.notVerified(reason));
    }
}
