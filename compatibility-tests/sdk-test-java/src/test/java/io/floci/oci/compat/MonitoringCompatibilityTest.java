package io.floci.oci.compat;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.Alarm;
import com.oracle.bmc.monitoring.model.AlarmStatusSummary;
import com.oracle.bmc.monitoring.model.CreateAlarmDetails;
import com.oracle.bmc.monitoring.model.Datapoint;
import com.oracle.bmc.monitoring.model.ListMetricsDetails;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.MetricDataDetails;
import com.oracle.bmc.monitoring.model.PostMetricDataDetails;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.model.UpdateAlarmDetails;
import com.oracle.bmc.monitoring.requests.CreateAlarmRequest;
import com.oracle.bmc.monitoring.requests.DeleteAlarmRequest;
import com.oracle.bmc.monitoring.requests.GetAlarmRequest;
import com.oracle.bmc.monitoring.requests.ListAlarmsStatusRequest;
import com.oracle.bmc.monitoring.requests.ListMetricsRequest;
import com.oracle.bmc.monitoring.requests.PostMetricDataRequest;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.requests.UpdateAlarmRequest;
import com.oracle.bmc.monitoring.responses.PostMetricDataResponse;
import com.oracle.bmc.ons.NotificationControlPlaneClient;
import com.oracle.bmc.ons.model.CreateTopicDetails;
import com.oracle.bmc.ons.requests.CreateTopicRequest;
import com.oracle.bmc.ons.requests.DeleteTopicRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validates Monitoring metrics and alarms via oci-java-sdk. */
class MonitoringCompatibilityTest {

    private static MonitoringClient monitoring;
    private static NotificationControlPlaneClient notifications;

    @BeforeAll
    static void setUp() {
        monitoring = EmulatorFixture.monitoring();
        notifications = EmulatorFixture.notificationControlPlane();
    }

    @AfterAll
    static void tearDown() {
        monitoring.close();
        notifications.close();
    }

    private static Date minutesAgo(int minutes) {
        // The SDK model takes java.util.Date; the conversion stays at this boundary.
        return Date.from(Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(minutes, ChronoUnit.MINUTES).plusSeconds(10));
    }

    @Test
    void postSummarizeAndListMetrics() {
        String namespace = "java_compat_" + System.nanoTime();
        PostMetricDataResponse posted = monitoring.postMetricData(PostMetricDataRequest.builder()
                .postMetricDataDetails(PostMetricDataDetails.builder()
                        .metricData(List.of(MetricDataDetails.builder()
                                .namespace(namespace)
                                .compartmentId(TENANCY)
                                .name("orders")
                                .dimensions(Map.of("country", "NL"))
                                .datapoints(List.of(
                                        Datapoint.builder().timestamp(minutesAgo(3)).value(5.0).count(2).build(),
                                        Datapoint.builder().timestamp(minutesAgo(2)).value(10.0).build()))
                                .build()))
                        .build())
                .build());
        assertThat(posted.getPostMetricDataResponseDetails().getFailedMetricsCount()).isZero();

        List<MetricData> data = monitoring.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
                .compartmentId(TENANCY)
                .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
                        .namespace(namespace)
                        .query("orders[1h].sum()")
                        .build())
                .build()).getItems();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).getDimensions()).containsEntry("country", "NL");
        assertThat(data.get(0).getAggregatedDatapoints()).isNotEmpty();
        assertThat(data.get(0).getAggregatedDatapoints().get(data.get(0).getAggregatedDatapoints().size() - 1)
                .getValue()).isEqualTo(20.0);

        assertThat(monitoring.listMetrics(ListMetricsRequest.builder()
                .compartmentId(TENANCY)
                .listMetricsDetails(ListMetricsDetails.builder().namespace(namespace).build())
                .build()).getItems()).extracting(m -> m.getName()).containsExactly("orders");
    }

    @Test
    void alarmLifecycle() {
        String topicId = notifications.createTopic(CreateTopicRequest.builder()
                .createTopicDetails(CreateTopicDetails.builder()
                        .name("java-alarms-" + System.nanoTime()).compartmentId(TENANCY).build())
                .build()).getNotificationTopic().getTopicId();

        Alarm alarm = monitoring.createAlarm(CreateAlarmRequest.builder()
                .createAlarmDetails(CreateAlarmDetails.builder()
                        .displayName("java-alarm")
                        .compartmentId(TENANCY)
                        .metricCompartmentId(TENANCY)
                        .namespace("java_compat")
                        .query("errors[1m].count() > 10")
                        .severity(Alarm.Severity.Error)
                        .destinations(List.of(topicId))
                        .isEnabled(true)
                        .build())
                .build()).getAlarm();
        assertThat(alarm.getId()).startsWith("ocid1.alarm.");
        assertThat(alarm.getLifecycleState()).isEqualTo(Alarm.LifecycleState.Active);
        assertThat(alarm.getPendingDuration()).isEqualTo("PT1M");

        Alarm updated = monitoring.updateAlarm(UpdateAlarmRequest.builder()
                .alarmId(alarm.getId())
                .updateAlarmDetails(UpdateAlarmDetails.builder().severity(Alarm.Severity.Critical).build())
                .build()).getAlarm();
        assertThat(updated.getSeverity()).isEqualTo(Alarm.Severity.Critical);

        List<AlarmStatusSummary> statuses = monitoring.listAlarmsStatus(ListAlarmsStatusRequest.builder()
                .compartmentId(TENANCY).build()).getItems();
        assertThat(statuses).filteredOn(s -> s.getId().equals(alarm.getId()))
                .extracting(AlarmStatusSummary::getStatus).containsExactly(AlarmStatusSummary.Status.Ok);

        monitoring.deleteAlarm(DeleteAlarmRequest.builder().alarmId(alarm.getId()).build());
        assertThat(monitoring.getAlarm(GetAlarmRequest.builder().alarmId(alarm.getId()).build())
                .getAlarm().getLifecycleState()).isEqualTo(Alarm.LifecycleState.Deleted);

        assertThatThrownBy(() -> monitoring.getAlarm(GetAlarmRequest.builder()
                .alarmId("ocid1.alarm.oc1.iad.missing").build()))
                .isInstanceOfSatisfying(BmcException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
        notifications.deleteTopic(DeleteTopicRequest.builder().topicId(topicId).build());
    }
}
