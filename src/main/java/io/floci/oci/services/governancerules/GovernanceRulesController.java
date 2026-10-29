package io.floci.oci.services.governancerules;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.governancerules.model.StoredGovernanceRule;
import io.floci.oci.services.governancerules.model.StoredInclusionCriterion;
import io.floci.oci.services.governancerules.model.StoredTenancyAttachment;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCI Governance Rules control plane at {@code /20220504}. Lists are {@code {"items":[…]}}
 * collections paged with {@code opc-next-page}. Creates return the resource with an
 * {@code etag} and an {@code opc-work-request-id}; updates, deletes and retries return
 * {@code 202} with an {@code opc-work-request-id} and no body.
 */
@Path("/20220504")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GovernanceRulesController {

    private static final Set<String> WORK_REQUEST_STATUSES =
            Set.of("ACCEPTED", "IN_PROGRESS", "WAITING", "FAILED", "SUCCEEDED", "CANCELING", "CANCELED");

    private final GovernanceRulesService service;
    private final WorkRequestService workRequests;

    @Inject
    public GovernanceRulesController(GovernanceRulesService service, WorkRequestService workRequests) {
        this.service = service;
        this.workRequests = workRequests;
    }

    // ── Governance rules ───────────────────────────────────────────────────────

    @POST
    @Path("/governanceRules")
    public Response createGovernanceRule(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        GovernanceRulesService.Created<StoredGovernanceRule> created = service.createGovernanceRule(
                str(details, "compartmentId"), str(details, "displayName"), str(details, "description"),
                str(details, "type"), str(details, "creationOption"), map(details, "template"),
                str(details, "relatedResourceId"), freeformTags(details), definedTags(details));
        StoredGovernanceRule rule = created.resource();
        return Response.ok(rule.toWire())
                .header("etag", rule.getEtag())
                .header("opc-work-request-id", created.workRequestId())
                .header("location", "/20220504/governanceRules/" + rule.getId())
                .build();
    }

    @GET
    @Path("/governanceRules/{governanceRuleId}")
    public Response getGovernanceRule(@PathParam("governanceRuleId") String id) {
        StoredGovernanceRule rule = service.getGovernanceRule(id);
        return Response.ok(rule.toWire()).header("etag", rule.getEtag()).build();
    }

    @GET
    @Path("/governanceRules")
    public Response listGovernanceRules(@QueryParam("compartmentId") String compartmentId,
                                        @QueryParam("governanceRuleId") String governanceRuleId,
                                        @QueryParam("lifecycleState") String lifecycleState,
                                        @QueryParam("displayName") String displayName,
                                        @QueryParam("governanceRuleType") String governanceRuleType,
                                        @QueryParam("sortBy") String sortBy,
                                        @QueryParam("sortOrder") String sortOrder,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        return collection(service.listGovernanceRules(compartmentId, governanceRuleId, lifecycleState, displayName,
                governanceRuleType, sortBy, sortOrder).stream().map(StoredGovernanceRule::toSummaryWire).toList(),
                limit, page);
    }

    @PUT
    @Path("/governanceRules/{governanceRuleId}")
    public Response updateGovernanceRule(@PathParam("governanceRuleId") String id,
                                         @HeaderParam("if-match") String ifMatch,
                                         Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        return accepted(service.updateGovernanceRule(id, str(details, "displayName"), str(details, "description"),
                map(details, "template"), str(details, "relatedResourceId"), freeformTags(details),
                definedTags(details), ifMatch));
    }

    @DELETE
    @Path("/governanceRules/{governanceRuleId}")
    public Response deleteGovernanceRule(@PathParam("governanceRuleId") String id,
                                         @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteGovernanceRule(id, ifMatch));
    }

    @POST
    @Path("/governanceRules/{governanceRuleId}/actions/retry")
    @Consumes(MediaType.WILDCARD)
    public Response retryGovernanceRule(@PathParam("governanceRuleId") String id,
                                        @HeaderParam("if-match") String ifMatch) {
        return accepted(service.retryGovernanceRule(id, ifMatch));
    }

    // ── Inclusion criteria ─────────────────────────────────────────────────────

    @POST
    @Path("/inclusionCriteria")
    public Response createInclusionCriterion(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        Map<String, Object> association = map(details, "association");
        GovernanceRulesService.Created<StoredInclusionCriterion> created = service.createInclusionCriterion(
                str(details, "governanceRuleId"), str(details, "type"),
                association != null ? str(association, "type") : null,
                association != null ? str(association, "tenancyId") : null);
        StoredInclusionCriterion criterion = created.resource();
        return Response.ok(criterion.toWire())
                .header("etag", criterion.getEtag())
                .header("opc-work-request-id", created.workRequestId())
                .header("location", "/20220504/inclusionCriteria/" + criterion.getId())
                .build();
    }

    @GET
    @Path("/inclusionCriteria/{inclusionCriterionId}")
    public Response getInclusionCriterion(@PathParam("inclusionCriterionId") String id) {
        StoredInclusionCriterion criterion = service.getInclusionCriterion(id);
        return Response.ok(criterion.toWire()).header("etag", criterion.getEtag()).build();
    }

    @GET
    @Path("/inclusionCriteria")
    public Response listInclusionCriteria(@QueryParam("governanceRuleId") String governanceRuleId,
                                          @QueryParam("inclusionCriterionId") String inclusionCriterionId,
                                          @QueryParam("lifecycleState") String lifecycleState,
                                          @QueryParam("sortBy") String sortBy,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        return collection(service.listInclusionCriteria(governanceRuleId, inclusionCriterionId, lifecycleState,
                sortBy, sortOrder).stream().map(StoredInclusionCriterion::toWire).toList(), limit, page);
    }

    @DELETE
    @Path("/inclusionCriteria/{inclusionCriterionId}")
    public Response deleteInclusionCriterion(@PathParam("inclusionCriterionId") String id,
                                             @HeaderParam("if-match") String ifMatch) {
        return accepted(service.deleteInclusionCriterion(id, ifMatch));
    }

    // ── Tenancy attachments ────────────────────────────────────────────────────

    @GET
    @Path("/tenancyAttachments/{tenancyAttachmentId}")
    public Response getTenancyAttachment(@PathParam("tenancyAttachmentId") String id) {
        StoredTenancyAttachment attachment = service.getTenancyAttachment(id);
        return Response.ok(attachment.toWire()).header("etag", attachment.getEtag()).build();
    }

    @GET
    @Path("/tenancyAttachments")
    public Response listTenancyAttachments(@QueryParam("compartmentId") String compartmentId,
                                           @QueryParam("tenancyAttachmentId") String tenancyAttachmentId,
                                           @QueryParam("governanceRuleId") String governanceRuleId,
                                           @QueryParam("lifecycleState") String lifecycleState,
                                           @QueryParam("childTenancyId") String childTenancyId,
                                           @QueryParam("sortBy") String sortBy,
                                           @QueryParam("sortOrder") String sortOrder,
                                           @QueryParam("limit") Integer limit,
                                           @QueryParam("page") String page) {
        return collection(service.listTenancyAttachments(compartmentId, tenancyAttachmentId, governanceRuleId,
                lifecycleState, childTenancyId, sortBy, sortOrder).stream()
                .map(StoredTenancyAttachment::toWire).toList(), limit, page);
    }

    @POST
    @Path("/tenancyAttachments/{tenancyAttachmentId}/actions/retry")
    @Consumes(MediaType.WILDCARD)
    public Response retryTenancyAttachment(@PathParam("tenancyAttachmentId") String id,
                                           @HeaderParam("if-match") String ifMatch) {
        return accepted(service.retryTenancyAttachment(id, ifMatch));
    }

    // ── Enforced governance rules ──────────────────────────────────────────────

    @GET
    @Path("/enforcedGovernanceRules/{enforcedGovernanceRuleId}")
    public Response getEnforcedGovernanceRule(@PathParam("enforcedGovernanceRuleId") String id) {
        GovernanceRulesService.Enforced enforced = service.getEnforcedGovernanceRule(id);
        return Response.ok(enforced.wire()).header("etag", enforced.etag()).build();
    }

    @GET
    @Path("/enforcedGovernanceRules")
    public Response listEnforcedGovernanceRules(@QueryParam("compartmentId") String compartmentId,
                                                @QueryParam("enforcedGovernanceRuleId") String enforcedGovernanceRuleId,
                                                @QueryParam("governanceRuleType") String governanceRuleType,
                                                @QueryParam("displayName") String displayName,
                                                @QueryParam("sortBy") String sortBy,
                                                @QueryParam("sortOrder") String sortOrder,
                                                @QueryParam("limit") Integer limit,
                                                @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listEnforcedGovernanceRules(compartmentId,
                enforcedGovernanceRuleId, governanceRuleType, displayName, sortBy, sortOrder).stream()
                .map(GovernanceRulesController::withoutTemplate).toList();
        return collection(summaries, limit, page);
    }

    // ── Work requests ──────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        return Response.ok(workRequests.get(GovernanceRulesService.WR_SERVICE, workRequestId).toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("workRequestId") String workRequestId,
                                     @QueryParam("status") String status,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        GovernanceRulesService.requireEnum(status, WORK_REQUEST_STATUSES, "status");
        requireWorkRequestSort(sortBy, sortOrder);
        Comparator<StoredWorkRequest> order = Comparator.comparing(StoredWorkRequest::getTimeAccepted)
                .thenComparing(StoredWorkRequest::getId);
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return collection(workRequests.list(GovernanceRulesService.WR_SERVICE, compartmentId).stream()
                .filter(wr -> workRequestId == null || workRequestId.equals(wr.getId()))
                .filter(wr -> status == null || status.equals(wr.getStatus()))
                .filter(wr -> resourceId == null || wr.getResources().stream()
                        .anyMatch(r -> resourceId.equals(r.getIdentifier())))
                .sorted(order).map(StoredWorkRequest::toWire).toList(), limit, page);
    }

    @DELETE
    @Path("/workRequests/{workRequestId}")
    public Response cancelWorkRequest(@PathParam("workRequestId") String workRequestId,
                                      @HeaderParam("if-match") String ifMatch) {
        StoredWorkRequest wr = workRequests.get(GovernanceRulesService.WR_SERVICE, workRequestId);
        throw OciException.conflict("Work request " + wr.getId() + " is " + wr.getStatus() + " and cannot be canceled");
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId,
                                          @QueryParam("sortBy") String sortBy,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("limit") Integer limit,
                                          @QueryParam("page") String page) {
        requireWorkRequestSort(sortBy, sortOrder);
        workRequests.get(GovernanceRulesService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId,
                                        @QueryParam("sortBy") String sortBy,
                                        @QueryParam("sortOrder") String sortOrder,
                                        @QueryParam("limit") Integer limit,
                                        @QueryParam("page") String page) {
        requireWorkRequestSort(sortBy, sortOrder);
        workRequests.get(GovernanceRulesService.WR_SERVICE, workRequestId);
        return collection(List.of(), limit, page);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void requireWorkRequestSort(String sortBy, String sortOrder) {
        GovernanceRulesService.requireEnum(sortBy, Set.of("timeAccepted"), "sortBy");
        GovernanceRulesService.requireEnum(sortOrder, GovernanceRulesService.SORT_ORDERS, "sortOrder");
    }

    private static Map<String, Object> withoutTemplate(Map<String, Object> enforced) {
        Map<String, Object> summary = new LinkedHashMap<>(enforced);
        summary.remove("template");
        return summary;
    }

    private static Response accepted(String workRequestId) {
        return Response.accepted().header("opc-work-request-id", workRequestId).build();
    }

    private static Response collection(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected a string");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw OciException.invalidParameter("Invalid " + key + ": expected an object");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> freeformTags(Map<String, Object> body) {
        return body.get("freeformTags") instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body.get("definedTags") instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : null;
    }
}
