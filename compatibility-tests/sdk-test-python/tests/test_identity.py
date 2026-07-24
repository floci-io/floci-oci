"""Identity API validated through the real oci Python SDK."""

import time

import oci
import pytest

from conftest import TENANCY


def unique(prefix):
    return f"{prefix}-{time.time_ns()}"


def test_compartment_crud_and_async_delete(identity):
    name = unique("py-compartment")
    created = identity.create_compartment(
        oci.identity.models.CreateCompartmentDetails(
            compartment_id=TENANCY, name=name, description="python compat test"
        )
    )
    compartment = created.data
    assert compartment.id.startswith("ocid1.compartment.")
    assert compartment.lifecycle_state == "ACTIVE"
    assert created.headers.get("etag")
    assert created.headers.get("opc-request-id")

    fetched = identity.get_compartment(compartment.id).data
    assert fetched.name == name

    listed = identity.list_compartments(TENANCY).data
    assert any(c.id == compartment.id for c in listed)

    deleted = identity.delete_compartment(compartment.id)
    work_request_id = deleted.headers["opc-work-request-id"]
    assert work_request_id.startswith("ocid1.")

    work_request = identity.get_work_request(work_request_id).data
    assert work_request.status == "SUCCEEDED"
    assert work_request.operation_type == "DELETE_COMPARTMENT"


def test_user_group_membership_roundtrip(identity):
    suffix = str(time.time_ns())
    user = identity.create_user(
        oci.identity.models.CreateUserDetails(
            compartment_id=TENANCY,
            name=f"py-user-{suffix}",
            description="python user",
            email="py@example.com",
        )
    ).data
    assert user.is_mfa_activated is False

    group = identity.create_group(
        oci.identity.models.CreateGroupDetails(
            compartment_id=TENANCY, name=f"py-group-{suffix}", description="python group"
        )
    ).data

    membership = identity.add_user_to_group(
        oci.identity.models.AddUserToGroupDetails(user_id=user.id, group_id=group.id)
    ).data
    assert membership.user_id == user.id

    memberships = identity.list_user_group_memberships(TENANCY, user_id=user.id).data
    assert len(memberships) == 1

    identity.remove_user_from_group(membership.id)
    identity.delete_user(user.id)
    identity.delete_group(group.id)

    with pytest.raises(oci.exceptions.ServiceError) as err:
        identity.get_user(user.id)
    assert err.value.status == 404
    assert err.value.code == "NotAuthorizedOrNotFound"


def test_policy_lifecycle(identity):
    policy = identity.create_policy(
        oci.identity.models.CreatePolicyDetails(
            compartment_id=TENANCY,
            name=unique("py-policy"),
            description="python policy",
            statements=["Allow group Administrators to manage all-resources in tenancy"],
        )
    ).data
    assert len(policy.statements) == 1
    identity.delete_policy(policy.id)


def test_stale_if_match_is_412(identity):
    user = identity.create_user(
        oci.identity.models.CreateUserDetails(
            compartment_id=TENANCY, name=unique("py-etag-user"), description="u"
        )
    ).data
    with pytest.raises(oci.exceptions.ServiceError) as err:
        identity.update_user(
            user.id,
            oci.identity.models.UpdateUserDetails(description="changed"),
            if_match="stale-etag",
        )
    assert err.value.status == 412
    assert err.value.code == "NoEtagMatch"
    identity.delete_user(user.id)


def test_reference_data(identity):
    regions = identity.list_regions().data
    assert any(r.name == "us-ashburn-1" for r in regions)

    ads = identity.list_availability_domains(TENANCY).data
    assert len(ads) == 3

    tenancy = identity.get_tenancy(TENANCY).data
    assert tenancy.id == TENANCY


def test_pagination_uses_opc_next_page(identity):
    marker = unique("py-page")
    ids = []
    for i in range(3):
        ids.append(
            identity.create_group(
                oci.identity.models.CreateGroupDetails(
                    compartment_id=TENANCY, name=f"{marker}-{i}", description="g"
                )
            ).data.id
        )
    try:
        first = identity.list_groups(TENANCY, limit=2)
        assert len(first.data) == 2
        assert first.next_page
        second = identity.list_groups(TENANCY, limit=2, page=first.next_page)
        assert len(second.data) >= 1
    finally:
        for group_id in ids:
            identity.delete_group(group_id)
