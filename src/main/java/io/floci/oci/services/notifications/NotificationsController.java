package io.floci.oci.services.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.notifications.model.StoredSubscription;
import io.floci.oci.services.notifications.model.StoredTopic;
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
 * OCI Notifications, {@code /20181201/topics} and {@code /20181201/subscriptions}
 * (oci-go-sdk ons/ons_notificationcontrolplane_client.go, ons_notificationdataplane_client.go).
 * ListTopics and ListSubscriptions return bare arrays with {@code opc-next-page}. Shares the
 * {@code /20181201} root with Functions and Events; no sub-path overlaps.
 */
@Path("/20181201")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationsController {

    private final NotificationsService service;
    private final CompartmentValidator compartments;
    private final ObjectMapper mapper;

    @Inject
    public NotificationsController(NotificationsService service, CompartmentValidator compartments,
                                   ObjectMapper mapper) {
        this.service = service;
        this.compartments = compartments;
        this.mapper = mapper;
    }

    // ── Topics ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/topics")
    public Response createTopic(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(NotificationsService.str(body, "compartmentId"))) {
            StoredTopic topic = service.createTopic(body);
            return Response.ok(topicJson(topic)).header("etag", topic.getEtag()).build();
        }
    }

    @GET
    @Path("/topics/{topicId}")
    public Response getTopic(@PathParam("topicId") String topicId) {
        StoredTopic topic = service.getTopic(topicId);
        return Response.ok(topicJson(topic)).header("etag", topic.getEtag()).build();
    }

    @GET
    @Path("/topics")
    public Response listTopics(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("id") String id,
                               @QueryParam("name") String name,
                               @QueryParam("lifecycleState") String lifecycleState,
                               @QueryParam("sortBy") String sortBy,
                               @QueryParam("sortOrder") String sortOrder,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = service.listTopics(compartmentId, id, name, lifecycleState, sortBy,
                sortOrder).stream().map(NotificationsController::topicJson).toList();
        return page(items, limit, page);
    }

    @PUT
    @Path("/topics/{topicId}")
    public Response updateTopic(@PathParam("topicId") String topicId, @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        StoredTopic topic = service.updateTopic(topicId, body, ifMatch);
        return Response.ok(topicJson(topic)).header("etag", topic.getEtag()).build();
    }

    @DELETE
    @Path("/topics/{topicId}")
    public Response deleteTopic(@PathParam("topicId") String topicId, @HeaderParam("if-match") String ifMatch) {
        service.deleteTopic(topicId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/topics/{topicId}/actions/changeCompartment")
    public Response changeTopicCompartment(@PathParam("topicId") String topicId,
                                           @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = NotificationsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeTopicCompartment(topicId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    @POST
    @Path("/topics/{topicId}/messages")
    public Response publishMessage(@PathParam("topicId") String topicId,
                                   @HeaderParam("messageType") String messageType, Map<String, Object> body) {
        NotificationsService.PublishResult result = service.publish(topicId, body, messageType);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("messageId", result.messageId());
        json.put("timeStamp", result.timeStamp());
        return Response.ok(json).build();
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    @POST
    @Path("/subscriptions")
    public Response createSubscription(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(NotificationsService.str(body, "compartmentId"))) {
            StoredSubscription subscription = service.createSubscription(body);
            return Response.ok(subscriptionJson(subscription)).header("etag", subscription.getEtag()).build();
        }
    }

    @GET
    @Path("/subscriptions/{subscriptionId}")
    public Response getSubscription(@PathParam("subscriptionId") String subscriptionId) {
        StoredSubscription subscription = service.getSubscription(subscriptionId);
        return Response.ok(subscriptionJson(subscription)).header("etag", subscription.getEtag()).build();
    }

    @GET
    @Path("/subscriptions")
    public Response listSubscriptions(@QueryParam("compartmentId") String compartmentId,
                                      @QueryParam("topicId") String topicId,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> items = service.listSubscriptions(compartmentId, topicId).stream()
                .map(NotificationsController::subscriptionSummaryJson).toList();
        return page(items, limit, page);
    }

    /** UpdateSubscription answers with UpdateSubscriptionDetails, not the Subscription. */
    @PUT
    @Path("/subscriptions/{subscriptionId}")
    public Response updateSubscription(@PathParam("subscriptionId") String subscriptionId,
                                       @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        StoredSubscription subscription = service.updateSubscription(subscriptionId, body, ifMatch);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("deliveryPolicy", subscription.getDeliveryPolicy());
        json.put("freeformTags", subscription.getFreeformTags());
        json.put("definedTags", subscription.getDefinedTags());
        return Response.ok(json).header("etag", subscription.getEtag()).build();
    }

    @DELETE
    @Path("/subscriptions/{subscriptionId}")
    public Response deleteSubscription(@PathParam("subscriptionId") String subscriptionId,
                                       @HeaderParam("if-match") String ifMatch) {
        service.deleteSubscription(subscriptionId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/subscriptions/{subscriptionId}/actions/changeCompartment")
    public Response changeSubscriptionCompartment(@PathParam("subscriptionId") String subscriptionId,
                                                  @HeaderParam("if-match") String ifMatch,
                                                  Map<String, Object> body) {
        String compartmentId = NotificationsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeSubscriptionCompartment(subscriptionId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    @GET
    @Path("/subscriptions/{id}/confirmation")
    public Response getConfirmSubscription(@PathParam("id") String id, @QueryParam("token") String token,
                                           @QueryParam("protocol") String protocol) {
        requireQuery(token, "token");
        requireQuery(protocol, "protocol");
        NotificationsService.Confirmation confirmation = service.confirmSubscription(id, token, protocol);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("topicName", confirmation.topic().getName());
        json.put("topicId", confirmation.topic().getTopicId());
        json.put("endpoint", confirmation.subscription().getEndpoint());
        json.put("unsubscribeUrl", confirmation.unsubscribeUrl());
        json.put("message", "You have successfully subscribed to the topic " + confirmation.topic().getName() + ".");
        json.put("subscriptionId", confirmation.subscription().getId());
        return Response.ok(json).header("etag", confirmation.subscription().getEtag()).build();
    }

    /** GetUnsubscription answers with a bare JSON string (oci-go-sdk decodes {@code Value *string} as JSON). */
    @GET
    @Path("/subscriptions/{id}/unsubscription")
    public Response getUnsubscription(@PathParam("id") String id, @QueryParam("token") String token,
                                      @QueryParam("protocol") String protocol) {
        requireQuery(token, "token");
        requireQuery(protocol, "protocol");
        String message = service.unsubscribe(id, token, protocol);
        try {
            return Response.ok(mapper.writeValueAsString(message), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (JsonProcessingException e) {
            throw OciException.internalServerError("Could not render the unsubscription result: " + e.getMessage());
        }
    }

    @POST
    @Path("/subscriptions/{id}/resendConfirmation")
    @Consumes(MediaType.WILDCARD)
    public Response resendSubscriptionConfirmation(@PathParam("id") String id) {
        StoredSubscription subscription = service.resendConfirmation(id);
        return Response.ok(subscriptionJson(subscription)).build();
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private static void requireQuery(String value, String name) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter(name + " is required");
        }
    }

    private static Response page(List<Map<String, Object>> items, Integer limit, String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(items, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    static Map<String, Object> topicJson(StoredTopic topic) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", topic.getName());
        json.put("topicId", topic.getTopicId());
        if (topic.getShortTopicId() != null) {
            json.put("shortTopicId", topic.getShortTopicId());
        }
        json.put("compartmentId", topic.getCompartmentId());
        json.put("lifecycleState", topic.getLifecycleState());
        if (topic.getDescription() != null) {
            json.put("description", topic.getDescription());
        }
        json.put("timeCreated", topic.getTimeCreated());
        json.put("apiEndpoint", topic.getApiEndpoint());
        json.put("etag", topic.getEtag());
        json.put("freeformTags", topic.getFreeformTags());
        json.put("definedTags", topic.getDefinedTags());
        return json;
    }

    private static Map<String, Object> subscriptionFields(StoredSubscription subscription) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", subscription.getId());
        json.put("topicId", subscription.getTopicId());
        json.put("protocol", subscription.getProtocol());
        json.put("endpoint", subscription.getEndpoint());
        json.put("lifecycleState", subscription.getLifecycleState());
        json.put("compartmentId", subscription.getCompartmentId());
        json.put("createdTime", subscription.getCreatedTime());
        return json;
    }

    /** Subscription carries the delivery policy as a JSON string in {@code deliverPolicy} (ons/subscription.go). */
    Map<String, Object> subscriptionJson(StoredSubscription subscription) {
        Map<String, Object> json = subscriptionFields(subscription);
        try {
            json.put("deliverPolicy", mapper.writeValueAsString(subscription.getDeliveryPolicy()));
        } catch (JsonProcessingException e) {
            throw OciException.internalServerError("Could not render the delivery policy: " + e.getMessage());
        }
        json.put("etag", subscription.getEtag());
        json.put("freeformTags", subscription.getFreeformTags());
        json.put("definedTags", subscription.getDefinedTags());
        return json;
    }

    /** SubscriptionSummary carries it as an object in {@code deliveryPolicy} (ons/subscription_summary.go). */
    static Map<String, Object> subscriptionSummaryJson(StoredSubscription subscription) {
        Map<String, Object> json = subscriptionFields(subscription);
        json.put("deliveryPolicy", subscription.getDeliveryPolicy());
        json.put("etag", subscription.getEtag());
        json.put("freeformTags", subscription.getFreeformTags());
        json.put("definedTags", subscription.getDefinedTags());
        return json;
    }
}
