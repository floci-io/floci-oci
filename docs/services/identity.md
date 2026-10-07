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
- Region subscriptions complete immediately with status `READY`. The home region is the
  configured `default-region`.
- Policy statements are stored verbatim; the policy language is not parsed or enforced.
- Identity domains, API keys, auth tokens, dynamic groups and tag namespaces are not
  implemented yet.
