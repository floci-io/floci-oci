package io.floci.oci.core.common;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Shapes unmatched-path 404s as the OCI error body instead of the default HTML page,
 * so they carry {@code {"code": "NotFound", ...}} and pass through the response filter
 * chain that stamps {@code opc-request-id}.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException exception) {
        return Response.status(404)
                .type(MediaType.APPLICATION_JSON)
                .entity(new OciErrorResponse("NotFound", "The requested resource was not found."))
                .build();
    }
}
