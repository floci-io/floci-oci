"""Container Engine for Kubernetes (OKE) validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


@pytest.fixture(scope="module")
def container_engine(sdk_config):
    return oci.container_engine.ContainerEngineClient(sdk_config, service_endpoint=ENDPOINT)


def created_id(container_engine, response):
    """CreateCluster and CreateNodePool return no body: the OCID is on the work request."""
    work_request_id = response.headers["opc-work-request-id"]
    work_request = container_engine.get_work_request(work_request_id).data
    return work_request.resources[0].identifier


def test_cluster_and_node_pool_lifecycle(container_engine):
    name = unique("py-cluster")
    created = container_engine.create_cluster(
        oci.container_engine.models.CreateClusterDetails(
            compartment_id=TENANCY,
            name=name,
            vcn_id="ocid1.vcn.oc1.iad.pyvcn",
            kubernetes_version="v1.30.1",
        )
    )
    assert created.status == 202
    assert created.data is None
    cluster_id = created_id(container_engine, created)
    try:
        exercise_cluster(container_engine, cluster_id, name)
    finally:
        # Deleting the cluster stops its k3s sidecar and frees its port, even when a step failed.
        assert container_engine.delete_cluster(cluster_id).headers["opc-work-request-id"]


def exercise_cluster(container_engine, cluster_id, name):
    cluster = oci.wait_until(
        container_engine,
        container_engine.get_cluster(cluster_id),
        "lifecycle_state",
        "ACTIVE",
        max_interval_seconds=2,
        max_wait_seconds=300,
    ).data
    assert cluster.name == name
    assert cluster.system_tags == {}
    assert cluster.available_kubernetes_upgrades == []

    listed = container_engine.list_clusters(TENANCY).data
    assert any(item.id == cluster_id for item in listed)

    kubeconfig = container_engine.create_kubeconfig(cluster_id).data.content.decode()
    assert "command: oci" in kubeconfig
    assert "- generate-token" in kubeconfig
    assert f"- {cluster_id}" in kubeconfig

    pool_name = unique("py-pool")
    pool_created = container_engine.create_node_pool(
        oci.container_engine.models.CreateNodePoolDetails(
            compartment_id=TENANCY,
            cluster_id=cluster_id,
            name=pool_name,
            kubernetes_version="v1.30.1",
            node_shape="VM.Standard.E4.Flex",
            quantity_per_subnet=2,
        )
    )
    assert pool_created.status == 202
    node_pool_id = created_id(container_engine, pool_created)
    try:
        node_pool = container_engine.get_node_pool(node_pool_id).data
        assert node_pool.name == pool_name
        assert node_pool.cluster_id == cluster_id
        assert node_pool.quantity_per_subnet == 2
        assert node_pool.system_tags == {}

        pools = container_engine.list_node_pools(TENANCY, cluster_id=cluster_id).data
        summary = next(item for item in pools if item.id == node_pool_id)
        assert summary.system_tags == {}
    finally:
        assert container_engine.delete_node_pool(node_pool_id).headers["opc-work-request-id"]


def test_get_missing_cluster_is_not_authorized_or_not_found(container_engine):
    with pytest.raises(oci.exceptions.ServiceError) as error:
        container_engine.get_cluster("ocid1.cluster.oc1.iad.doesnotexist")
    assert error.value.status == 404
    assert error.value.code == "NotAuthorizedOrNotFound"
