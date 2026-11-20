"""Logging: log groups and logs through work requests, PutLogs and SearchLogs via the OCI Python SDK."""

import datetime
import json
import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def management(sdk_config):
    return oci.logging.LoggingManagementClient(sdk_config, service_endpoint=ENDPOINT)


def identifier(management, response):
    """Mutations answer a work request; the new OCID is its resource's identifier."""
    work_request = management.get_work_request(response.headers["opc-work-request-id"]).data
    assert work_request.status == "SUCCEEDED"
    return work_request.resources[0].identifier


def test_log_lifecycle_ingest_and_search(management, sdk_config):
    group_name = f"py-logs-{time.time_ns()}"
    group_id = identifier(management, management.create_log_group(
        oci.logging.models.CreateLogGroupDetails(compartment_id=TENANCY, display_name=group_name)))
    log_id = identifier(management, management.create_log(group_id, oci.logging.models.CreateLogDetails(
        display_name="worker", log_type="CUSTOM", retention_duration=90)))

    log = management.get_log(group_id, log_id).data
    assert log.lifecycle_state == "ACTIVE"
    assert log.retention_duration == 90
    assert [g.id for g in management.list_log_groups(TENANCY, display_name=group_name).data] == [group_id]

    now = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
    ingestion = oci.loggingingestion.LoggingClient(sdk_config, service_endpoint=ENDPOINT)
    ingestion.put_logs(log_id, oci.loggingingestion.models.PutLogsDetails(
        specversion="1.0",
        log_entry_batches=[oci.loggingingestion.models.LogEntryBatch(
            source="py-worker", type="com.example.jobs", defaultlogentrytime=now,
            entries=[oci.loggingingestion.models.LogEntry(id=str(i), data=json.dumps({"job": i, "ok": i % 2 == 0}))
                     for i in range(4)])]))

    search = oci.loggingsearch.LogSearchClient(sdk_config, service_endpoint=ENDPOINT)
    window = dict(time_start=now - datetime.timedelta(hours=1), time_end=now + datetime.timedelta(minutes=5))
    failed = search.search_logs(oci.loggingsearch.models.SearchLogsDetails(
        search_query=f'search "{TENANCY}/{group_name}/worker" | where data.ok = false | sort by data.job desc',
        **window)).data
    assert failed.summary.result_count == 2
    assert [r.data["logContent"]["data"]["job"] for r in failed.results] == [3, 1]

    counted = search.search_logs(oci.loggingsearch.models.SearchLogsDetails(
        search_query=f'search "{TENANCY}/{group_name}" | count', **window)).data
    assert counted.results[0].data == {"count": 4}

    identifier(management, management.delete_log(group_id, log_id))
    identifier(management, management.delete_log_group(group_id))
