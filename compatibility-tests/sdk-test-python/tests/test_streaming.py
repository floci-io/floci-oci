"""Streaming stream pools validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY

# With the compose emulator a pool starts a Redpanda broker before it is ACTIVE.
TIMEOUT = 180


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


@pytest.fixture(scope="module")
def stream_admin(sdk_config):
    return oci.streaming.StreamAdminClient(sdk_config, service_endpoint=ENDPOINT)


def wait_for_work_request(stream_admin, work_request_id, timeout=TIMEOUT):
    """Polls until the work request carries time_finished, as the SDK's own waiters do."""
    deadline = time.time() + timeout
    work_request = stream_admin.get_work_request(work_request_id).data
    while work_request.time_finished is None and time.time() < deadline:
        time.sleep(1)
        work_request = stream_admin.get_work_request(work_request_id).data
    return work_request


def test_stream_pool_crud(stream_admin):
    name = unique("py-pool")
    created = stream_admin.create_stream_pool(
        oci.streaming.models.CreateStreamPoolDetails(
            name=name,
            compartment_id=TENANCY,
            kafka_settings=oci.streaming.models.KafkaSettings(num_partitions=2),
        )
    )
    assert created.headers["etag"]
    pool = created.data
    # kafkaSettings and customEncryptionKey are mandatory on StreamPool.
    assert pool.kafka_settings is not None
    assert pool.custom_encryption_key is not None

    work_request = wait_for_work_request(stream_admin, created.headers["opc-work-request-id"])
    assert work_request.status == "SUCCEEDED"
    assert [(r.entity_type, r.identifier) for r in work_request.resources] == [("stream_pool", pool.id)]

    fetched = stream_admin.get_stream_pool(pool.id).data
    assert fetched.lifecycle_state == "ACTIVE"
    assert fetched.kafka_settings.num_partitions == 2
    assert fetched.custom_encryption_key.key_state == "NONE"
    assert fetched.is_private is False

    listed = stream_admin.list_stream_pools(compartment_id=TENANCY, name=name).data
    assert [p.id for p in listed] == [pool.id]

    updated = stream_admin.update_stream_pool(
        pool.id, oci.streaming.models.UpdateStreamPoolDetails(name=name + "-renamed")
    )
    assert updated.data.name == name + "-renamed"
    wait_for_work_request(stream_admin, updated.headers["opc-work-request-id"])

    moved = stream_admin.change_stream_pool_compartment(
        pool.id, oci.streaming.models.ChangeStreamPoolCompartmentDetails(compartment_id=TENANCY)
    )
    assert moved.headers["opc-work-request-id"]

    deleted = stream_admin.delete_stream_pool(pool.id)
    assert wait_for_work_request(stream_admin, deleted.headers["opc-work-request-id"]).status == "SUCCEEDED"

    with pytest.raises(oci.exceptions.ServiceError) as missing:
        stream_admin.get_stream_pool(pool.id)
    assert missing.value.status == 404
    assert missing.value.code == "NotAuthorizedOrNotFound"

