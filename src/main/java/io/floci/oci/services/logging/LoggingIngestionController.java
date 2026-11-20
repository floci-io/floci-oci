package io.floci.oci.services.logging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * OCI Logging Ingestion, {@code /20200831} (oci-go-sdk loggingingestion/loggingingestion_client.go).
 * Real OCI serves it on the {@code ingestion.logging} host; floci-oci answers on the same port.
 */
@Path("/20200831")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoggingIngestionController {

    private final LoggingService service;

    @Inject
    public LoggingIngestionController(LoggingService service) {
        this.service = service;
    }

    @POST
    @Path("/logs/{logId}/actions/push")
    public Response putLogs(@PathParam("logId") String logId, Map<String, Object> body) {
        service.putLogs(logId, body);
        return Response.ok().build();
    }
}
