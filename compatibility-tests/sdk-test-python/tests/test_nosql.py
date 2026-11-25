"""NoSQL Database: tables from DDL through work requests, rows, queries and statements via the OCI Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def nosql(sdk_config):
    return oci.nosql.NosqlClient(sdk_config, service_endpoint=ENDPOINT)


def table_resource(nosql, response):
    """Mutations answer a work request whose resource is the TABLE, as Terraform reads it."""
    work_request = nosql.get_work_request(response.headers["opc-work-request-id"]).data
    assert work_request.status == "SUCCEEDED"
    assert work_request.time_finished is not None
    resource = work_request.resources[0]
    assert resource.entity_type == "TABLE"
    return resource


def test_table_rows_and_queries(nosql):
    name = f"py_users_{time.time_ns()}"
    ddl = (f"CREATE TABLE {name} (id INTEGER GENERATED ALWAYS AS IDENTITY, name STRING, info JSON,"
           f" PRIMARY KEY (id)) USING TTL 7 DAYS")
    created = table_resource(nosql, nosql.create_table(oci.nosql.models.CreateTableDetails(
        name=name, compartment_id=TENANCY, ddl_statement=ddl)))
    assert created.action_type == "CREATED"
    table_id = created.identifier

    table = nosql.get_table(table_id).data
    assert table.ddl_statement == ddl
    assert table.schema.identity.column_name == "id"
    assert table.schema.identity.is_always is True
    assert table.schema.ttl == 7
    assert table.table_limits.capacity_mode == "ON_DEMAND"

    generated = []
    for city in ("Lima", "Oslo", "Lima"):
        result = nosql.update_row(name, oci.nosql.models.UpdateRowDetails(
            compartment_id=TENANCY, value={"name": city.lower(), "info": {"city": city}})).data
        assert result.version
        generated.append(result.generated_value)
    assert generated == ["1", "2", "3"]

    row = nosql.get_row(table_id, ["id:2"])
    assert row.data.value["info"] == {"city": "Oslo"}
    assert row.data.time_of_expiration is not None

    query = nosql.query(oci.nosql.models.QueryDetails(
        compartment_id=TENANCY,
        statement=f"SELECT id FROM {name} t WHERE t.info.city = 'Lima' ORDER BY id")).data
    assert [item["id"] for item in query.items] == [1, 3]

    prepared = nosql.prepare_statement(
        TENANCY, f"DECLARE $city STRING; SELECT count(*) AS n FROM {name} t WHERE t.info.city = $city").data
    counted = nosql.query(oci.nosql.models.QueryDetails(
        compartment_id=TENANCY, statement=prepared.statement, is_prepared=True,
        variables={"$city": "Lima"})).data
    assert counted.items == [{"n": 2}]

    summary = nosql.summarize_statement(TENANCY, f"DROP TABLE IF EXISTS {name}").data
    assert summary.operation == "DROP_TABLE"
    assert summary.is_if_exists is True
    assert nosql.summarize_statement(TENANCY, "SELECT * FRM t").data.syntax_error

    deleted = nosql.delete_row(table_id, ["id:1"]).data
    assert deleted.is_success is True
    assert nosql.delete_row(table_id, ["id:1"]).data.is_success is False

    dropped = table_resource(nosql, nosql.delete_table(table_id))
    assert dropped.action_type == "DELETED"
    with pytest.raises(oci.exceptions.ServiceError) as error:
        nosql.get_table(name, compartment_id=TENANCY)
    assert error.value.status == 404
    assert error.value.code == "NotAuthorizedOrNotFound"
