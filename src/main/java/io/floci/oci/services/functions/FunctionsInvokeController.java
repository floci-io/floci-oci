package io.floci.oci.services.functions;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;

/**
 * OCI Functions invoke plane — {@code POST /20181201/functions/{functionId}/actions/invoke}
 * on the function's {@code invokeEndpoint}. Raw binary in, raw binary out — never
 * JSON-wrapped. Honors {@code fn-invoke-type: detached} (fire and return immediately)
 * and {@code is-dry-run: true} (validate without executing).
 */
@Path("/20181201/functions/{functionId}/actions/invoke")
@ApplicationScoped
public class FunctionsInvokeController {

    private final FunctionsService service;

    @Inject
    public FunctionsInvokeController(FunctionsService service) {
        this.service = service;
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
            // Detached: fire on a worker thread, respond immediately with an empty body.
            Thread.ofVirtual().start(() -> {
                try {
                    service.invoke(functionId, payload, contentType);
                } catch (RuntimeException ignored) {
                    // Detached invocations report nothing back.
                }
            });
            service.getFunction(functionId);
            return Response.accepted().build();
        }
        FunctionsService.InvokeResult result = service.invoke(functionId, payload, contentType);
        return Response.ok(result.body()).type(result.contentType()).build();
    }
}
