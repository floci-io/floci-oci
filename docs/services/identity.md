# Identity (IAM)

OCI Identity and Access Management — API version `20160918`.

## Supported operations

| Resource | Operations |
|---|---|
| Compartments | Create, Get, List (`compartmentIdInSubtree`, `name`, `lifecycleState`, `sortBy`, `sortOrder`, `accessLevel`), Update, Delete and Move (async, work request), Recover |
| Users | Create, Get, List, Update, Delete |
| Groups | Create, Get, List, Update, Delete |
| User group memberships | AddUserToGroup, Get, List (by user/group), RemoveUserFromGroup |
| Policies | Create, Get, List, Update, Delete |
| Availability domains | List (3 ADs) |
| Fault domains | List (3 per AD) |
| Regions | List (every region in the configured realm) |
| Region subscriptions | List, Create |
| Tenancies | Get |
| Work requests | Get, List |

## Quickstart

```bash
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci iam compartment create --endpoint http://localhost:4599 \
  --compartment-id "$TENANCY" --name dev --description "Development"

oci iam compartment list --endpoint http://localhost:4599 --compartment-id "$TENANCY"
```

## Notes & limitations

- The root compartment is the tenancy itself; `GET /compartments/{tenancyOcid}` returns it.
- Deleting a compartment is asynchronous, exactly like real OCI: `202` +
  `opc-work-request-id`, terminal status `SUCCEEDED`.
- `compartmentIdInSubtree=true` is only accepted on the tenancy (root compartment), as on
  real OCI. `accessLevel` is validated but not enforced: every compartment is accessible.
- Region subscriptions complete immediately with status `READY`. Each tenancy has its own home
  region, reported by `GetTenancy` as `homeRegionKey`: a tenancy seen for the first time gets
  `default-region` and keeps it, so changing `default-region` later does not move an existing
  tenancy's home.
- Other services accept any `compartmentId` by default. Set
  `FLOCI_OCI_SERVICES_IDENTITY_STRICT_COMPARTMENTS=true` to require an ACTIVE compartment
  created here (or the tenancy), as real OCI does.
- As on real OCI, compartments, users, groups, group memberships and policies can only be
  created, updated or deleted through the home region's endpoint. A write sent with another
  region in the `Host` header returns `403 NotAllowed` ("This operation must be directed at the
  home region."). Reads work in every region, and changes are visible everywhere at once (real
  OCI takes a few minutes to propagate them). Clients that never set a region host use
  `default-region`, which is the default home, so they are unaffected. The exception is a
  `default-region` changed after a tenancy was registered: the tenancy keeps its saved home, so
  its writes must then name that home region in the `Host` header.
  `CreateRegionSubscription` is accepted in every region: the SDKs do not tie it to the home
  region, so floci-oci does not either.
- Policy statements are stored verbatim; the policy language is not parsed or enforced.
- Identity domains, API keys, auth tokens, dynamic groups and tag namespaces are not
  implemented yet.
