"""Notifications (ONS): topics, subscriptions, confirmation and publish through the OCI Python SDK."""

import json
import time
import urllib.parse
import urllib.request

import oci
import pytest

from conftest import ENDPOINT, TENANCY


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


@pytest.fixture(scope="module")
def control_plane(sdk_config):
    return oci.ons.NotificationControlPlaneClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def data_plane(sdk_config):
    return oci.ons.NotificationDataPlaneClient(sdk_config, service_endpoint=ENDPOINT)


def captured(subscription_id):
    """floci-oci's inspection endpoint: what the EMAIL subscription would have received."""
    query = urllib.parse.urlencode({"subscriptionId": subscription_id})
    with urllib.request.urlopen(f"{ENDPOINT}/_floci-oci/notifications/messages?{query}") as response:
        return json.load(response)["messages"]


def test_topic_lifecycle(control_plane):
    name = unique("py-topic")
    topic = control_plane.create_topic(
        oci.ons.models.CreateTopicDetails(name=name, compartment_id=TENANCY, description="first")
    ).data
    assert topic.topic_id.startswith("ocid1.onstopic.")
    assert topic.lifecycle_state == "ACTIVE"
    assert len(topic.short_topic_id) == 6
    assert topic.api_endpoint

    listed = control_plane.list_topics(TENANCY, name=name).data
    assert [t.topic_id for t in listed] == [topic.topic_id]

    updated = control_plane.update_topic(
        topic.topic_id, oci.ons.models.TopicAttributesDetails(description="second")
    ).data
    assert updated.description == "second"

    control_plane.delete_topic(topic.topic_id)
    with pytest.raises(oci.exceptions.ServiceError) as error:
        control_plane.get_topic(topic.topic_id)
    assert error.value.status == 404


def test_email_subscription_confirm_publish_and_unsubscribe(control_plane, data_plane):
    topic = control_plane.create_topic(
        oci.ons.models.CreateTopicDetails(name=unique("py-email"), compartment_id=TENANCY)
    ).data
    subscription = data_plane.create_subscription(
        oci.ons.models.CreateSubscriptionDetails(
            topic_id=topic.topic_id, compartment_id=TENANCY, protocol="EMAIL", endpoint="py@example.com"
        )
    ).data
    assert subscription.lifecycle_state == "PENDING"
    assert json.loads(subscription.deliver_policy)["backoffRetryPolicy"]["policyType"] == "EXPONENTIAL"

    confirmation_url = captured(subscription.id)[0]["confirmationUrl"]
    params = urllib.parse.parse_qs(urllib.parse.urlparse(confirmation_url).query)
    confirmed = data_plane.get_confirm_subscription(
        subscription.id, params["token"][0], params["protocol"][0]
    ).data
    assert confirmed.subscription_id == subscription.id
    assert confirmed.topic_name == topic.name
    assert data_plane.get_subscription(subscription.id).data.lifecycle_state == "ACTIVE"

    result = data_plane.publish_message(
        topic.topic_id, oci.ons.models.MessageDetails(body="disk full", title="Alert")
    ).data
    assert result.message_id
    delivered = [m for m in captured(subscription.id) if m["messageType"] == "Notification"]
    assert delivered and delivered[0]["body"] == "disk full" and delivered[0]["title"] == "Alert"

    summaries = data_plane.list_subscriptions(TENANCY, topic_id=topic.topic_id).data
    assert [s.id for s in summaries] == [subscription.id]
    assert summaries[0].delivery_policy.backoff_retry_policy.max_retry_duration == 7200000

    unsubscribe = urllib.parse.parse_qs(urllib.parse.urlparse(confirmed.unsubscribe_url).query)
    message = data_plane.get_unsubscription(
        subscription.id, unsubscribe["token"][0], unsubscribe["protocol"][0]
    ).data
    assert isinstance(message, str) and message
    with pytest.raises(oci.exceptions.ServiceError) as error:
        data_plane.get_subscription(subscription.id)
    assert error.value.status == 404

    control_plane.delete_topic(topic.topic_id)
