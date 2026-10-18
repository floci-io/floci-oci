"""Database with PostgreSQL validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def postgresql(sdk_config):
    return oci.psql.PostgresqlClient(sdk_config, service_endpoint=ENDPOINT)


def wait_for_work_request(postgresql, work_request_id, timeout=300):
    """Polls until the work request carries time_finished, as the SDK's own waiters do."""
    deadline = time.time() + timeout
    work_request = postgresql.get_work_request(work_request_id).data
    while work_request.time_finished is None and time.time() < deadline:
        time.sleep(1)
        work_request = postgresql.get_work_request(work_request_id).data
    return work_request


def test_catalog_lists_shapes_and_default_configurations(postgresql):
    shapes = postgresql.list_shapes(compartment_id=TENANCY).data.items
    assert "VM.Standard.E4.Flex" in [shape.shape for shape in shapes]

    configurations = postgresql.list_default_configurations(db_version="15").data.items
    assert len(configurations) == 3
    assert "defaultconfiguration" in configurations[0].id
    assert postgresql.get_default_configuration(configurations[0].id).data.db_version == "15"


def test_db_system_lifecycle(postgresql):
    created = postgresql.create_db_system(
        oci.psql.models.CreateDbSystemDetails(
            compartment_id=TENANCY,
            display_name=f"py-db-{time.time_ns()}",
            db_version="15",
            shape="PostgreSQL.VM.Standard.E4.Flex.2.32GB",
            storage_details=oci.psql.models.OciOptimizedStorageDetails(is_regionally_durable=True),
            network_details=oci.psql.models.NetworkDetails(subnet_id="ocid1.subnet.oc1.iad.pysdk"),
            credentials=oci.psql.models.Credentials(
                username="admin_user",
                password_details=oci.psql.models.PlainTextPasswordDetails(password="BEstrO0ng_#11"),
            ),
        )
    )
    db_system_id = created.data.id

    # Every check sits inside the try, so a failure still deletes the database it created.
    try:
        assert created.headers["opc-work-request-id"]
        assert created.data.shape == "VM.Standard.E4.Flex"
        assert created.data.instance_ocpu_count == 2
        assert created.data.instance_memory_size_in_gbs == 32
        assert created.data.admin_username == "admin_user"

        work_request = wait_for_work_request(postgresql, created.headers["opc-work-request-id"])
        assert work_request.status == "SUCCEEDED"
        assert work_request.operation_type == "CREATE_POSTGRESQL_DB_SYSTEM"
        assert work_request.resources[0].identifier == db_system_id

        db_system = postgresql.get_db_system(db_system_id).data
        assert db_system.lifecycle_state == "ACTIVE"
        assert db_system.db_version.startswith("15")
        assert db_system.storage_details.system_type == "OCI_OPTIMIZED_STORAGE"
        assert db_system.management_policy.backup_policy.kind == "NONE"
        assert db_system.source.source_type == "NONE"
        assert db_system_id in [s.id for s in postgresql.list_db_systems(compartment_id=TENANCY).data.items]

        connection = postgresql.get_connection_details(db_system_id).data
        assert connection.ca_certificate.startswith("-----BEGIN CERTIFICATE-----")
        assert connection.primary_db_endpoint.fqdn
        assert connection.primary_db_endpoint.port > 0
        primary = postgresql.get_primary_db_instance(db_system_id).data.db_instance_id
        assert [endpoint.db_instance_id for endpoint in connection.instance_endpoints] == [primary]

        stopped = postgresql.stop_db_system(db_system_id)
        assert wait_for_work_request(postgresql, stopped.headers["opc-work-request-id"]).status == "SUCCEEDED"
        assert postgresql.get_db_system(db_system_id).data.lifecycle_state == "INACTIVE"

        started = postgresql.start_db_system(db_system_id, oci.psql.models.StartDbSystemDetails())
        assert wait_for_work_request(postgresql, started.headers["opc-work-request-id"]).status == "SUCCEEDED"
        assert postgresql.get_db_system(db_system_id).data.lifecycle_state == "ACTIVE"
    finally:
        deleted = postgresql.delete_db_system(db_system_id)
        wait_for_work_request(postgresql, deleted.headers["opc-work-request-id"])

    assert postgresql.get_db_system(db_system_id).data.lifecycle_state == "DELETED"


def test_errors_carry_the_oci_shape(postgresql):
    with pytest.raises(oci.exceptions.ServiceError) as missing:
        postgresql.get_db_system("ocid1.postgresqldbsystem.oc1.iad.missing")
    assert missing.value.status == 404
    assert missing.value.code == "NotAuthorizedOrNotFound"
