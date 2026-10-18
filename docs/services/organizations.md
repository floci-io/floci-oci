# Organizations

OCI Organizations (the Tenant Manager control plane), API version `20230401`. A parent
tenancy creates child tenancies; together they form an organization, and each child is linked
to the parent per feature. Real OCI serves it on `organizations.{region}.oci.{domain}`; floci-oci
serves it on port 4599 with the other services.

## Supported operations

| Area | Operations |
|---|---|
| Child tenancies | CreateChildTenancy |
| Organizations | ListOrganizations, GetOrganization, UpdateOrganization |
| Organization tenancies | ListOrganizationTenancies, GetOrganizationTenancy |
| Links | ListLinks, GetLink, GetLinkWithTenancyNames |
| Work requests | Get, List, errors, logs (`/20230401/workRequests`) |

## How isolation works

A child tenancy is a new tenancy OCID with its own home region. Requests signed with the child
tenancy (its OCID as the first part of the Signature `keyId`) land in that tenancy's own,
empty storage: they see none of the parent's resources, and `GetTenancy` reports the child's
name and home region. This mirrors how floci-aws member accounts work.

A child homed in another region reaches that region by default: a request whose `Host` names
no region (`localhost:4599`) lands in the signing tenancy's home region. Like any tenancy, the
child can use other regions only after subscribing to them.

Organizations, organization tenancies and links are stored with the parent tenancy, so a child
lists the same organization as its parent. Only the parent can update it or create more
children: `CreateChildTenancy` signed by a child is `404 NotAuthorizedOrNotFound`.

## Wire notes

- `CreateChildTenancy` and `UpdateOrganization` return `202` with an `opc-work-request-id`
  header and no body. The work request is already `SUCCEEDED`; its `resources[0].identifier`
  is the new child tenancy OCID.
- Lists are wrapped (`{"items":[…]}`) and paged with `opc-next-page`.
- `GetOrganization`, `GetOrganizationTenancy` and `GetLink` return an `etag`;
  `UpdateOrganization` honours `if-match`.
- `homeRegion` must be a region the parent tenancy is subscribed to (its home region, or one
  added with `CreateRegionSubscription`); otherwise the request fails with `400 InvalidParameter`.
- `compartmentId` on `CreateChildTenancy` and `ListOrganizations` must be the caller's own
  tenancy; another tenancy is `404 NotAuthorizedOrNotFound`.
- A `CORE` link is always created, plus one link per entry in `features`.

## Emulator choices

The OCI SDKs leave these open, so floci-oci picks:

- **Organizations data is global.** Real OCI serves this API on a regional host and does not
  say whether its data is shared across regions; floci-oci keeps organizations, organization
  tenancies, links and their work requests tenancy-wide, like Identity, so every region of the
  tenancy sees the same organization.
- `homeRegion` accepts a region name (`us-phoenix-1`) or a region key (`PHX`).
- `governanceStatus` defaults to `OPTED_IN` when omitted.
- Organizations and links get global OCIDs: `ocid1.organization.<realm>..…` and
  `ocid1.link.<realm>..…`. A child tenancy's OCID uses the realm of its home region.
- When no `subscriptionId` is given, the organization gets a synthetic
  `ocid1.organizationssubscription…` default subscription.

## Not implemented yet

DeleteOrganizationTenancy, RestoreOrganizationTenancy, ApproveForTransfer,
UnapproveForTransfer, DeleteLink, link features, invitations, governance (including governance
rules), domains, subscriptions and orders. The child tenancy limit is not enforced, and no
administrator user or policy is created in the child.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci organizations child-tenancy create $E --compartment-id "$TENANCY" \
  --tenancy-name dev --home-region us-ashburn-1 --admin-email admin@example.com
oci organizations organization list $E --compartment-id "$TENANCY"
oci organizations link list $E
```
