package io.floci.oci.services.organizations;

import io.floci.oci.services.organizations.model.StoredDomain;
import io.floci.oci.services.organizations.model.StoredDomainGovernance;
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

import java.util.Map;

/**
 * OCI Tenant Manager domains and domain governance at {@code /20230401}. Create, get and update
 * return the resource with an {@code etag}; CreateDomain also returns an
 * {@code opc-work-request-id}. Deletes return no body and no work request.
 */
@Path("/20230401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DomainsController {

    private final DomainsService service;

    @Inject
    public DomainsController(DomainsService service) {
        this.service = service;
    }

    // ── Domains ────────────────────────────────────────────────────────────────

    @POST
    @Path("/domains")
    public Response createDomain(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        DomainsService.Created created = service.createDomain(Bodies.str(details, "compartmentId"),
                Bodies.str(details, "domainName"), Bodies.str(details, "subscriptionEmail"),
                Bodies.bool(details, "isGovernanceEnabled"), Bodies.freeformTags(details), Bodies.definedTags(details));
        return Response.ok(created.domain().toWire())
                .header("etag", created.domain().getEtag())
                .header("opc-work-request-id", created.workRequestId())
                .build();
    }

    @GET
    @Path("/domains/{domainId}")
    public Response getDomain(@PathParam("domainId") String id) {
        return withEtag(service.getDomain(id));
    }

    @GET
    @Path("/domains")
    public Response listDomains(@QueryParam("compartmentId") String compartmentId,
                                @QueryParam("domainId") String domainId,
                                @QueryParam("lifecycleState") String lifecycleState,
                                @QueryParam("status") String status,
                                @QueryParam("name") String name,
                                @QueryParam("sortBy") String sortBy,
                                @QueryParam("sortOrder") String sortOrder,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        return Bodies.collection(service.listDomains(compartmentId, domainId, lifecycleState, status, name, sortBy,
                sortOrder).stream().map(StoredDomain::toWire).toList(), limit, page);
    }

    @PUT
    @Path("/domains/{domainId}")
    public Response updateDomain(@PathParam("domainId") String id,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return withEtag(service.updateDomain(id, Bodies.freeformTags(details), Bodies.definedTags(details),
                ifMatch));
    }

    @DELETE
    @Path("/domains/{domainId}")
    public Response deleteDomain(@PathParam("domainId") String id,
                                 @HeaderParam("if-match") String ifMatch) {
        service.deleteDomain(id, ifMatch);
        return Response.noContent().build();
    }

    // ── Domain governance ──────────────────────────────────────────────────────

    @POST
    @Path("/domainGovernances")
    public Response createDomainGovernance(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return withEtag(service.createDomainGovernance(Bodies.str(details, "compartmentId"),
                Bodies.str(details, "domainId"), Bodies.str(details, "subscriptionEmail"),
                Bodies.str(details, "onsTopicId"), Bodies.str(details, "onsSubscriptionId"),
                Bodies.freeformTags(details), Bodies.definedTags(details)));
    }

    @GET
    @Path("/domainGovernances/{domainGovernanceId}")
    public Response getDomainGovernance(@PathParam("domainGovernanceId") String id) {
        return withEtag(service.getDomainGovernance(id));
    }

    @GET
    @Path("/domainGovernances")
    public Response listDomainGovernances(@QueryParam("compartmentId") String compartmentId,
                                          @QueryParam("domainId") String domainId,
                                          @QueryParam("domainGovernanceId") String domainGovernanceId,
                                          @QueryParam("lifecycleState") String lifecycleState,
                                          @QueryParam("name") String name,
                                          @QueryParam("sortBy") String sortBy,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        return Bodies.collection(service.listDomainGovernances(compartmentId, domainId, domainGovernanceId,
                lifecycleState, name, sortBy, sortOrder).stream().map(StoredDomainGovernance::toWire).toList(),
                limit, page);
    }

    @PUT
    @Path("/domainGovernances/{domainGovernanceId}")
    public Response updateDomainGovernance(@PathParam("domainGovernanceId") String id,
                                           @HeaderParam("if-match") String ifMatch,
                                           Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return withEtag(service.updateDomainGovernance(id, Bodies.str(details, "subscriptionEmail"),
                Bodies.bool(details, "isGovernanceEnabled"), Bodies.freeformTags(details), Bodies.definedTags(details),
                ifMatch));
    }

    @DELETE
    @Path("/domainGovernances/{domainGovernanceId}")
    public Response deleteDomainGovernance(@PathParam("domainGovernanceId") String id,
                                           @HeaderParam("if-match") String ifMatch) {
        service.deleteDomainGovernance(id, ifMatch);
        return Response.noContent().build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Response withEtag(StoredDomain domain) {
        return Response.ok(domain.toWire()).header("etag", domain.getEtag()).build();
    }

    private static Response withEtag(StoredDomainGovernance governance) {
        return Response.ok(governance.toWire()).header("etag", governance.getEtag()).build();
    }
}
