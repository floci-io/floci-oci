# Governance Rules

The OCI Governance Rules control plane, API version `20220504`. A parent tenancy defines rules
for the child tenancies of its [organization](organizations.md): quotas, tags or the regions
children may use. Inclusion criteria pick the children a rule applies to, and a tenancy
attachment records the rule applied to one child. Real OCI serves it on
`governance-rules.organizations.{region}.oci.{domain}`; floci-oci serves it on port 4599 with
the other services.

## Supported operations

| Area | Operations |
|---|---|
| Governance rules | CreateGovernanceRule, GetGovernanceRule, ListGovernanceRules, UpdateGovernanceRule, DeleteGovernanceRule, RetryGovernanceRule |
| Inclusion criteria | CreateInclusionCriterion, GetInclusionCriterion, ListInclusionCriteria, DeleteInclusionCriterion |
| Tenancy attachments | GetTenancyAttachment, ListTenancyAttachments, RetryTenancyAttachment |
| Enforced governance rules | GetEnforcedGovernanceRule, ListEnforcedGovernanceRules |
| Work requests | GetWorkRequest, ListWorkRequests, CancelWorkRequest, ListWorkRequestErrors, ListWorkRequestLogs (`/20220504/workRequests`) |

## Wire notes

- `CreateGovernanceRule` and `CreateInclusionCriterion` return `200` with the resource, an
  `etag`, an `opc-work-request-id` and a `location` header. Update, delete and both retries
  return `202` with an `opc-work-request-id` and no body. Every work request is already
  `SUCCEEDED`.
- Lists are wrapped (`{"items":[…]}`) and paged with `opc-next-page`. Summaries leave out the
  `template`. `sortBy` takes `timeCreated` or `displayName`, `sortOrder` `ASC` or `DESC`.
- `ListGovernanceRules` and `ListTenancyAttachments` need `compartmentId` or
  `governanceRuleId` (`400 MissingParameter` otherwise), as the SDK documents;
  `ListInclusionCriteria` needs `governanceRuleId`.
- The `template` is polymorphic on `type`: `ALLOWED_REGIONS` needs `displayName` and
  `regions` (known region names), `QUOTA` needs `displayName` and `statements`, `TAG` needs
  `name`. A template whose `type` differs from the rule's is `400 InvalidParameter`.
- Rules are created in the root compartment (the tenancy OCID) of a tenancy that is not a
  child. Another compartment is `400 InvalidParameter`.
- `CancelWorkRequest` on a finished work request, and changing a `DELETED` rule or inclusion
  criterion, are `409 Conflict`.
- `GetEnforcedGovernanceRule` returns an `etag`.

## Enforcement

`ALLOWED_REGIONS` is enforced: a child governed by such a rule cannot subscribe to a region
outside its list. `CreateRegionSubscription` answers `400 InvalidParameter` and names the rule.
A child is governed when it is an `ACTIVE` member of the organization, its `governanceStatus` is
`OPTED_IN` (see `AddGovernance` and `RemoveGovernance` in [Organizations](organizations.md)), and
an active inclusion criterion of the rule covers it (`ALL`, or `TENANCY` naming it).

`QUOTA` and `TAG` rules are stored, attached and listed as enforced rules, but have no effect:
floci-oci has no quotas or tagging service to apply them to.

## Emulator choices

The SDKs give the shapes but not the behaviour, so floci-oci picks:

- **Data is global**, like Organizations: rules, criteria and attachments are kept with the
  parent tenancy and seen from every region.
- Tenancy attachments are kept in step with the rules: a child that becomes governed gets an
  `ACTIVE` attachment, and one that stops being governed (it opts out, leaves, or the rule or
  criterion is deleted) has its attachment set to `DELETED`.
- Deleting a rule sets it and its inclusion criteria to `DELETED`; they can still be read.
- An enforced governance rule is the child's view of one attachment: its `compartmentId` is
  the child tenancy, and only the child can read it.
- OCIDs: `ocid1.governancerule…`, `ocid1.inclusioncriterion…`, `ocid1.tenancyattachment…`,
  `ocid1.enforcedgovernancerule…`.
- The corpus does not say how real OCI enforces `ALLOWED_REGIONS` or which error it returns;
  refusing the region subscription with `400 InvalidParameter` is floci-oci's choice.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci governance-rules-control-plane governance-rule create-governance-rule-allowed-regions-template $E \
  --compartment-id "$TENANCY" --display-name only-ashburn --type ALLOWED_REGIONS --creation-option TEMPLATE \
  --template-display-name only-ashburn --template-regions '["us-ashburn-1"]'
oci governance-rules-control-plane governance-rule list $E --compartment-id "$TENANCY"
```
