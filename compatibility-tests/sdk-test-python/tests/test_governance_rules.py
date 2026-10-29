"""Governance Rules control plane validated through the real oci Python SDK."""

import time

import oci

from conftest import ENDPOINT


def test_allowed_regions_rule_lifecycle(sdk_config):
    parent = f"ocid1.tenancy.oc1..pygov{time.time_ns()}"
    config = dict(sdk_config, tenancy=parent)
    rules = oci.governance_rules_control_plane.GovernanceRuleClient(config, service_endpoint=ENDPOINT)
    work_requests = oci.governance_rules_control_plane.WorkRequestClient(config, service_endpoint=ENDPOINT)
    models = oci.governance_rules_control_plane.models

    created = rules.create_governance_rule(
        models.CreateGovernanceRuleDetails(
            compartment_id=parent,
            display_name="only-ashburn",
            type="ALLOWED_REGIONS",
            creation_option="TEMPLATE",
            template=models.AllowedRegionsTemplate(display_name="only-ashburn", regions=["us-ashburn-1"]),
        )
    )
    assert created.headers["etag"]
    rule = created.data
    assert isinstance(rule.template, models.AllowedRegionsTemplate)
    assert rule.template.regions == ["us-ashburn-1"]

    work_request = work_requests.get_work_request(created.headers["opc-work-request-id"]).data
    assert work_request.status == "SUCCEEDED"
    assert work_request.operation_type == "CREATE_GOVERNANCE_RULE"

    rules.create_inclusion_criterion(
        models.CreateInclusionCriterionDetails(governance_rule_id=rule.id, type="ALL")
    )
    criteria = rules.list_inclusion_criteria(governance_rule_id=rule.id).data.items
    assert [c.type for c in criteria] == ["ALL"]

    listed = rules.list_governance_rules(compartment_id=parent).data.items
    assert [r.id for r in listed] == [rule.id]

    rules.delete_governance_rule(rule.id)
    assert rules.get_governance_rule(rule.id).data.lifecycle_state == "DELETED"
