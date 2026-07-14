package io.floci.oci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Stamps the {@code opc-request-id} header on every response — including errors —
 * echoing the caller's value when present, and echoes {@code opc-client-request-id}
 * back verbatim. This is the OCI contract every SDK relies on for request tracing.
 */
@Provider
public class OpcRequestIdFilter implements ContainerResponseFilter {

    public static final String OPC_REQUEST_ID = "opc-request-id";
    public static final String OPC_CLIENT_REQUEST_ID = "opc-client-request-id";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!responseContext.getHeaders().containsKey(OPC_REQUEST_ID)) {
            String incoming = requestContext.getHeaderString(OPC_REQUEST_ID);
            responseContext.getHeaders().add(OPC_REQUEST_ID,
                    incoming != null && !incoming.isBlank() ? incoming : newRequestId());
        }
        String clientRequestId = requestContext.getHeaderString(OPC_CLIENT_REQUEST_ID);
        if (clientRequestId != null && !clientRequestId.isBlank()
                && !responseContext.getHeaders().containsKey(OPC_CLIENT_REQUEST_ID)) {
            responseContext.getHeaders().add(OPC_CLIENT_REQUEST_ID, clientRequestId);
        }
    }

    /** Real OCI request ids are opaque 32-char uppercase hex tokens (often /-separated). */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
