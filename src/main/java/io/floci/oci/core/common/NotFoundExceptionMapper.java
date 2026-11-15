package io.floci.oci.core.common;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Shapes unmatched-path 404s as the OCI error body instead of the default HTML page,
 * so they carry {@code {"code": "NotFound", ...}} and pass through the response filter
 * chain that stamps {@code opc-request-id}. Under the Floci control-plane prefixes the body is the
 * control-plane one, {@code {"error": "NotFound", "path": ...}}, which every Floci emulator shares.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NotFoundException exception) {
        String path = uriInfo == null ? null : uriInfo.getPath();
        if (path != null && isControlPlanePath(path)) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "NotFound", "path", path))
                    .build();
        }
        return Response.status(404)
                .type(MediaType.APPLICATION_JSON)
                .entity(new OciErrorResponse("NotFound", "The requested resource was not found."))
                .build();
    }

    static boolean isControlPlanePath(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return relative.equals("_floci") || relative.startsWith("_floci/")
                || relative.equals("_floci-oci") || relative.startsWith("_floci-oci/");
    }
}
