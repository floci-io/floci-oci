"""Container Instances validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def container_instances(sdk_config):
    return oci.container_instances.ContainerInstanceClient(sdk_config, service_endpoint=ENDPOINT)


def wait_for_work_request(client, work_request_id, timeout=180):
    """Polls until the work request carries time_finished, as the Terraform provider does."""
    deadline = time.time() + timeout
    work_request = client.get_work_request(work_request_id).data
    while work_request.time_finished is None and time.time() < deadline:
        time.sleep(1)
        work_request = client.get_work_request(work_request_id).data
    return work_request


def test_shapes_are_an_item_collection(container_instances):
    shapes = container_instances.list_container_instance_shapes(compartment_id=TENANCY).data
    assert "CI.Standard.E4.Flex" in [shape.name for shape in shapes.items]


def test_container_instance_lifecycle(container_instances, identity, network):
    availability_domain = identity.list_availability_domains(TENANCY).data[0].name
    created = container_instances.create_container_instance(
        oci.container_instances.models.CreateContainerInstanceDetails(
            compartment_id=TENANCY,
            availability_domain=availability_domain,
            display_name="py-sdk-ci",
            shape="CI.Standard.E4.Flex",
            shape_config=oci.container_instances.models.CreateContainerInstanceShapeConfigDetails(ocpus=1),
            containers=[
                oci.container_instances.models.CreateContainerDetails(
                    image_url="busybox:1.36",
                    command=["sh", "-c"],
                    arguments=["echo hello-from-python; exec httpd -f -p 8080"],
                )
            ],
            vnics=[oci.container_instances.models.CreateContainerVnicDetails(subnet_id=network["subnet_id"])],
        )
    )
    instance_id = created.data.id
    try:
        assert created.headers["etag"]
        work_request = wait_for_work_request(container_instances, created.headers["opc-work-request-id"])
        assert work_request.status == "SUCCEEDED"
        assert work_request.resources[0].identifier == instance_id

        instance = container_instances.get_container_instance(instance_id).data
        assert instance.lifecycle_state == "ACTIVE"
        assert instance.shape_config.memory_in_gbs == 16

        container_id = instance.containers[0].container_id
        logs = ""
        deadline = time.time() + 30
        while "hello-from-python" not in logs and time.time() < deadline:
            logs = container_instances.retrieve_logs(container_id).data.content.decode("utf-8")
            time.sleep(0.5)
        assert "hello-from-python" in logs

        listed = container_instances.list_containers(compartment_id=TENANCY, container_instance_id=instance_id).data
        assert [summary.id for summary in listed.items] == [container_id]
    finally:
        deleted = container_instances.delete_container_instance(instance_id)
        wait_for_work_request(container_instances, deleted.headers["opc-work-request-id"])
    assert container_instances.get_container_instance(instance_id).data.lifecycle_state == "DELETED"
