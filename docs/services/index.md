# Services Overview

| Service | API root | Coverage |
|---|---|---|
| [Identity (IAM)](identity.md) | `/20160918/…` | Compartments, users, groups, memberships, policies, reference data, work requests |
| [Object Storage](object-storage.md) | `/n/{ns}/b/{bucket}/o/{object}` | Buckets, objects, listing, rename, copy, batch delete, multipart, pre-authenticated requests |
| [Queue](queue.md) | `/20210201/…` | Queues (work-request driven), messages with visibility timeouts and DLQ, stats, channels |
| [Vault, KMS & Secrets](kms-vault.md) | `/20180608/…`, `/20190301/…` | Vaults, keys and versions, **real** AES-GCM/RSA/ECDSA crypto, secrets and bundles |
| [Streaming](streaming.md) | `/20180418/…` | Streams, partitioned log, cursors (incl. group cursors), commit/heartbeat |
| [Functions](functions.md) | `/20260325/…`, `/20181201/…` | Applications, functions, real invocation via an `fnproject/fnserver` sidecar |
| [Container Engine for Kubernetes (OKE)](containerengine.md) | `/20180222/…` | Clusters CRUD, Node Pools CRUD, Options, Kubeconfig generation, work requests, real k3s sidecar |
| [Database with PostgreSQL](psql.md) | `/20220915/…` | DB systems CRUD, start/stop/restart, password reset, connection details with a CA certificate, shapes, custom configurations, default configurations, backups, restore and point-in-time recovery, instance patches, failover and replicas, Kerberos and insight settings, work requests, real PostgreSQL sidecar |
| [Organizations](organizations.md) | `/20230401/…` | Child tenancies with their own home region and isolated storage, organizations, organization tenancies, transfer approval, links and link features, invitations, governance opt-in, domains and domain governance, subscriptions and subscription mappings, orders, work requests |
| [Governance Rules](governance-rules.md) | `/20220504/…` | Governance rules (quota, tag, allowed regions), inclusion criteria, tenancy attachments, enforced rules, work requests; allowed regions enforced on region subscriptions |

## Wire contract

Every service follows the OCI wire contract:

- **Errors** are `{"code": "...", "message": "..."}` with the correct HTTP status;
  missing resources report `404 NotAuthorizedOrNotFound` exactly as real OCI does.
- Every response carries an **`opc-request-id`** header (echoing yours if provided),
  and `opc-client-request-id` is echoed back.
- Lists paginate via `limit`/`page` query parameters and the **`opc-next-page`**
  response header. Identity lists return bare JSON arrays.
- Mutations carry **`etag`** headers and honour `if-match` / `if-none-match`.
- Writes honour **`opc-retry-token`**: a repeat of a successful request with the same token, within
  24 hours, returns the first response (status, headers and body, with a request id of its own) instead
  of running the action again, so an SDK retry after a lost response never makes a second resource.
  A token is scoped to the tenancy, region, method and path. Reusing it with a different request is
  `409 Conflict` (OCI does not document this case for these services; that answer is floci-oci's
  choice), field order in the body does not count as different, and a request that fails does not use
  its token up. Object Storage, Functions and Secrets declare no retry token, and their binary uploads
  are never replayed. Once the resource a token created is deleted (any successful `DELETE` of its
  OCID), a retry with that token is `409 InvalidatedRetryToken`, as in real OCI. A delete answered with a
  work request counts once that work request has `SUCCEEDED`: while it runs, or if it `FAILED` (a
  compartment that still holds resources), the token keeps replaying. Deletes that do not go
  through such a request, like a cascade from a parent or KMS and Vault scheduled deletion, do not
  invalidate the token, and those retries keep replaying.
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
FLOCI_OCI_SERVICES_PSQL_ENABLED=false
```

Disabled services answer `503 ServiceUnavailable`.
