"""DNS: zones, RRSets with PATCH preconditions, and private views via the OCI Python SDK."""

import time

import oci
import pytest

from conftest import ENDPOINT, TENANCY


@pytest.fixture(scope="module")
def dns(sdk_config):
    return oci.dns.DnsClient(sdk_config, service_endpoint=ENDPOINT)


def test_zone_and_rrset_round_trip(dns):
    name = f"py-{time.time_ns()}.example"
    zone = dns.create_zone(oci.dns.models.CreateZoneDetails(
        name=name, compartment_id=TENANCY, zone_type="PRIMARY")).data
    try:
        assert zone.lifecycle_state == "ACTIVE"
        assert zone.scope == "GLOBAL"
        assert dns.get_zone(name).data.id == zone.id

        records = dns.get_zone_records(zone.id).data.items
        assert {r.rtype for r in records} == {"SOA", "NS"}
        assert all(r.is_protected for r in records)

        domain = "www." + name
        patched = dns.patch_rr_set(zone.id, domain, "AAAA", oci.dns.models.PatchRRSetDetails(items=[
            oci.dns.models.RecordOperation(operation="PROHIBIT", domain=domain, rtype="AAAA"),
            oci.dns.models.RecordOperation(operation="ADD", domain=domain, rtype="AAAA",
                                           rdata="2001:0db8:0000:0000:0000:0000:0000:0001", ttl=60),
        ])).data
        assert [r.rdata for r in patched.items] == ["2001:db8::1"]

        with pytest.raises(oci.exceptions.ServiceError) as failed:
            dns.patch_rr_set(zone.id, domain, "AAAA", oci.dns.models.PatchRRSetDetails(items=[
                oci.dns.models.RecordOperation(operation="PROHIBIT", domain=domain, rtype="AAAA")]))
        assert failed.value.status == 412

        rrset = dns.get_rr_set(zone.id, domain, "AAAA")
        assert rrset.headers["etag"]
        assert len(rrset.data.items) == 1
        assert dns.get_zone(zone.id).data.serial == 2

        dns.delete_rr_set(zone.id, domain, "AAAA")
        assert dns.get_rr_set(zone.id, domain, "AAAA").data.items == []
    finally:
        dns.delete_zone(zone.id)


def test_private_zone_in_view(dns):
    view = dns.create_view(oci.dns.models.CreateViewDetails(
        compartment_id=TENANCY, display_name=f"py-view-{time.time_ns()}")).data
    name = f"py-private-{time.time_ns()}.example"
    zone = dns.create_zone(oci.dns.models.CreateZoneDetails(
        name=name, compartment_id=TENANCY, scope="PRIVATE", view_id=view.id)).data
    try:
        assert zone.view_id == view.id
        assert dns.get_zone(name, scope="PRIVATE", view_id=view.id).data.id == zone.id
        assert view.id in [v.id for v in dns.list_views(TENANCY).data]
        with pytest.raises(oci.exceptions.ServiceError) as conflict:
            dns.delete_view(view.id)
        assert conflict.value.status == 409
    finally:
        dns.delete_zone(zone.id)
        dns.delete_view(view.id)
