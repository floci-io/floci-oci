"""Monitoring: custom metrics, MQL queries and alarms through the OCI Python SDK."""

import datetime
import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def monitoring(sdk_config):
    return oci.monitoring.MonitoringClient(sdk_config, service_endpoint=ENDPOINT)


def minutes_ago(minutes):
    now = datetime.datetime.now(datetime.timezone.utc).replace(second=10, microsecond=0)
    return now - datetime.timedelta(minutes=minutes)


def test_post_and_summarize_metrics(monitoring):
    namespace = f"py_compat_{time.time_ns()}"
    posted = monitoring.post_metric_data(
        oci.monitoring.models.PostMetricDataDetails(
            metric_data=[
                oci.monitoring.models.MetricDataDetails(
                    namespace=namespace,
                    compartment_id=TENANCY,
                    name="latency",
                    dimensions={"host": host},
                    datapoints=[oci.monitoring.models.Datapoint(timestamp=minutes_ago(2), value=value)],
                )
                for host, value in (("web-1", 120.0), ("web-2", 80.0))
            ]
        )
    ).data
    assert posted.failed_metrics_count == 0

    over = monitoring.summarize_metrics_data(
        TENANCY,
        oci.monitoring.models.SummarizeMetricsDataDetails(namespace=namespace, query="latency[1m].max() > 100"),
    ).data
    assert [m.dimensions["host"] for m in over] == ["web-1"]
    assert over[0].aggregated_datapoints[0].value == 120.0

    grouped = monitoring.summarize_metrics_data(
        TENANCY,
        oci.monitoring.models.SummarizeMetricsDataDetails(namespace=namespace, query="latency[1m].grouping().mean()"),
    ).data
    assert len(grouped) == 1 and grouped[0].aggregated_datapoints[0].value == 100.0

    metrics = monitoring.list_metrics(
        TENANCY, oci.monitoring.models.ListMetricsDetails(namespace=namespace, group_by=["name"])
    ).data
    assert [m.name for m in metrics] == ["latency"]


def test_rejected_metrics_are_reported(monitoring):
    posted = monitoring.post_metric_data(
        oci.monitoring.models.PostMetricDataDetails(
            metric_data=[
                oci.monitoring.models.MetricDataDetails(
                    namespace="oci_reserved",
                    compartment_id=TENANCY,
                    name="x",
                    dimensions={"k": "v"},
                    datapoints=[oci.monitoring.models.Datapoint(timestamp=minutes_ago(1), value=1.0)],
                )
            ]
        )
    ).data
    assert posted.failed_metrics_count == 1
    assert posted.failed_metrics[0].metric_data.name == "x"


def test_alarm_crud(monitoring, sdk_config):
    ons = oci.ons.NotificationControlPlaneClient(sdk_config, service_endpoint=ENDPOINT)
    topic = ons.create_topic(
        oci.ons.models.CreateTopicDetails(name=f"py-alarms-{time.time_ns()}", compartment_id=TENANCY)
    ).data
    alarm = monitoring.create_alarm(
        oci.monitoring.models.CreateAlarmDetails(
            display_name="py-alarm",
            compartment_id=TENANCY,
            metric_compartment_id=TENANCY,
            namespace="py_compat",
            query="errors[1m].count() > 10",
            severity="WARNING",
            destinations=[topic.topic_id],
            is_enabled=True,
            repeat_notification_duration="PT2H",
        )
    ).data
    assert alarm.lifecycle_state == "ACTIVE"
    assert alarm.resolution == "1m"
    assert alarm.repeat_notification_duration == "PT2H"

    listed = monitoring.list_alarms(TENANCY, display_name="py-alarm").data
    assert alarm.id in [a.id for a in listed]

    monitoring.delete_alarm(alarm.id)
    assert monitoring.get_alarm(alarm.id).data.lifecycle_state == "DELETED"
    ons.delete_topic(topic.topic_id)
