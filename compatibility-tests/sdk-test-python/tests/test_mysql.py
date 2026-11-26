"""MySQL HeatWave validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def db_systems(sdk_config):
    return oci.mysql.DbSystemClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def catalog(sdk_config):
    return oci.mysql.MysqlaasClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def work_requests(sdk_config):
    return oci.mysql.WorkRequestsClient(sdk_config, service_endpoint=ENDPOINT)


def wait_for_work_request(work_requests, work_request_id, timeout=300):
    """Polls until the work request carries time_finished, as the SDK's own waiters do."""
    deadline = time.time() + timeout
    work_request = work_requests.get_work_request(work_request_id).data
    while work_request.time_finished is None and time.time() < deadline:
        time.sleep(1)
        work_request = work_requests.get_work_request(work_request_id).data
    return work_request


def test_catalog_lists_shapes_versions_and_configurations(catalog):
    shapes = catalog.list_shapes(compartment_id=TENANCY).data
    assert "MySQL.2" in [shape.name for shape in shapes]

    versions = catalog.list_versions(compartment_id=TENANCY).data
    assert "8.4.4" in [version.version for family in versions for version in family.versions]

    configurations = catalog.list_configurations(compartment_id=TENANCY, shape_name="MySQL.2", type=["DEFAULT"]).data
    assert sorted(configuration.display_name for configuration in configurations) == [
        "MySQL.2.HA",
        "MySQL.2.Standalone",
    ]


def test_custom_configuration_round_trip(catalog):
    created = catalog.create_configuration(
        oci.mysql.models.CreateConfigurationDetails(
            compartment_id=TENANCY,
            shape_name="MySQL.2",
            display_name="py-compat-configuration",
            variables=oci.mysql.models.ConfigurationVariables(max_connections=500),
        )
    ).data
    try:
        assert created.type == "CUSTOM"
        assert created.variables.max_connections == 500
        assert catalog.get_configuration(created.id).data.display_name == "py-compat-configuration"
    finally:
        catalog.delete_configuration(created.id)
    assert catalog.get_configuration(created.id).data.lifecycle_state == "DELETED"


def test_db_system_lifecycle(db_systems, work_requests, network):
    created = db_systems.create_db_system(
        oci.mysql.models.CreateDbSystemDetails(
            compartment_id=TENANCY,
            display_name="py-compat-mysql",
            shape_name="MySQL.2",
            subnet_id=network["subnet_id"],
            admin_username="admin_user",
            admin_password="BEstrO0ng_#11",
        )
    )
    db_system_id = created.data.id
    try:
        assert created.headers["opc-work-request-id"]
        work_request = wait_for_work_request(work_requests, created.headers["opc-work-request-id"])
        assert work_request.status == "SUCCEEDED"

        active = db_systems.get_db_system(db_system_id).data
        assert active.lifecycle_state == "ACTIVE"
        assert active.endpoints[0].port > 0
        assert db_system_id in [summary.id for summary in db_systems.list_db_systems(compartment_id=TENANCY).data]

        stopped = db_systems.stop_db_system(
            db_system_id, oci.mysql.models.StopDbSystemDetails(shutdown_type="FAST")
        ).headers["opc-work-request-id"]
        assert wait_for_work_request(work_requests, stopped).status == "SUCCEEDED"
        assert db_systems.get_db_system(db_system_id).data.lifecycle_state == "INACTIVE"
    finally:
        deleted = db_systems.delete_db_system(db_system_id).headers["opc-work-request-id"]
        wait_for_work_request(work_requests, deleted)
    assert db_systems.get_db_system(db_system_id).data.lifecycle_state == "DELETED"
