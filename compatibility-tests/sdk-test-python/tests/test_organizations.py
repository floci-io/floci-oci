"""Organizations (Tenant Manager control plane) validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def organizations(sdk_config):
    return oci.tenant_manager_control_plane.OrganizationClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def links(sdk_config):
    return oci.tenant_manager_control_plane.LinkClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="module")
def work_requests(sdk_config):
    return oci.tenant_manager_control_plane.WorkRequestClient(sdk_config, service_endpoint=ENDPOINT)


def create_child(organizations, work_requests, name):
    """Work-request driven: no body, opc-work-request-id header, then poll."""
    created = organizations.create_child_tenancy(
        oci.tenant_manager_control_plane.models.CreateChildTenancyDetails(
            compartment_id=TENANCY,
            tenancy_name=name,
            home_region="us-ashburn-1",
            admin_email="admin@example.com",
        )
    )
    work_request = work_requests.get_work_request(created.headers["opc-work-request-id"]).data
    assert work_request.status == "SUCCEEDED"
    assert work_request.operation_type == "CREATE_CHILD_TENANCY"
    return work_request.resources[0].identifier


def test_child_tenancy_forms_an_organization(organizations, links, work_requests):
    child = create_child(organizations, work_requests, f"py-child-{time.time_ns()}")

    listed = organizations.list_organizations(compartment_id=TENANCY).data.items
    assert len(listed) == 1
    organization_id = listed[0].id

    members = organizations.list_organization_tenancies(organization_id).data.items
    roles = {member.tenancy_id: member.role for member in members}
    assert roles[TENANCY] == "PARENT"
    assert roles[child] == "CHILD"

    child_links = links.list_links(child_tenancy_id=child).data.items
    assert [link.feature for link in child_links] == ["CORE"]


def test_child_tenancy_reports_its_own_home_region(sdk_config, organizations, work_requests):
    child = create_child(organizations, work_requests, f"py-isolated-{time.time_ns()}")

    child_config = dict(sdk_config, tenancy=child)
    identity = oci.identity.IdentityClient(child_config, service_endpoint=ENDPOINT)
    tenancy = identity.get_tenancy(child).data
    assert tenancy.id == child
    assert tenancy.home_region_key == "IAD"
