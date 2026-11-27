"""Autonomous Database (management plane, mock mode) validated through the real oci Python SDK."""

import io
import time
import zipfile

import oci
import pytest

from conftest import ENDPOINT, TENANCY

PASSWORD = "Welcome12345#"


@pytest.fixture(scope="module")
def database(sdk_config):
    return oci.database.DatabaseClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def work_requests(sdk_config):
    return oci.work_requests.WorkRequestClient(sdk_config, service_endpoint=ENDPOINT)


def test_autonomous_database_lifecycle(database, work_requests):
    db_name = "pyadb" + str(int(time.time()) % 100000)
    response = database.create_autonomous_database(
        oci.database.models.CreateAutonomousDatabaseDetails(
            compartment_id=TENANCY,
            db_name=db_name,
            display_name="py-compat-adb",
            admin_password=PASSWORD,
            compute_model="ECPU",
            compute_count=2,
            data_storage_size_in_tbs=1,
            db_workload="OLTP",
            character_set="AL32UTF8",
        )
    )
    adb = response.data
    try:
        assert adb.id.startswith("ocid1.autonomousdatabase.")
        assert adb.lifecycle_state == "AVAILABLE"
        assert adb.db_name == db_name
        assert adb.compute_count == 2
        assert response.headers["etag"]

        work_request = work_requests.get_work_request(response.headers["opc-work-request-id"]).data
        assert work_request.status == "SUCCEEDED"
        assert [r.identifier for r in work_request.resources if "database" in r.entity_type.lower()] == [adb.id]

        fetched = oci.wait_until(database, database.get_autonomous_database(adb.id), "lifecycle_state",
                                 "AVAILABLE", max_wait_seconds=60).data
        assert fetched.connection_strings.high.endswith(db_name.lower() + "_high.adb.oraclecloud.com")
        assert [p.consumer_group for p in fetched.connection_strings.profiles] == [
            "HIGH", "MEDIUM", "LOW", "TP", "TPURGENT"]

        listed = database.list_autonomous_databases(compartment_id=TENANCY, display_name="py-compat-adb").data
        assert adb.id in [summary.id for summary in listed]

        updated = database.update_autonomous_database(
            adb.id, oci.database.models.UpdateAutonomousDatabaseDetails(display_name="py-compat-adb-renamed")
        ).data
        assert updated.display_name == "py-compat-adb-renamed"

        assert database.stop_autonomous_database(adb.id).data.lifecycle_state == "STOPPED"
        assert database.start_autonomous_database(adb.id).data.lifecycle_state == "AVAILABLE"
        assert database.restart_autonomous_database(adb.id).data.lifecycle_state == "AVAILABLE"

        wallet = database.generate_autonomous_database_wallet(
            adb.id, oci.database.models.GenerateAutonomousDatabaseWalletDetails(password="WalletPass1")
        )
        with zipfile.ZipFile(io.BytesIO(wallet.data.content)) as archive:
            assert {"tnsnames.ora", "sqlnet.ora"} <= set(archive.namelist())
            assert (db_name.lower() + "_low = ") in archive.read("tnsnames.ora").decode()

        with pytest.raises(oci.exceptions.ServiceError) as conflict:
            database.start_autonomous_database(adb.id)
        assert conflict.value.status == 409
        assert conflict.value.code == "Conflict"
    finally:
        database.delete_autonomous_database(adb.id)

    terminated = database.get_autonomous_database(adb.id).data
    assert terminated.lifecycle_state == "TERMINATED"


def test_weak_admin_password_is_rejected(database):
    with pytest.raises(oci.exceptions.ServiceError) as error:
        database.create_autonomous_database(
            oci.database.models.CreateAutonomousDatabaseDetails(
                compartment_id=TENANCY, db_name="pyweak", admin_password="password")
        )
    assert error.value.status == 400
    assert error.value.code == "InvalidParameter"
