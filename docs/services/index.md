# Services Overview

| Service | API root | Coverage |
|---|---|---|
| [Identity (IAM)](identity.md) | `/20160918/…` | Compartments, users, groups, memberships, policies, reference data, work requests |
| [Object Storage](object-storage.md) | `/n/{ns}/b/{bucket}/o/{object}` | Buckets, objects, listing, rename, copy, multipart, pre-authenticated requests |
| [Queue](queue.md) | `/20210201/…` | Queues (work-request driven), messages with visibility timeouts and DLQ, stats, channels |
| [Vault, KMS & Secrets](kms-vault.md) | `/20180608/…`, `/20190301/…` | Vaults, keys and versions, **real** AES-GCM/RSA/ECDSA crypto, secrets and bundles |
| [Streaming](streaming.md) | `/20180418/…` | Streams, partitioned log, cursors (incl. group cursors), commit/heartbeat |
| [Functions](functions.md) | `/20181201/…` | Applications, functions, real invocation via an `fnproject/fnserver` sidecar |
| [Container Engine for Kubernetes (OKE)](containerengine.md) | `/20180222/…` | Clusters CRUD, Node Pools CRUD, Options, Kubeconfig generation, work requests, real k3s sidecar |

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
FLOCI_OCI_SERVICES_QUEUE_ENABLED=false
FLOCI_OCI_SERVICES_KMS_ENABLED=false
FLOCI_OCI_SERVICES_VAULT_ENABLED=false
FLOCI_OCI_SERVICES_STREAMING_ENABLED=false
FLOCI_OCI_SERVICES_FUNCTIONS_ENABLED=false
FLOCI_OCI_SERVICES_OKE_ENABLED=false
```

Disabled services answer `503 ServiceUnavailable`.
