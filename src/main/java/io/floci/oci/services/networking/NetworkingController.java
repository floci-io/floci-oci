package io.floci.oci.services.networking;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.networking.model.StoredNetworkResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Core Virtual Network, {@code /20160918/...} beside Identity (the same API version prefix,
 * on the {@code iaas.<region>} host in real OCI). Every resource type shares one CRUD shape, so
 * one set of methods serves all nine collections: create, get and update return the resource with
 * an {@code etag}; delete returns 204; lists are bare JSON arrays with {@code opc-next-page}
 * (oci-go-sdk/core/list_vcns_request_response.go and siblings).
 */
@Path("/20160918")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NetworkingController {

    private final NetworkingService service;
    private final CompartmentValidator compartments;

    @Inject
    public NetworkingController(NetworkingService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    @POST
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}")
    public Response create(@PathParam("collection") String collection, Map<String, Object> body) {
        NetworkKind kind = kind(collection);
        try (CompartmentValidator.Use ignored = compartments.useInBody(NetworkingService.str(body, "compartmentId"))) {
            StoredNetworkResource r = service.create(kind, body);
            Response.ResponseBuilder builder = Response.ok(toWire(r));
            // CreateServiceGateway is the one create whose response carries no etag.
            if (kind != NetworkKind.SERVICE_GATEWAY) {
                builder.header("etag", r.getEtag());
            }
            return builder.build();
        }
    }

    @GET
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}/{id}")
    public Response get(@PathParam("collection") String collection, @PathParam("id") String id) {
        StoredNetworkResource r = service.get(kind(collection), id);
        return Response.ok(toWire(r)).header("etag", r.getEtag()).build();
    }

    @GET
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}")
    public Response list(@PathParam("collection") String collection,
                         @QueryParam("compartmentId") String compartmentId,
                         @QueryParam("vcnId") String vcnId,
                         @QueryParam("displayName") String displayName,
                         @QueryParam("lifecycleState") String lifecycleState,
                         @QueryParam("sortBy") String sortBy,
                         @QueryParam("sortOrder") String sortOrder,
                         @QueryParam("limit") Integer limit,
                         @QueryParam("page") String page) {
        NetworkKind kind = kind(collection);
        // ListNetworkSecurityGroups is the one list where compartmentId is optional.
        if (compartmentId == null && !(kind == NetworkKind.NETWORK_SECURITY_GROUP && vcnId != null)) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> items = service.list(kind, compartmentId, vcnId, displayName,
                lifecycleState, sortBy, sortOrder).stream().map(NetworkingController::toWire).toList();
        return paged(items, limit, page);
    }

    @PUT
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}/{id}")
    public Response update(@PathParam("collection") String collection, @PathParam("id") String id,
                           @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        StoredNetworkResource r = service.update(kind(collection), id, body, ifMatch);
        return Response.ok(toWire(r)).header("etag", r.getEtag()).build();
    }

    @DELETE
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}/{id}")
    public Response delete(@PathParam("collection") String collection, @PathParam("id") String id,
                           @HeaderParam("if-match") String ifMatch) {
        service.delete(kind(collection), id, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/{collection: " + NetworkKind.COLLECTIONS + "}/{id}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("collection") String collection, @PathParam("id") String id,
                                      @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = NetworkingService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            NetworkingService.CompartmentMove move = service.changeCompartment(kind(collection), id,
                    compartmentId, ifMatch);
            if (move.workRequestId() != null) {
                return Response.accepted()
                        .header("etag", move.resource().getEtag())
                        .header("opc-work-request-id", move.workRequestId())
                        .build();
            }
            return Response.ok().header("etag", move.resource().getEtag()).build();
        }
    }

    // ── Service gateways and services ──────────────────────────────────────────

    @POST
    @Path("/serviceGateways/{id}/actions/attachService")
    public Response attachService(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch,
                                  Map<String, Object> body) {
        return serviceGatewayAction(id, body, true, ifMatch);
    }

    @POST
    @Path("/serviceGateways/{id}/actions/detachService")
    public Response detachService(@PathParam("id") String id, @HeaderParam("if-match") String ifMatch,
                                  Map<String, Object> body) {
        return serviceGatewayAction(id, body, false, ifMatch);
    }

    private Response serviceGatewayAction(String id, Map<String, Object> body, boolean attach, String ifMatch) {
        String serviceId = NetworkingService.str(body, "serviceId");
        if (serviceId == null) {
            throw OciException.missingParameter("serviceId is required");
        }
        StoredNetworkResource r = service.attachService(id, serviceId, attach, ifMatch);
        return Response.ok(toWire(r)).header("etag", r.getEtag()).build();
    }

    @GET
    @Path("/services")
    public Response listServices(@QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        return paged(service.listServices(), limit, page);
    }

    @GET
    @Path("/services/{serviceId}")
    public Response getService(@PathParam("serviceId") String serviceId) {
        return Response.ok(service.getService(serviceId)).build();
    }

    // ── Network security group rules ───────────────────────────────────────────

    @POST
    @Path("/networkSecurityGroups/{id}/actions/addSecurityRules")
    public Response addSecurityRules(@PathParam("id") String id, Map<String, Object> body) {
        List<?> rules = body != null && body.get("securityRules") instanceof List<?> l ? l : List.of();
        return Response.ok(Map.of("securityRules", service.addSecurityRules(id, rules))).build();
    }

    @POST
    @Path("/networkSecurityGroups/{id}/actions/updateSecurityRules")
    public Response updateSecurityRules(@PathParam("id") String id, Map<String, Object> body) {
        List<?> rules = body != null && body.get("securityRules") instanceof List<?> l ? l : List.of();
        return Response.ok(Map.of("securityRules", service.updateSecurityRules(id, rules))).build();
    }

    @POST
    @Path("/networkSecurityGroups/{id}/actions/removeSecurityRules")
    public Response removeSecurityRules(@PathParam("id") String id, Map<String, Object> body) {
        service.removeSecurityRules(id, NetworkingService.stringList(body == null ? null : body.get("securityRuleIds")));
        return Response.ok().build();
    }

    @GET
    @Path("/networkSecurityGroups/{id}/securityRules")
    public Response listSecurityRules(@PathParam("id") String id,
                                      @QueryParam("direction") String direction,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        return paged(service.listSecurityRules(id, direction), limit, page);
    }

    /**
     * GetVcnDnsResolverAssociation (oci-go-sdk core/vcn_dns_resolver_association.go): the VCN's
     * dedicated DNS resolver, which the DNS service creates with the VCN.
     */
    @GET
    @Path("/vcns/{vcnId}/dnsResolverAssociation")
    public Response getVcnDnsResolverAssociation(@PathParam("vcnId") String vcnId) {
        StoredNetworkResource vcn = service.get(NetworkKind.VCN, vcnId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("vcnId", vcn.getId());
        service.dnsResolverId(vcnId).ifPresent(id -> json.put("dnsResolverId", id));
        json.put("lifecycleState", "AVAILABLE");
        return Response.ok(json).header("etag", vcn.getEtag()).build();
    }

    /** floci-oci attaches no VNICs to anything, so this list is always empty. */
    @GET
    @Path("/networkSecurityGroups/{id}/vnics")
    public Response listNetworkSecurityGroupVnics(@PathParam("id") String id) {
        service.get(NetworkKind.NETWORK_SECURITY_GROUP, id);
        return Response.ok(List.of()).build();
    }

    // ── Wire projection ────────────────────────────────────────────────────────

    static Map<String, Object> toWire(StoredNetworkResource r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", r.getId());
        json.put("compartmentId", r.getCompartmentId());
        if (r.getVcnId() != null) {
            json.put("vcnId", r.getVcnId());
        }
        json.put("displayName", r.getDisplayName());
        json.put("lifecycleState", r.getLifecycleState());
        json.put("timeCreated", r.getTimeCreated());
        json.putAll(r.getAttributes());
        json.put("freeformTags", r.getFreeformTags());
        json.put("definedTags", r.getDefinedTags());
        // Terraform marks systemTags computed; omitting it drifts on every plan.
        json.put("systemTags", Map.of());
        return json;
    }

    private static NetworkKind kind(String collection) {
        return NetworkKind.byCollection(collection)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("Unknown collection " + collection));
    }

    private static <T> Response paged(List<T> all, Integer limit, String page) {
        OciPage.Page<T> result = OciPage.paginate(all, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }
}
