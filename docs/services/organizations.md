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
| Organization tenancies | ListOrganizationTenancies, GetOrganizationTenancy, DeleteOrganizationTenancy, RestoreOrganizationTenancy |
| Links | ListLinks, GetLink, GetLinkWithTenancyNames, DeleteLink |
| Invitations | CreateSenderInvitation, GetSenderInvitation, ListSenderInvitations, UpdateSenderInvitation, CancelSenderInvitation, GetRecipientInvitation, ListRecipientInvitations, UpdateRecipientInvitation, AcceptRecipientInvitation, IgnoreRecipientInvitation |
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
lists the same organization as its parent. Only the parent can update it, create more
children, terminate or restore a child, or terminate a link: the same calls signed by a child
are `404 NotAuthorizedOrNotFound`.

## Wire notes

- `CreateChildTenancy`, `UpdateOrganization`, `DeleteOrganizationTenancy`,
  `RestoreOrganizationTenancy` and `DeleteLink` return `202` with an `opc-work-request-id`
  header and no body. The work request is already `SUCCEEDED`; for `CreateChildTenancy` its
  `resources[0].identifier` is the new child tenancy OCID.
- The `organizationTenancyId` path parameter of `DeleteOrganizationTenancy` and
  `RestoreOrganizationTenancy` is the child's tenancy OCID. The parent tenancy cannot be
  terminated this way (`400 InvalidParameter`).
- Terminating needs an `ACTIVE` member, restoring a `DELETED` one, and `DeleteLink` an `ACTIVE`
  link; anything else is `409 Conflict`. All three honour `if-match`.
- `CreateSenderInvitation` returns `200` with the invitation, an `etag` and an
  `opc-work-request-id`; `CancelSenderInvitation` and `AcceptRecipientInvitation` return `202`
  with an `opc-work-request-id`; `IgnoreRecipientInvitation` and both updates return the
  invitation with an `etag`. Invitation lists are wrapped and take the SDK's `status` and
  `lifecycleState` enums; `ListRecipientInvitations` has no `limit` or sort, as in the SDK.
- `ListLinks` rejects a `lifecycleState` outside `CREATING`, `ACTIVE`, `INACTIVE`, `UPDATING`,
  `FAILED`, `TERMINATED` with `400 InvalidParameter`.
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
- Termination is immediate and reversible. `DeleteOrganizationTenancy` moves the member to
  `DELETED` with `timeLeft` set and terminates its active links, under the work request type
  `TERMINATE_ORGANIZATION_TENANCY`. The child tenancy stays registered and its data is kept, so
  requests signed with it still work. `RestoreOrganizationTenancy` (`RESTORE_TENANCY`) makes it
  `ACTIVE` again and reactivates the links its termination ended, but not links terminated on
  their own before. Real OCI does not document the termination lifecycle or how long a
  terminated tenancy stays restorable.
- `DeleteLink` moves the link to `TERMINATED` with `timeTerminated` set (`TERMINATE_LINK`),
  for any feature, `CORE` included; the child stays a member of the organization.

### Invitations

The SDKs give the shapes but not the flow, so floci-oci picks:

- The recipient invitation is delivered at once, with its own OCID
  (`ocid1.recipientinvitation…`, the sender's is `ocid1.senderinvitation…`); each points at
  the other. Both start `ACTIVE` with status `PENDING`, and `subjects` defaults to `LINK`.
- Only a tenancy that is not a child can invite (`404` otherwise), and only a tenancy outside
  any organization can be invited or accept (`409 Conflict`). A second pending invitation to
  the same tenancy is also `409 Conflict`; inviting yourself is `400 InvalidParameter`.
- Accepting makes the recipient a child of the sender: it joins the sender's organization
  (created if needed) with a `CORE` link plus one link per invitation feature, and both
  invitations become `ACCEPTED`.
- Ignoring marks the recipient's side `IGNORED`. The sender's side stays `PENDING`, since its
  status enum has no ignored value, until the sender cancels it.
- Cancel, accept and ignore need a `PENDING` invitation (`409 Conflict` otherwise). Accepting
  is also `409 Conflict` when the recipient has joined another organization, or the sender has
  become a child, since the invitation was sent.
- An invitation without `displayName` uses its own OCID as the name, because the summary
  shape requires one.

## Not implemented yet

ApproveForTransfer, UnapproveForTransfer, link features, governance (including governance
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
