package io.floci.oci.services.organizations;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * OCI Tenant Manager orders at {@code /20230401}: GetOrder returns the order an activation token
 * names; ActivateOrder is work-request based ({@code 202}, {@code opc-work-request-id}, no body).
 */
@Path("/20230401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdersController {

    private final OrdersService service;

    @Inject
    public OrdersController(OrdersService service) {
        this.service = service;
    }

    @GET
    @Path("/orders/{activationToken}")
    public Response getOrder(@PathParam("activationToken") String activationToken) {
        return Response.ok(service.getOrder(activationToken)).build();
    }

    @POST
    @Path("/orders/{activationToken}/actions/activate")
    public Response activateOrder(@PathParam("activationToken") String activationToken, Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        String workRequestId = service.activateOrder(activationToken, Bodies.str(details, "compartmentId"));
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }
}
