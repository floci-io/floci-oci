package io.floci.oci.services.monitoring;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.monitoring.model.StoredAlarm;
import io.floci.oci.services.monitoring.model.StoredAlarmSuppression;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OCI Monitoring, {@code /20180401} (oci-go-sdk monitoring/monitoring_client.go). PostMetricData is
 * served on the same port as the rest; real OCI sends it to the {@code telemetry-ingestion} host.
 * ListAlarms, ListAlarmsStatus, ListMetrics and SummarizeMetricsData return bare arrays;
 * ListAlarmSuppressions returns an {@code {"items": [...]}} collection.
 */
@Path("/20180401")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MonitoringController {

    private static final List<String> ALARM_SUMMARY_FIELDS = List.of("displayName", "compartmentId",
            "metricCompartmentId", "namespace", "query", "severity", "destinations", "isEnabled", "suppression",
            "isNotificationsPerMetricDimensionEnabled", "freeformTags", "definedTags", "overrides", "ruleName",
            "notificationVersion", "notificationTitle", "evaluationSlackDuration", "alarmSummary", "resourceGroup");

    private final MonitoringService service;
    private final AlarmEvaluator evaluator;
    private final CompartmentValidator compartments;

    @Inject
    public MonitoringController(MonitoringService service, AlarmEvaluator evaluator, CompartmentValidator compartments) {
        this.service = service;
        this.evaluator = evaluator;
        this.compartments = compartments;
    }

    // ── Metrics ────────────────────────────────────────────────────────────────

    @POST
    @Path("/metrics")
    public Response postMetricData(Map<String, Object> body) {
        List<MonitoringService.FailedMetric> failed = service.postMetricData(body, compartments::isKnown);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("failedMetricsCount", failed.size());
        json.put("failedMetrics", failed.stream().map(f -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("message", f.message());
            record.put("metricData", f.metricData());
            return record;
        }).toList());
        return Response.ok(json).build();
    }

    @POST
    @Path("/metrics/actions/summarizeMetricsData")
    public Response summarizeMetricsData(@QueryParam("compartmentId") String compartmentId,
                                         @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                                         Map<String, Object> body) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MonitoringService.SeriesResult series : service.summarizeMetricsData(compartmentId, inSubtree, body)) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("namespace", series.sample().getNamespace());
            json.put("compartmentId", series.sample().getCompartmentId());
            json.put("name", series.sample().getName());
            json.put("dimensions", series.dimensions());
            if (series.sample().getResourceGroup() != null) {
                json.put("resourceGroup", series.sample().getResourceGroup());
            }
            json.put("metadata", series.sample().getMetadata());
            json.put("resolution", series.resolution());
            List<Map<String, Object>> points = new ArrayList<>();
            series.points().forEach((t, v) -> {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("timestamp", Instant.ofEpochMilli(t).toString());
                point.put("value", v);
                points.add(point);
            });
            json.put("aggregatedDatapoints", points);
            items.add(json);
        }
        return Response.ok(items).build();
    }

    @POST
    @Path("/metrics/actions/listMetrics")
    @Consumes(MediaType.WILDCARD)
    public Response listMetrics(@QueryParam("compartmentId") String compartmentId,
                                @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                                @QueryParam("limit") Integer limit, @QueryParam("page") String page,
                                Map<String, Object> body) {
        requireCompartment(compartmentId);
        return page(service.listMetrics(compartmentId, inSubtree, body), limit, page);
    }

    // ── Alarms ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/alarms")
    public Response createAlarm(Map<String, Object> body) {
        compartments.requireInBody(MonitoringService.str(body, "metricCompartmentId"));
        try (CompartmentValidator.Use ignored = compartments.useInBody(MonitoringService.str(body, "compartmentId"))) {
            StoredAlarm alarm = service.createAlarm(body);
            return Response.ok(alarmJson(alarm)).header("etag", alarm.getEtag()).build();
        }
    }

    @GET
    @Path("/alarms/{alarmId}")
    public Response getAlarm(@PathParam("alarmId") String alarmId) {
        StoredAlarm alarm = service.getAlarm(alarmId);
        return Response.ok(alarmJson(alarm)).header("etag", alarm.getEtag()).build();
    }

    @GET
    @Path("/alarms")
    public Response listAlarms(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                               @QueryParam("displayName") String displayName,
                               @QueryParam("lifecycleState") String lifecycleState,
                               @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                               @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = service.listAlarms(compartmentId, inSubtree, displayName, lifecycleState,
                sortBy, sortOrder).stream().map(MonitoringController::alarmSummaryJson).toList();
        return page(items, limit, page);
    }

    @PUT
    @Path("/alarms/{alarmId}")
    public Response updateAlarm(@PathParam("alarmId") String alarmId, @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        String compartmentId = body == null ? null : MonitoringService.str(body, "compartmentId");
        if (body != null) {
            compartments.requireInBody(MonitoringService.str(body, "metricCompartmentId"));
        }
        if (compartmentId == null) {
            StoredAlarm alarm = service.updateAlarm(alarmId, body, ifMatch);
            return Response.ok(alarmJson(alarm)).header("etag", alarm.getEtag()).build();
        }
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            StoredAlarm alarm = service.updateAlarm(alarmId, body, ifMatch);
            return Response.ok(alarmJson(alarm)).header("etag", alarm.getEtag()).build();
        }
    }

    @DELETE
    @Path("/alarms/{alarmId}")
    public Response deleteAlarm(@PathParam("alarmId") String alarmId, @HeaderParam("if-match") String ifMatch) {
        service.deleteAlarm(alarmId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/alarms/{alarmId}/actions/changeCompartment")
    public Response changeAlarmCompartment(@PathParam("alarmId") String alarmId,
                                           @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = MonitoringService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeAlarmCompartment(alarmId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    @POST
    @Path("/alarms/{alarmId}/actions/removeSuppression")
    @Consumes(MediaType.WILDCARD)
    public Response removeAlarmSuppression(@PathParam("alarmId") String alarmId,
                                           @HeaderParam("if-match") String ifMatch) {
        service.removeAlarmSuppression(alarmId, ifMatch);
        return Response.ok().build();
    }

    @GET
    @Path("/alarms/status")
    public Response listAlarmsStatus(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                                     @QueryParam("displayName") String displayName,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("status") String status,
                                     @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredAlarm alarm : service.listAlarms(compartmentId, inSubtree, displayName, null, null, null)) {
            if (resourceId != null && alarm.getStreams().values().stream()
                    .noneMatch(s -> resourceId.equals(MonitoringService.asMap(s.get("dimensions")).get("resourceId")))) {
                continue;
            }
            String current = evaluator.suspended(alarm, now) ? "SUSPENDED" : alarm.getStatus();
            if (status != null && !status.equals(current)) {
                continue;
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", alarm.getId());
            json.put("displayName", alarm.getSpec().get("displayName"));
            json.put("severity", Objects.requireNonNullElse(alarm.getFiringSeverity(), alarm.getSpec().get("severity")));
            json.put("ruleName", Objects.requireNonNullElse(alarm.getFiringRuleName(),
                    alarm.getSpec().getOrDefault("ruleName", "BASE")));
            json.put("timestampTriggered", alarm.getTimestampTriggered());
            json.put("alarmSummary", AlarmEvaluator.summary(alarm));
            json.put("status", current);
            if (alarm.getSpec().get("suppression") != null) {
                json.put("suppression", alarm.getSpec().get("suppression"));
            }
            items.add(json);
        }
        Comparator<Map<String, Object>> order = "severity".equals(sortBy)
                ? Comparator.comparingInt(m -> MonitoringService.severityRank((String) m.get("severity")))
                : Comparator.comparing(m -> String.valueOf(m.get("displayName")));
        if ("DESC".equals(sortOrder)) {
            order = order.reversed();
        }
        return page(items.stream().sorted(order).toList(), limit, page);
    }

    @GET
    @Path("/alarms/{alarmId}/history")
    public Response getAlarmHistory(@PathParam("alarmId") String alarmId,
                                    @QueryParam("alarmHistorytype") String type,
                                    @QueryParam("timestampGreaterThanOrEqualTo") String from,
                                    @QueryParam("timestampLessThan") String until,
                                    @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        StoredAlarm alarm = service.getAlarm(alarmId);
        Instant start = from == null ? null : MonitoringService.parseTime(from, "timestampGreaterThanOrEqualTo");
        Instant end = until == null ? null : MonitoringService.parseTime(until, "timestampLessThan");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> entry : alarm.getHistory()) {
            Instant at = Instant.parse(String.valueOf(entry.get("timestamp")));
            if ((start == null || !at.isBefore(start)) && (end == null || at.isBefore(end))) {
                entries.add(entry);
            }
        }
        // Newest first.
        List<Map<String, Object>> newest = new ArrayList<>(entries);
        Collections.reverse(newest);
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(newest, limit, page);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("alarmId", alarm.getId());
        json.put("isEnabled", alarm.getSpec().get("isEnabled"));
        json.put("entries", result.items());
        Response.ResponseBuilder builder = Response.ok(json);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @POST
    @Path("/alarms/{alarmId}/actions/retrieveDimensionStates")
    @Consumes(MediaType.WILDCARD)
    public Response retrieveDimensionStates(@PathParam("alarmId") String alarmId,
                                            @QueryParam("limit") Integer limit, @QueryParam("page") String page,
                                            Map<String, Object> body) {
        StoredAlarm alarm = service.getAlarm(alarmId);
        Map<String, Object> filters = MonitoringService.asMap(body == null ? null : body.get("dimensionFilters"));
        String status = body == null ? null : MonitoringService.str(body, "status");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> stream : alarm.getStreams().values()) {
            Map<String, Object> dimensions = MonitoringService.asMap(stream.get("dimensions"));
            if (status != null && !status.equals(stream.get("status"))
                    || !filters.entrySet().stream().allMatch(f -> Objects.equals(f.getValue(), dimensions.get(f.getKey())))) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("alarmSummary", AlarmEvaluator.summary(alarm));
            entry.put("dimensions", dimensions);
            entry.put("status", stream.get("status"));
            entry.put("ruleName", stream.get("ruleName"));
            entry.put("timestamp", stream.get("timestamp"));
            items.add(entry);
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("alarmId", alarm.getId());
        json.put("isEnabled", alarm.getSpec().get("isEnabled"));
        json.put("isNotificationsPerMetricDimensionEnabled",
                Boolean.TRUE.equals(alarm.getSpec().get("isNotificationsPerMetricDimensionEnabled")));
        json.put("items", result.items());
        Response.ResponseBuilder builder = Response.ok(json);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @POST
    @Path("/alarms/{alarmId}/actions/summarizeAlarmSuppressionHistory")
    @Consumes(MediaType.WILDCARD)
    public Response summarizeAlarmSuppressionHistory(@PathParam("alarmId") String alarmId,
                                                     @QueryParam("limit") Integer limit,
                                                     @QueryParam("page") String page, Map<String, Object> body) {
        StoredAlarm alarm = service.getAlarm(alarmId);
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, Object> dimensions = MonitoringService.asMap(b.get("dimensions"));
        Instant fromAtLeast = b.get("timeSuppressFromGreaterThanOrEqualTo") instanceof String s
                ? MonitoringService.parseTime(s, "timeSuppressFromGreaterThanOrEqualTo") : null;
        Instant fromBefore = b.get("timeSuppressFromLessThan") instanceof String s
                ? MonitoringService.parseTime(s, "timeSuppressFromLessThan") : null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredAlarmSuppression suppression : service.suppressionStore().scan(k -> true)) {
            if (!service.targets(suppression, alarm)) {
                continue;
            }
            Map<String, Object> spec = suppression.getSpec();
            Instant from = Instant.parse(String.valueOf(spec.get("timeSuppressFrom")));
            Map<String, Object> suppressed = MonitoringService.asMap(spec.get("dimensions"));
            if (fromAtLeast != null && from.isBefore(fromAtLeast) || fromBefore != null && !from.isBefore(fromBefore)
                    || !dimensions.entrySet().stream()
                    .allMatch(d -> Objects.equals(d.getValue(), suppressed.get(d.getKey())))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("suppressionId", suppression.getId());
            item.put("alarmSuppressionTarget", spec.get("alarmSuppressionTarget"));
            item.put("level", spec.get("level"));
            item.put("displayName", spec.get("displayName"));
            putIfPresent(item, spec, "description");
            putIfPresent(item, spec, "dimensions");
            item.put("timeEffectiveFrom", spec.get("timeSuppressFrom"));
            item.put("timeEffectiveUntil", spec.get("timeSuppressUntil"));
            putIfPresent(item, spec, "suppressionConditions");
            items.add(item);
        }
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    // ── Alarm suppressions ─────────────────────────────────────────────────────

    @POST
    @Path("/alarmSuppressions")
    public Response createAlarmSuppression(Map<String, Object> body) {
        Map<String, Object> target = MonitoringService.asMap(body == null ? null : body.get("alarmSuppressionTarget"));
        if (!"COMPARTMENT".equals(MonitoringService.str(target, "targetType"))) {
            StoredAlarmSuppression suppression = service.createAlarmSuppression(body);
            return Response.ok(suppressionJson(suppression)).header("etag", suppression.getEtag()).build();
        }
        try (CompartmentValidator.Use ignored = compartments.useInBody(MonitoringService.str(target, "compartmentId"))) {
            StoredAlarmSuppression suppression = service.createAlarmSuppression(body);
            return Response.ok(suppressionJson(suppression)).header("etag", suppression.getEtag()).build();
        }
    }

    @GET
    @Path("/alarmSuppressions/{alarmSuppressionId}")
    public Response getAlarmSuppression(@PathParam("alarmSuppressionId") String id) {
        StoredAlarmSuppression suppression = service.getAlarmSuppression(id);
        return Response.ok(suppressionJson(suppression)).header("etag", suppression.getEtag()).build();
    }

    @GET
    @Path("/alarmSuppressions")
    public Response listAlarmSuppressions(@QueryParam("alarmId") String alarmId,
                                          @QueryParam("compartmentId") String compartmentId,
                                          @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                                          @QueryParam("displayName") String displayName,
                                          @QueryParam("lifecycleState") String lifecycleState,
                                          @QueryParam("level") String level,
                                          @QueryParam("targetType") String targetType,
                                          @QueryParam("isAllSuppressions") Boolean isAllSuppressions,
                                          @QueryParam("sortBy") String sortBy,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("limit") Integer limit, @QueryParam("page") String page) {
        List<Map<String, Object>> items = service.listAlarmSuppressions(alarmId, compartmentId, inSubtree,
                displayName, lifecycleState, level, targetType, isAllSuppressions, sortBy, sortOrder).stream()
                .map(MonitoringController::suppressionJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(Map.of("items", result.items()));
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @DELETE
    @Path("/alarmSuppressions/{alarmSuppressionId}")
    public Response deleteAlarmSuppression(@PathParam("alarmSuppressionId") String id,
                                           @HeaderParam("if-match") String ifMatch) {
        service.deleteAlarmSuppression(id, ifMatch);
        return Response.noContent().build();
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private static Response page(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    private static void putIfPresent(Map<String, Object> json, Map<String, Object> spec, String field) {
        if (spec.get(field) != null) {
            json.put(field, spec.get(field));
        }
    }

    static Map<String, Object> alarmJson(StoredAlarm alarm) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", alarm.getId());
        json.putAll(alarm.getSpec());
        json.put("lifecycleState", alarm.getLifecycleState());
        json.put("timeCreated", alarm.getTimeCreated());
        json.put("timeUpdated", alarm.getTimeUpdated());
        return json;
    }

    static Map<String, Object> alarmSummaryJson(StoredAlarm alarm) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", alarm.getId());
        for (String field : ALARM_SUMMARY_FIELDS) {
            putIfPresent(json, alarm.getSpec(), field);
        }
        json.put("lifecycleState", alarm.getLifecycleState());
        return json;
    }

    static Map<String, Object> suppressionJson(StoredAlarmSuppression suppression) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", suppression.getId());
        json.put("compartmentId", suppression.getCompartmentId());
        json.putAll(suppression.getSpec());
        json.put("lifecycleState", suppression.getLifecycleState());
        json.put("timeCreated", suppression.getTimeCreated());
        json.put("timeUpdated", suppression.getTimeUpdated());
        return json;
    }
}
