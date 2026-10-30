package io.floci.oci.services.functions;

import io.floci.oci.core.common.RequestContextPropagation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;

/**
 * The OCI Functions invoke plane, {@code POST .../functions/{functionId}/actions/invoke}, shared
 * by both API versions: each subclass only mounts it on its version prefix, at the function's
 * {@code invokeEndpoint}. Raw binary in, raw binary out, never JSON-wrapped. Honors
 * {@code fn-invoke-type: detached} (fire and return immediately) and {@code is-dry-run: true}
 * (validate without executing).
 */
abstract class FunctionsInvokeResource {

    private static final Logger LOG = Logger.getLogger(FunctionsInvokeResource.class);

    private final FunctionsService service;
    private final RequestContextPropagation contextPropagation;

    FunctionsInvokeResource(FunctionsService service, RequestContextPropagation contextPropagation) {
        this.service = service;
        this.contextPropagation = contextPropagation;
    }

    /** For the client proxy CDI builds around each {@code @ApplicationScoped} subclass; never serves a request. */
    FunctionsInvokeResource() {
        this(null, null);
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response invoke(@PathParam("functionId") String functionId,
                           @HeaderParam("Content-Type") String contentType,
                           @HeaderParam("fn-invoke-type") String invokeType,
                           @HeaderParam("is-dry-run") String isDryRun,
                           InputStream body) throws IOException {
        if ("true".equalsIgnoreCase(isDryRun)) {
            service.getFunction(functionId); // validate only, never execute
            return Response.ok().build();
        }
        byte[] payload = body != null ? body.readAllBytes() : new byte[0];
        if ("detached".equalsIgnoreCase(invokeType)) {
            // Detached: validate now, then run on a worker thread as the caller (same tenancy and
            // region) and respond immediately with an empty body.
            service.getFunction(functionId);
            Thread.ofVirtual().start(contextPropagation.withCurrentContext(() -> {
                try {
                    service.invoke(functionId, payload, contentType);
                } catch (RuntimeException e) {
                    // The client has already had its 202; the log is the only place this can surface.
                    LOG.warnv("Detached invocation of {0} failed: {1}", functionId, e.getMessage());
                }
            }));
            return Response.accepted().build();
        }
        FunctionsService.InvokeResult result = service.invoke(functionId, payload, contentType);
        return Response.ok(result.body()).type(result.contentType()).build();
    }
}
