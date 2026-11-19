package io.floci.oci.services.events;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.events.model.StoredRule;
import io.floci.oci.services.identity.CompartmentValidator;
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
 * OCI Events, {@code /20181201/rules} (oci-go-sdk/events/events_client.go). Every operation is
 * synchronous: create, get and update return the rule with an {@code etag}; ListRules returns a
 * bare array of RuleSummary with {@code opc-next-page}. Shares the {@code /20181201} root with the
 * Functions controllers; no sub-path overlaps.
 */
@Path("/20181201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventsController {

    private final EventsService service;
    private final CompartmentValidator compartments;

    @Inject
    public EventsController(EventsService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    @POST
    @Path("/rules")
    public Response createRule(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(EventsService.str(body, "compartmentId"))) {
            StoredRule rule = service.createRule(body);
            return Response.ok(ruleJson(rule)).header("etag", rule.getEtag()).build();
        }
    }

    @GET
    @Path("/rules/{ruleId}")
    public Response getRule(@PathParam("ruleId") String ruleId) {
        StoredRule rule = service.getRule(ruleId);
        return Response.ok(ruleJson(rule)).header("etag", rule.getEtag()).build();
    }

    @GET
    @Path("/rules")
    public Response listRules(@QueryParam("compartmentId") String compartmentId,
                              @QueryParam("displayName") String displayName,
                              @QueryParam("lifecycleState") String lifecycleState,
                              @QueryParam("sortBy") String sortBy,
                              @QueryParam("sortOrder") String sortOrder,
                              @QueryParam("limit") Integer limit,
                              @QueryParam("page") String page) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> items = service.listRules(compartmentId, displayName, lifecycleState,
                sortBy, sortOrder).stream().map(EventsController::summaryFields).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/rules/{ruleId}")
    public Response updateRule(@PathParam("ruleId") String ruleId, @HeaderParam("if-match") String ifMatch,
                               Map<String, Object> body) {
        StoredRule rule = service.updateRule(ruleId, body, ifMatch);
        return Response.ok(ruleJson(rule)).header("etag", rule.getEtag()).build();
    }

    @DELETE
    @Path("/rules/{ruleId}")
    public Response deleteRule(@PathParam("ruleId") String ruleId, @HeaderParam("if-match") String ifMatch) {
        service.deleteRule(ruleId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/rules/{ruleId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("ruleId") String ruleId, @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        String compartmentId = EventsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            StoredRule rule = service.changeCompartment(ruleId, compartmentId, ifMatch);
            return Response.ok().header("etag", rule.getEtag()).build();
        }
    }

    static Map<String, Object> ruleJson(StoredRule rule) {
        Map<String, Object> json = summaryFields(rule);
        json.put("actions", Map.of("actions", rule.getActions()));
        json.put("lifecycleMessage", "");
        return json;
    }

    private static Map<String, Object> summaryFields(StoredRule rule) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", rule.getId());
        json.put("displayName", rule.getDisplayName());
        if (rule.getDescription() != null) {
            json.put("description", rule.getDescription());
        }
        json.put("lifecycleState", rule.getLifecycleState());
        json.put("condition", rule.getCondition());
        json.put("compartmentId", rule.getCompartmentId());
        json.put("isEnabled", rule.isEnabled());
        json.put("timeCreated", rule.getTimeCreated());
        json.put("freeformTags", rule.getFreeformTags());
        json.put("definedTags", rule.getDefinedTags());
        return json;
    }
}
