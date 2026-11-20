package io.floci.oci.compat;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.ons.NotificationControlPlaneClient;
import com.oracle.bmc.ons.NotificationDataPlaneClient;
import com.oracle.bmc.ons.model.ConfirmationResult;
import com.oracle.bmc.ons.model.CreateSubscriptionDetails;
import com.oracle.bmc.ons.model.CreateTopicDetails;
import com.oracle.bmc.ons.model.MessageDetails;
import com.oracle.bmc.ons.model.NotificationTopic;
import com.oracle.bmc.ons.model.Subscription;
import com.oracle.bmc.ons.model.SubscriptionSummary;
import com.oracle.bmc.ons.model.TopicAttributesDetails;
import com.oracle.bmc.ons.requests.CreateSubscriptionRequest;
import com.oracle.bmc.ons.requests.CreateTopicRequest;
import com.oracle.bmc.ons.requests.DeleteTopicRequest;
import com.oracle.bmc.ons.requests.GetConfirmSubscriptionRequest;
import com.oracle.bmc.ons.requests.GetSubscriptionRequest;
import com.oracle.bmc.ons.requests.GetTopicRequest;
import com.oracle.bmc.ons.requests.GetUnsubscriptionRequest;
import com.oracle.bmc.ons.requests.ListSubscriptionsRequest;
import com.oracle.bmc.ons.requests.ListTopicsRequest;
import com.oracle.bmc.ons.requests.PublishMessageRequest;
import com.oracle.bmc.ons.requests.UpdateTopicRequest;
import com.oracle.bmc.ons.responses.PublishMessageResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.oci.compat.EmulatorFixture.ENDPOINT;
import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validates Notifications topics, subscriptions, confirmation and publish via oci-java-sdk. */
class NotificationsCompatibilityTest {

    private static final Pattern CONFIRMATION_URL = Pattern.compile("\"confirmationUrl\":\"([^\"]+)\"");

    private static NotificationControlPlaneClient controlPlane;
    private static NotificationDataPlaneClient dataPlane;

    @BeforeAll
    static void setUp() {
        controlPlane = EmulatorFixture.notificationControlPlane();
        dataPlane = EmulatorFixture.notificationDataPlane();
    }

    @AfterAll
    static void tearDown() {
        controlPlane.close();
        dataPlane.close();
    }

    private static NotificationTopic createTopic(String prefix) {
        return controlPlane.createTopic(CreateTopicRequest.builder()
                .createTopicDetails(CreateTopicDetails.builder()
                        .name(prefix + "-" + System.nanoTime())
                        .compartmentId(TENANCY)
                        .description("java compat")
                        .build())
                .build()).getNotificationTopic();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    /** floci-oci's inspection endpoint holds the confirmation an EMAIL subscription would have received. */
    private static String capturedConfirmationUrl(String subscriptionId) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(ENDPOINT + "/_floci-oci/notifications/messages?subscriptionId=" + subscriptionId))
                .build(), HttpResponse.BodyHandlers.ofString());
        Matcher matcher = CONFIRMATION_URL.matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    @Test
    void topicLifecycle() {
        NotificationTopic topic = createTopic("java-topic");
        assertThat(topic.getTopicId()).startsWith("ocid1.onstopic.");
        assertThat(topic.getLifecycleState()).isEqualTo(NotificationTopic.LifecycleState.Active);
        assertThat(topic.getShortTopicId()).hasSize(6);
        assertThat(topic.getEtag()).isNotBlank();

        List<String> listed = controlPlane.listTopics(ListTopicsRequest.builder()
                .compartmentId(TENANCY).name(topic.getName()).build())
                .getItems().stream().map(t -> t.getTopicId()).toList();
        assertThat(listed).containsExactly(topic.getTopicId());

        NotificationTopic updated = controlPlane.updateTopic(UpdateTopicRequest.builder()
                .topicId(topic.getTopicId())
                .topicAttributesDetails(TopicAttributesDetails.builder().description("updated").build())
                .build()).getNotificationTopic();
        assertThat(updated.getDescription()).isEqualTo("updated");

        controlPlane.deleteTopic(DeleteTopicRequest.builder().topicId(topic.getTopicId()).build());
        assertThatThrownBy(() -> controlPlane.getTopic(GetTopicRequest.builder().topicId(topic.getTopicId()).build()))
                .isInstanceOfSatisfying(BmcException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }

    @Test
    void emailSubscriptionConfirmPublishAndUnsubscribe() throws Exception {
        NotificationTopic topic = createTopic("java-email");
        Subscription subscription = dataPlane.createSubscription(CreateSubscriptionRequest.builder()
                .createSubscriptionDetails(CreateSubscriptionDetails.builder()
                        .topicId(topic.getTopicId())
                        .compartmentId(TENANCY)
                        .protocol("EMAIL")
                        .endpoint("java@example.com")
                        .build())
                .build()).getSubscription();
        assertThat(subscription.getLifecycleState()).isEqualTo(Subscription.LifecycleState.Pending);
        assertThat(subscription.getDeliverPolicy()).contains("EXPONENTIAL");

        Map<String, String> confirmation = query(capturedConfirmationUrl(subscription.getId()));
        ConfirmationResult confirmed = dataPlane.getConfirmSubscription(GetConfirmSubscriptionRequest.builder()
                .id(subscription.getId())
                .token(confirmation.get("token"))
                .protocol(confirmation.get("protocol"))
                .build()).getConfirmationResult();
        assertThat(confirmed.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(confirmed.getTopicName()).isEqualTo(topic.getName());
        assertThat(dataPlane.getSubscription(GetSubscriptionRequest.builder().subscriptionId(subscription.getId())
                .build()).getSubscription().getLifecycleState()).isEqualTo(Subscription.LifecycleState.Active);

        PublishMessageResponse published = dataPlane.publishMessage(PublishMessageRequest.builder()
                .topicId(topic.getTopicId())
                .messageDetails(MessageDetails.builder().body("disk full").title("Alert").build())
                .build());
        assertThat(published.getPublishResult().getMessageId()).isNotBlank();

        List<SubscriptionSummary> summaries = dataPlane.listSubscriptions(ListSubscriptionsRequest.builder()
                .compartmentId(TENANCY).topicId(topic.getTopicId()).build()).getItems();
        assertThat(summaries).extracting(SubscriptionSummary::getId).containsExactly(subscription.getId());
        assertThat(summaries.get(0).getDeliveryPolicy().getBackoffRetryPolicy().getMaxRetryDuration())
                .isEqualTo(7_200_000);

        Map<String, String> unsubscribe = query(confirmed.getUnsubscribeUrl());
        String message = dataPlane.getUnsubscription(GetUnsubscriptionRequest.builder()
                .id(subscription.getId())
                .token(unsubscribe.get("token"))
                .protocol(unsubscribe.get("protocol"))
                .build()).getValue();
        assertThat(message).isNotBlank();
        assertThatThrownBy(() -> dataPlane.getSubscription(GetSubscriptionRequest.builder()
                .subscriptionId(subscription.getId()).build()))
                .isInstanceOfSatisfying(BmcException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));

        controlPlane.deleteTopic(DeleteTopicRequest.builder().topicId(topic.getTopicId()).build());
    }
}
