# Services Overview

| Service | API root | Coverage |
|---|---|---|
| [Identity (IAM)](identity.md) | `/20160918/…` | Compartments, users, groups, memberships, policies, reference data, work requests |
| [Object Storage](object-storage.md) | `/n/{ns}/b/{bucket}/o/{object}` | Buckets, objects, listing, rename, copy, multipart, pre-authenticated requests |

## Wire contract

Every service follows the OCI wire contract:

- **Errors** are `{"code": "...", "message": "..."}` with the correct HTTP status;
  missing resources report `404 NotAuthorizedOrNotFound` exactly as real OCI does.
- Every response carries an **`opc-request-id`** header (echoing yours if provided),
  and `opc-client-request-id` is echoed back.
- Lists paginate via `limit`/`page` query parameters and the **`opc-next-page`**
  response header. Identity lists return bare JSON arrays.
- Mutations carry **`etag`** headers and honour `if-match` / `if-none-match`.
- Async operations return **202 + `opc-work-request-id`**, pollable via the
  work-request endpoints.

## Disabling a service

```bash
FLOCI_OCI_SERVICES_IDENTITY_ENABLED=false
FLOCI_OCI_SERVICES_OBJECTSTORAGE_ENABLED=false
```

Disabled services answer `503 ServiceUnavailable`.
