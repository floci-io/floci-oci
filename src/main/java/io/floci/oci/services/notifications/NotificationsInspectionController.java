package io.floci.oci.services.notifications;

import io.floci.oci.services.notifications.model.DeliveryRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * floci-oci's own inspection endpoint for Notifications, not an OCI API: every message sent to a
 * subscription endpoint, newest last. EMAIL, SLACK, PAGERDUTY and SMS messages, confirmation
 * messages included, are only recorded here and never sent.
 */
@Path("{prefix:(_floci-oci|_floci)}/notifications/messages")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class NotificationsInspectionController {

    private final NotificationDispatcher dispatcher;

    @Inject
    public NotificationsInspectionController(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GET
    public Response messages(@QueryParam("topicId") String topicId,
                             @QueryParam("subscriptionId") String subscriptionId,
                             @QueryParam("protocol") String protocol) {
        List<DeliveryRecord> records = dispatcher.records(topicId, subscriptionId, protocol);
        return Response.ok(Map.of("messages", records)).build();
    }

    @DELETE
    public Response clear() {
        dispatcher.clearRecords();
        return Response.noContent().build();
    }
}
