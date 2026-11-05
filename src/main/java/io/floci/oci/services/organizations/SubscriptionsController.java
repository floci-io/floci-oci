package io.floci.oci.services.organizations;

import io.floci.oci.services.organizations.model.StoredSubscriptionMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * OCI Tenant Manager subscriptions, assigned subscriptions and subscription mappings at
 * {@code /20230401}. All synchronous: no operation returns a work request. Subscriptions are
 * polymorphic on {@code entityVersion}; floci-oci serves the V2 (cloud) shape.
 */
@Path("/20230401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SubscriptionsController {

    private final SubscriptionsService service;

    @Inject
    public SubscriptionsController(SubscriptionsService service) {
        this.service = service;
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    @GET
    @Path("/subscriptions")
    public Response listSubscriptions(@QueryParam("compartmentId") String compartmentId,
                                      @QueryParam("subscriptionId") String subscriptionId,
                                      @QueryParam("entityVersion") String entityVersion,
                                      @QueryParam("sortBy") String sortBy,
                                      @QueryParam("sortOrder") String sortOrder,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        return Bodies.collection(service.listSubscriptions(compartmentId, subscriptionId, entityVersion, sortBy,
                sortOrder), limit, page);
    }

    @GET
    @Path("/subscriptions/{subscriptionId}")
    public Response getSubscription(@PathParam("subscriptionId") String subscriptionId) {
        return withEtag(service.getSubscription(subscriptionId));
    }

    @GET
    @Path("/subscriptions/{subscriptionId}/subscriptionLineItems")
    public Response listSubscriptionLineItems(@PathParam("subscriptionId") String subscriptionId,
                                              @QueryParam("sortBy") String sortBy,
                                              @QueryParam("sortOrder") String sortOrder,
                                              @QueryParam("limit") Integer limit,
                                              @QueryParam("page") String page) {
        return Bodies.collection(service.listSubscriptionLineItems(subscriptionId, sortBy, sortOrder), limit, page);
    }

    @GET
    @Path("/subscriptions/{subscriptionId}/availableRegions")
    public Response listAvailableRegions(@PathParam("subscriptionId") String subscriptionId,
                                         @QueryParam("page") String page) {
        return Bodies.collection(service.listAvailableRegions(subscriptionId), null, page);
    }

    // ── Assigned subscriptions ─────────────────────────────────────────────────

    @GET
    @Path("/assignedSubscriptions")
    public Response listAssignedSubscriptions(@QueryParam("compartmentId") String compartmentId,
                                              @QueryParam("subscriptionId") String subscriptionId,
                                              @QueryParam("entityVersion") String entityVersion,
                                              @QueryParam("sortBy") String sortBy,
                                              @QueryParam("sortOrder") String sortOrder,
                                              @QueryParam("limit") Integer limit,
                                              @QueryParam("page") String page) {
        return Bodies.collection(service.listAssignedSubscriptions(compartmentId, subscriptionId, entityVersion,
                sortBy, sortOrder), limit, page);
    }

    @GET
    @Path("/assignedSubscriptions/{assignedSubscriptionId}")
    public Response getAssignedSubscription(@PathParam("assignedSubscriptionId") String id) {
        return withEtag(service.getAssignedSubscription(id));
    }

    @GET
    @Path("/assignedSubscriptions/{assignedSubscriptionId}/assignedSubscriptionLineItems")
    public Response listAssignedSubscriptionLineItems(@PathParam("assignedSubscriptionId") String id,
                                                      @QueryParam("sortBy") String sortBy,
                                                      @QueryParam("sortOrder") String sortOrder,
                                                      @QueryParam("limit") Integer limit,
                                                      @QueryParam("page") String page) {
        return Bodies.collection(service.listAssignedSubscriptionLineItems(id, sortBy, sortOrder), limit, page);
    }

    // ── Subscription mappings ──────────────────────────────────────────────────

    @POST
    @Path("/subscriptionMappings")
    public Response createSubscriptionMapping(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        StoredSubscriptionMapping mapping = service.createSubscriptionMapping(Bodies.str(details, "compartmentId"),
                Bodies.str(details, "subscriptionId"));
        return Response.ok(mapping.toWire()).header("etag", mapping.getEtag()).build();
    }

    @GET
    @Path("/subscriptionMappings/{subscriptionMappingId}")
    public Response getSubscriptionMapping(@PathParam("subscriptionMappingId") String id) {
        StoredSubscriptionMapping mapping = service.getSubscriptionMapping(id);
        return Response.ok(mapping.toWire()).header("etag", mapping.getEtag()).build();
    }

    @GET
    @Path("/subscriptionMappings")
    public Response listSubscriptionMappings(@QueryParam("subscriptionId") String subscriptionId,
                                             @QueryParam("subscriptionMappingId") String subscriptionMappingId,
                                             @QueryParam("compartmentId") String compartmentId,
                                             @QueryParam("lifecycleState") String lifecycleState,
                                             @QueryParam("sortBy") String sortBy,
                                             @QueryParam("sortOrder") String sortOrder,
                                             @QueryParam("limit") Integer limit,
                                             @QueryParam("page") String page) {
        return Bodies.collection(service.listSubscriptionMappings(subscriptionId, subscriptionMappingId,
                compartmentId, lifecycleState, sortBy, sortOrder).stream()
                .map(StoredSubscriptionMapping::toWire).toList(), limit, page);
    }

    @DELETE
    @Path("/subscriptionMappings/{subscriptionMappingId}")
    public Response deleteSubscriptionMapping(@PathParam("subscriptionMappingId") String id,
                                              @HeaderParam("if-match") String ifMatch) {
        service.deleteSubscriptionMapping(id, ifMatch);
        return Response.noContent().build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Subscriptions have no stored record, so the etag is derived from the shape. */
    private static Response withEtag(Map<String, Object> wire) {
        return Response.ok(wire).header("etag", Integer.toHexString(wire.hashCode())).build();
    }
}
