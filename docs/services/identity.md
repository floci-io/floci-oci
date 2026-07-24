# Identity (IAM)

OCI Identity and Access Management — API version `20160918`.

## Supported operations

| Resource | Operations |
|---|---|
| Compartments | Create, Get, List (incl. `compartmentIdInSubtree`), Update, Delete (async, work request) |
| Users | Create, Get, List, Update, Delete |
| Groups | Create, Get, List, Update, Delete |
| User group memberships | AddUserToGroup, Get, List (by user/group), RemoveUserFromGroup |
| Policies | Create, Get, List, Update, Delete |
| Availability domains | List (3 ADs) |
| Regions / region subscriptions | List |
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
- Policy statements are stored verbatim; the policy language is not parsed or enforced.
- Identity domains, API keys, auth tokens, dynamic groups and tag namespaces are not
  implemented yet.
