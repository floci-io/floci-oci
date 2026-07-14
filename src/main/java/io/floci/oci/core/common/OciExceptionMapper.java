package io.floci.oci.core.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Renders {@link OciException} as the OCI wire error shape. The {@code opc-request-id}
 * header is stamped on every response — including these — by {@code OpcRequestIdFilter}.
 */
@Provider
public class OciExceptionMapper implements ExceptionMapper<OciException> {

    private static final Logger LOG = Logger.getLogger(OciExceptionMapper.class);

    @Override
    public Response toResponse(OciException exception) {
        LOG.debugv("Mapping exception: {0} - {1}", exception.getCode(), exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new OciErrorResponse(exception.getCode(), exception.getMessage()))
                .build();
    }
}
