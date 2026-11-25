package io.floci.oci.services.dns;

import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.dns.DnsService.RecordFilter;
import io.floci.oci.services.dns.DnsService.RecordScope;
import io.floci.oci.services.dns.DnsService.ZoneQuery;
import io.floci.oci.services.dns.model.StoredRecord;
import io.floci.oci.services.dns.model.StoredResolver;
import io.floci.oci.services.dns.model.StoredView;
import io.floci.oci.services.dns.model.StoredZone;
import io.floci.oci.services.identity.CompartmentValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI DNS, {@code /20180115} (oci-go-sdk dns/dns_client.go). ListZones, ListViews and ListResolvers
 * return bare arrays; every record read and write returns a {@code {"items": [...]}} collection with
 * {@code opc-total-items}, {@code opc-next-page} and the zone's {@code etag}.
 */
@Path(DnsService.API_PREFIX)
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DnsController {

    private final DnsService service;
    private final CompartmentValidator compartments;
    private final OciContext ociContext;

    @Inject
    public DnsController(DnsService service, CompartmentValidator compartments, OciContext ociContext) {
        this.service = service;
        this.compartments = compartments;
        this.ociContext = ociContext;
    }

    // ── Zones ──────────────────────────────────────────────────────────────────

    @POST
    @Path("/zones")
    public Response createZone(@QueryParam("compartmentId") String compartmentId, @QueryParam("scope") String scope,
                               @QueryParam("viewId") String viewId, Map<String, Object> body) {
        String target = DnsService.str(body, "compartmentId") != null ? DnsService.str(body, "compartmentId")
                : compartmentId;
        try (CompartmentValidator.Use ignored = compartments.useInBody(target)) {
            StoredZone zone = service.createZone(body, scope, viewId, compartmentId);
            String self = zoneSelf(zone);
            return Response.created(URI.create(self)).entity(zoneJson(zone, self, true))
                    .header("etag", zone.getEtag()).build();
        }
    }

    @POST
    @Path("/actions/createZoneFromZoneFile")
    public Response createZoneFromZoneFile() {
        throw OciException.notImplemented("Creating a zone from a zone file is not emulated");
    }

    @GET
    @Path("/zones")
    public Response listZones(@QueryParam("compartmentId") String compartmentId, @QueryParam("name") String name,
                              @QueryParam("nameContains") String nameContains,
                              @QueryParam("zoneType") String zoneType,
                              @QueryParam("timeCreatedGreaterThanOrEqualTo") String createdFrom,
                              @QueryParam("timeCreatedLessThan") String createdBefore,
                              @QueryParam("lifecycleState") String lifecycleState,
                              @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                              @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                              @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredZone zone : service.listZones(compartmentId, name, nameContains, zoneType, createdFrom,
                createdBefore, lifecycleState, scope, viewId, sortBy, sortOrder)) {
            items.add(zoneJson(zone, zoneSelf(zone), false));
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        return paged(Response.ok(result.items()), result, items.size()).build();
    }

    @GET
    @Path("/zones/{zoneNameOrId}")
    public Response getZone(@PathParam("zoneNameOrId") String zoneNameOrId, @HeaderParam("If-None-Match") String ifNoneMatch,
                            @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                            @QueryParam("compartmentId") String compartmentId) {
        StoredZone zone = service.getZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId));
        if (notModified(ifNoneMatch, zone.getEtag())) {
            return Response.notModified().header("etag", zone.getEtag()).build();
        }
        return Response.ok(zoneJson(zone, zoneSelf(zone), true)).header("etag", zone.getEtag()).build();
    }

    @PUT
    @Path("/zones/{zoneNameOrId}")
    public Response updateZone(@PathParam("zoneNameOrId") String zoneNameOrId, @HeaderParam("If-Match") String ifMatch,
                               @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                               @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        StoredZone zone = service.updateZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId), body, ifMatch);
        return Response.ok(zoneJson(zone, zoneSelf(zone), true)).header("etag", zone.getEtag()).build();
    }

    @DELETE
    @Path("/zones/{zoneNameOrId}")
    public Response deleteZone(@PathParam("zoneNameOrId") String zoneNameOrId, @HeaderParam("If-Match") String ifMatch,
                               @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                               @QueryParam("compartmentId") String compartmentId) {
        service.deleteZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId), ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/zones/{zoneId}/actions/changeCompartment")
    public Response changeZoneCompartment(@PathParam("zoneId") String zoneId, @HeaderParam("If-Match") String ifMatch,
                                          Map<String, Object> body) {
        String compartmentId = DnsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeZoneCompartment(zoneId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    // ── Records ────────────────────────────────────────────────────────────────

    @GET
    @Path("/zones/{zoneNameOrId}/records")
    public Response getZoneRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                   @HeaderParam("If-None-Match") String ifNoneMatch,
                                   @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                   @QueryParam("compartmentId") String compartmentId,
                                   @QueryParam("domain") String domain,
                                   @QueryParam("domainContains") String domainContains,
                                   @QueryParam("rtype") String rtype, @QueryParam("sortBy") String sortBy,
                                   @QueryParam("sortOrder") String sortOrder, @QueryParam("limit") Integer limit,
                                   @QueryParam("page") String page) {
        StoredZone zone = service.getZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId));
        return readRecords(zone, RecordScope.ZONE, new RecordFilter(domain, domainContains, rtype, sortBy, sortOrder),
                ifNoneMatch, limit, page);
    }

    @PUT
    @Path("/zones/{zoneNameOrId}/records")
    public Response updateZoneRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                      @HeaderParam("If-Match") String ifMatch, @QueryParam("scope") String scope,
                                      @QueryParam("viewId") String viewId,
                                      @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        StoredZone zone = service.updateRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                RecordScope.ZONE, items(body), ifMatch);
        return readRecords(zone, RecordScope.ZONE, RecordFilter.NONE, null, null, null);
    }

    @PATCH
    @Path("/zones/{zoneNameOrId}/records")
    public Response patchZoneRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                     @HeaderParam("If-Match") String ifMatch, @QueryParam("scope") String scope,
                                     @QueryParam("viewId") String viewId,
                                     @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        StoredZone zone = service.patchRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                RecordScope.ZONE, items(body), ifMatch);
        return readRecords(zone, RecordScope.ZONE, RecordFilter.NONE, null, null, null);
    }

    @GET
    @Path("/zones/{zoneNameOrId}/records/{domain}")
    public Response getDomainRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                     @PathParam("domain") String domain,
                                     @HeaderParam("If-None-Match") String ifNoneMatch,
                                     @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                     @QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("rtype") String rtype, @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder, @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        StoredZone zone = service.getZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId));
        return readRecords(zone, new RecordScope(DnsService.trimDot(domain), null),
                new RecordFilter(null, null, rtype, sortBy, sortOrder), ifNoneMatch, limit, page);
    }

    @PUT
    @Path("/zones/{zoneNameOrId}/records/{domain}")
    public Response updateDomainRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                        @PathParam("domain") String domain, @HeaderParam("If-Match") String ifMatch,
                                        @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                        @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        RecordScope recordScope = new RecordScope(DnsService.trimDot(domain), null);
        StoredZone zone = service.updateRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                recordScope, items(body), ifMatch);
        return readRecords(zone, recordScope, RecordFilter.NONE, null, null, null);
    }

    @PATCH
    @Path("/zones/{zoneNameOrId}/records/{domain}")
    public Response patchDomainRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                       @PathParam("domain") String domain, @HeaderParam("If-Match") String ifMatch,
                                       @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                       @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        RecordScope recordScope = new RecordScope(DnsService.trimDot(domain), null);
        StoredZone zone = service.patchRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                recordScope, items(body), ifMatch);
        return readRecords(zone, recordScope, RecordFilter.NONE, null, null, null);
    }

    @DELETE
    @Path("/zones/{zoneNameOrId}/records/{domain}")
    public Response deleteDomainRecords(@PathParam("zoneNameOrId") String zoneNameOrId,
                                        @PathParam("domain") String domain, @HeaderParam("If-Match") String ifMatch,
                                        @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                        @QueryParam("compartmentId") String compartmentId) {
        service.deleteRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                new RecordScope(domain, null), ifMatch);
        return Response.noContent().build();
    }

    @GET
    @Path("/zones/{zoneNameOrId}/records/{domain}/{rtype}")
    public Response getRRSet(@PathParam("zoneNameOrId") String zoneNameOrId, @PathParam("domain") String domain,
                             @PathParam("rtype") String rtype, @HeaderParam("If-None-Match") String ifNoneMatch,
                             @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                             @QueryParam("compartmentId") String compartmentId, @QueryParam("limit") Integer limit,
                             @QueryParam("page") String page) {
        StoredZone zone = service.getZone(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId));
        return readRecords(zone, rrset(domain, rtype), RecordFilter.NONE, ifNoneMatch, limit, page);
    }

    @PUT
    @Path("/zones/{zoneNameOrId}/records/{domain}/{rtype}")
    public Response updateRRSet(@PathParam("zoneNameOrId") String zoneNameOrId, @PathParam("domain") String domain,
                                @PathParam("rtype") String rtype, @HeaderParam("If-Match") String ifMatch,
                                @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        RecordScope recordScope = rrset(domain, rtype);
        StoredZone zone = service.updateRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                recordScope, items(body), ifMatch);
        return readRecords(zone, recordScope, RecordFilter.NONE, null, null, null);
    }

    @PATCH
    @Path("/zones/{zoneNameOrId}/records/{domain}/{rtype}")
    public Response patchRRSet(@PathParam("zoneNameOrId") String zoneNameOrId, @PathParam("domain") String domain,
                               @PathParam("rtype") String rtype, @HeaderParam("If-Match") String ifMatch,
                               @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                               @QueryParam("compartmentId") String compartmentId, Map<String, Object> body) {
        RecordScope recordScope = rrset(domain, rtype);
        StoredZone zone = service.patchRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId),
                recordScope, items(body), ifMatch);
        return readRecords(zone, recordScope, RecordFilter.NONE, null, null, null);
    }

    @DELETE
    @Path("/zones/{zoneNameOrId}/records/{domain}/{rtype}")
    public Response deleteRRSet(@PathParam("zoneNameOrId") String zoneNameOrId, @PathParam("domain") String domain,
                                @PathParam("rtype") String rtype, @HeaderParam("If-Match") String ifMatch,
                                @QueryParam("scope") String scope, @QueryParam("viewId") String viewId,
                                @QueryParam("compartmentId") String compartmentId) {
        service.deleteRecords(zoneNameOrId, new ZoneQuery(scope, viewId, compartmentId), new RecordScope(domain, rtype),
                ifMatch);
        return Response.noContent().build();
    }

    // ── Views ──────────────────────────────────────────────────────────────────

    @POST
    @Path("/views")
    public Response createView(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(DnsService.str(body, "compartmentId"))) {
            StoredView view = service.createView(body);
            String self = viewSelf(view);
            return Response.created(URI.create(self)).entity(viewJson(view, self)).header("etag", view.getEtag())
                    .build();
        }
    }

    @GET
    @Path("/views")
    public Response listViews(@QueryParam("compartmentId") String compartmentId,
                              @QueryParam("displayName") String displayName, @QueryParam("id") String id,
                              @QueryParam("lifecycleState") String lifecycleState,
                              @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                              @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredView view : service.listViews(compartmentId, displayName, id, lifecycleState, sortBy, sortOrder)) {
            items.add(viewJson(view, viewSelf(view)));
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/views/{viewId}")
    public Response getView(@PathParam("viewId") String viewId, @HeaderParam("If-None-Match") String ifNoneMatch) {
        StoredView view = service.getView(viewId);
        if (notModified(ifNoneMatch, view.getEtag())) {
            return Response.notModified().header("etag", view.getEtag()).build();
        }
        return Response.ok(viewJson(view, viewSelf(view))).header("etag", view.getEtag()).build();
    }

    @PUT
    @Path("/views/{viewId}")
    public Response updateView(@PathParam("viewId") String viewId, @HeaderParam("If-Match") String ifMatch,
                               Map<String, Object> body) {
        StoredView view = service.updateView(viewId, body, ifMatch);
        return Response.ok(viewJson(view, viewSelf(view))).header("etag", view.getEtag()).build();
    }

    @DELETE
    @Path("/views/{viewId}")
    public Response deleteView(@PathParam("viewId") String viewId, @HeaderParam("If-Match") String ifMatch) {
        service.deleteView(viewId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/views/{viewId}/actions/changeCompartment")
    public Response changeViewCompartment(@PathParam("viewId") String viewId, @HeaderParam("If-Match") String ifMatch,
                                          Map<String, Object> body) {
        String compartmentId = DnsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeViewCompartment(viewId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    // ── Resolvers ──────────────────────────────────────────────────────────────

    @GET
    @Path("/resolvers")
    public Response listResolvers(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("displayName") String displayName, @QueryParam("id") String id,
                                  @QueryParam("lifecycleState") String lifecycleState,
                                  @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                                  @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredResolver resolver : service.listResolvers(compartmentId, displayName, id, lifecycleState, sortBy,
                sortOrder)) {
            items.add(resolverJson(resolver, resolverSelf(resolver), false));
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/resolvers/{resolverId}")
    public Response getResolver(@PathParam("resolverId") String resolverId,
                                @HeaderParam("If-None-Match") String ifNoneMatch) {
        StoredResolver resolver = service.getResolver(resolverId);
        if (notModified(ifNoneMatch, resolver.getEtag())) {
            return Response.notModified().header("etag", resolver.getEtag()).build();
        }
        return Response.ok(resolverJson(resolver, resolverSelf(resolver), true)).header("etag", resolver.getEtag())
                .build();
    }

    @PUT
    @Path("/resolvers/{resolverId}")
    public Response updateResolver(@PathParam("resolverId") String resolverId, @HeaderParam("If-Match") String ifMatch,
                                   Map<String, Object> body) {
        StoredResolver resolver = service.updateResolver(resolverId, body, ifMatch);
        return Response.ok(resolverJson(resolver, resolverSelf(resolver), true)).header("etag", resolver.getEtag())
                .build();
    }

    @POST
    @Path("/resolvers/{resolverId}/actions/changeCompartment")
    public Response changeResolverCompartment(@PathParam("resolverId") String resolverId,
                                              @HeaderParam("If-Match") String ifMatch, Map<String, Object> body) {
        String compartmentId = DnsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeResolverCompartment(resolverId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    /** floci-oci emulates no resolver endpoints, so a resolver lists none. */
    @GET
    @Path("/resolvers/{resolverId}/endpoints")
    public Response listResolverEndpoints(@PathParam("resolverId") String resolverId) {
        service.getResolver(resolverId);
        return Response.ok(List.of()).build();
    }

    @POST
    @Path("/resolvers/{resolverId}/endpoints")
    public Response createResolverEndpoint(@PathParam("resolverId") String resolverId) {
        throw OciException.notImplemented("Resolver endpoints are not emulated");
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private Response readRecords(StoredZone zone, RecordScope scope, RecordFilter filter, String ifNoneMatch,
                                 Integer limit, String page) {
        if (notModified(ifNoneMatch, zone.getEtag())) {
            return Response.notModified().header("etag", zone.getEtag()).build();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredRecord record : service.records(zone, scope, filter)) {
            items.add(recordJson(record));
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        return paged(Response.ok(Map.of("items", result.items())), result, items.size())
                .header("etag", zone.getEtag()).build();
    }

    private static Response.ResponseBuilder paged(Response.ResponseBuilder builder, OciPage.Page<?> result, int total) {
        builder.header("opc-total-items", total);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder;
    }

    private static RecordScope rrset(String domain, String rtype) {
        return new RecordScope(DnsService.trimDot(domain), DnsRdata.rtype(rtype));
    }

    private static Object items(Map<String, Object> body) {
        return body == null ? null : body.get("items");
    }

    /** {@code If-None-Match} on a read: a match answers 304 instead of the body. */
    static boolean notModified(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if ("*".equals(value) || value.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private String zoneSelf(StoredZone zone) {
        return ociContext.baseUrl() + DnsService.API_PREFIX + "/zones/" + zone.getId();
    }

    private String resolverSelf(StoredResolver resolver) {
        return ociContext.baseUrl() + DnsService.API_PREFIX + "/resolvers/" + resolver.getId();
    }

    private String viewSelf(StoredView view) {
        return ociContext.baseUrl() + DnsService.API_PREFIX + "/views/" + view.getId();
    }

    /** Zone (zone.go); ZoneSummary (zone_summary.go) leaves out nameservers, masters and downstreams. */
    static Map<String, Object> zoneJson(StoredZone zone, String self, boolean full) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", zone.getName());
        json.put("zoneType", zone.getZoneType());
        json.put("compartmentId", zone.getCompartmentId());
        json.put("viewId", zone.getViewId());
        json.put("scope", zone.getScope());
        json.put("freeformTags", zone.getFreeformTags());
        json.put("definedTags", zone.getDefinedTags());
        json.put("resolutionMode", zone.getResolutionMode());
        json.put("dnssecState", zone.getDnssecState());
        if (full) {
            json.put("externalMasters", List.of());
            json.put("externalDownstreams", List.of());
        }
        json.put("self", self);
        json.put("id", zone.getId());
        json.put("timeCreated", zone.getTimeCreated());
        json.put("version", String.valueOf(zone.getSerial()));
        json.put("serial", zone.getSerial());
        json.put("lifecycleState", zone.getLifecycleState());
        json.put("isProtected", zone.isProtectedZone());
        if (full) {
            List<Map<String, Object>> nameservers = new ArrayList<>();
            for (String hostname : DnsService.NAMESERVERS) {
                nameservers.add(Map.of("hostname", hostname));
            }
            json.put("nameservers", nameservers);
            json.put("zoneTransferServers", List.of());
        }
        return json;
    }

    static Map<String, Object> recordJson(StoredRecord record) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("domain", record.getDomain());
        json.put("recordHash", record.getRecordHash());
        json.put("isProtected", record.isProtectedRecord());
        json.put("rdata", record.getRdata());
        json.put("rrsetVersion", record.getRrsetVersion());
        json.put("rtype", record.getRtype());
        json.put("ttl", record.getTtl());
        return json;
    }

    /** Resolver (resolver.go); ResolverSummary leaves out endpoints, attached views and rules. */
    static Map<String, Object> resolverJson(StoredResolver resolver, String self, boolean full) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", resolver.getCompartmentId());
        json.put("attachedVcnId", resolver.getAttachedVcnId());
        json.put("displayName", resolver.getDisplayName());
        json.put("freeformTags", resolver.getFreeformTags());
        json.put("definedTags", resolver.getDefinedTags());
        json.put("id", resolver.getId());
        json.put("timeCreated", resolver.getTimeCreated());
        json.put("timeUpdated", resolver.getTimeUpdated());
        json.put("lifecycleState", resolver.getLifecycleState());
        json.put("self", self);
        json.put("defaultViewId", resolver.getDefaultViewId());
        json.put("isProtected", true);
        if (full) {
            json.put("endpoints", List.of());
            List<Map<String, Object>> attached = new ArrayList<>();
            for (String viewId : resolver.getAttachedViewIds()) {
                attached.add(Map.of("viewId", viewId));
            }
            json.put("attachedViews", attached);
            json.put("rules", resolver.getRules());
        }
        return json;
    }

    static Map<String, Object> viewJson(StoredView view, String self) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", view.getCompartmentId());
        json.put("displayName", view.getDisplayName());
        json.put("freeformTags", view.getFreeformTags());
        json.put("definedTags", view.getDefinedTags());
        json.put("id", view.getId());
        json.put("self", self);
        json.put("timeCreated", view.getTimeCreated());
        json.put("timeUpdated", view.getTimeUpdated());
        json.put("lifecycleState", view.getLifecycleState());
        json.put("isProtected", view.isProtectedView());
        return json;
    }
}
