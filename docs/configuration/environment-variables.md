# Environment Variables

All configuration lives under the `floci-oci.*` prefix; every property maps to an
`FLOCI_OCI_*` environment variable via the standard MicroProfile Config rules
(dots and dashes become underscores, uppercase).

| Variable | Default | Description |
|---|---|---|
| `FLOCI_OCI_PORT` | `4599` | Listen port |
| `FLOCI_OCI_BASE_URL` | `http://localhost:4599` | Base URL used in returned URLs |
| `FLOCI_OCI_HOSTNAME` | – | Overrides the hostname in returned URLs (multi-container setups) |
| `FLOCI_OCI_DEFAULT_REGION` | `us-ashburn-1` | Home region of the tenancy, and the region for requests whose `Host` names no OCI region |
| `FLOCI_OCI_DEFAULT_REALM` | `oc1` | Realm key for OCIDs when the region is not a known OCI region; known regions use their own realm (e.g. `uk-gov-london-1` is `oc4`) |
| `FLOCI_OCI_DEFAULT_TENANCY_ID` | `ocid1.tenancy.oc1..flocilocal…` | Tenancy used for unsigned requests |
| `FLOCI_OCI_DEFAULT_NAMESPACE` | `floci-local` | Object Storage namespace |
| `FLOCI_OCI_MAX_REQUEST_SIZE` | `2048` | Max request body size in MB |
| `FLOCI_OCI_STORAGE_MODE` | `memory` | `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_OCI_STORAGE_PERSISTENT_PATH` | `./data` | Where persisted state is written |
| `FLOCI_OCI_AUTH_REQUIRE_SIGNATURE` | `false` | Reject unsigned requests with 401 |
| `FLOCI_OCI_TLS_ENABLED` | `false` | Serve HTTPS + HTTP on the same port |
| `FLOCI_OCI_TLS_HTTPS_PORT` | `443` | Extra HTTPS binding for clients that assume 443 (0 disables) |
| `FLOCI_OCI_SERVICES_IDENTITY_ENABLED` | `true` | Enable/disable Identity |
| `FLOCI_OCI_SERVICES_IDENTITY_STRICT_COMPARTMENTS` | `false` | Reject `compartmentId` values that are not an ACTIVE Identity compartment (or the tenancy) in other services: 400 `RelatedResourceNotAuthorizedOrNotFound` in a request body, 404 `NotAuthorizedOrNotFound` in a query |
| `FLOCI_OCI_SERVICES_OBJECTSTORAGE_ENABLED` | `true` | Enable/disable Object Storage |
| `FLOCI_OCI_SERVICES_QUEUE_ENABLED` | `true` | Enable/disable Queue |
| `FLOCI_OCI_SERVICES_KMS_ENABLED` | `true` | Enable/disable KMS (vaults, keys, crypto) |
| `FLOCI_OCI_SERVICES_VAULT_ENABLED` | `true` | Enable/disable Vault secrets + bundles |
| `FLOCI_OCI_SERVICES_STREAMING_ENABLED` | `true` | Enable/disable Streaming |
| `FLOCI_OCI_SERVICES_FUNCTIONS_ENABLED` | `true` | Enable/disable Functions |
| `FLOCI_OCI_SERVICES_FUNCTIONS_MOCK` | `false` | Skip the Fn sidecar; invocations return a synthetic body |
| `FLOCI_OCI_SERVICES_FUNCTIONS_SERVER_IMAGE` | `fnproject/fnserver:latest` | Fn Project server image |
| `FLOCI_OCI_SERVICES_OKE_ENABLED` | `true` | Enable/disable Container Engine for Kubernetes (OKE) |
| `FLOCI_OCI_SERVICES_OKE_MOCK` | `false` | Skip the k3s sidecar; clusters become `ACTIVE` at once with synthetic endpoints |
| `FLOCI_OCI_SERVICES_OKE_DEFAULT_IMAGE` | `rancher/k3s:v1.30.1-k3s1` | k3s image for cluster sidecars |
| `FLOCI_OCI_SERVICES_OKE_API_SERVER_BASE_PORT` | `6443` | First host port for cluster API servers |
| `FLOCI_OCI_SERVICES_OKE_API_SERVER_MAX_PORT` | `6543` | Last host port for cluster API servers |
| `FLOCI_OCI_SERVICES_OKE_KUBECONFIG_AUTH` | `exec` | Kubeconfig user: `exec` (real OKE `oci ce cluster generate-token`) or `token` (static bearer token) |
| `FLOCI_OCI_SERVICES_OKE_READY_TIMEOUT_SECONDS` | `300` | How long a cluster may stay `CREATING` before it goes `FAILED` |
| `FLOCI_OCI_SERVICES_DOCKER_NETWORK` | – | Shared Docker network for sidecar containers |
| `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE` | – | Namespace inserted into sidecar container/volume names (`floci-oci-<ns>-…`) so parallel emulator instances on one Docker host don't collide |

Per-service storage overrides use the map form:

```bash
FLOCI_OCI_STORAGE_SERVICES_OBJECTSTORAGE_MODE=wal
FLOCI_OCI_STORAGE_SERVICES_OBJECTSTORAGE_FLUSH_INTERVAL_MS=5000
```

## Resource identity labels

Every container and volume floci-oci creates carries the base labels `floci=true`,
`floci_emulator=floci-oci` and, when `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE` is set,
`floci_namespace`. Function containers that `fnserver` starts itself are not labelled. A container backing an emulated OCI resource also carries labels tying
it back to that resource, additive to the base labels:

| Label | Value | Purpose |
|---|---|---|
| `io.floci` | `oci` | Cloud provider, for multi-cloud discovery when several Floci emulators share a host |
| `io.floci.service` | e.g. `oke` | The OCI service the container backs |
| `io.floci.resource-id` | e.g. `ocid1.cluster.oc1.iad.…` | The resource OCID, as the OCI CLI and SDKs take it |
| `io.floci.compartment` | the compartment OCID | The compartment the resource belongs to |
| `io.floci.region` | e.g. `us-ashburn-1` | The region the resource belongs to (`FLOCI_OCI_DEFAULT_REGION`) |
| `floci_service` | same value as `io.floci.service` | Legacy alias, written alongside `io.floci.service`; prefer the new key |

This makes `docker ps --filter label=io.floci.resource-id=<cluster-ocid>` resolve an emulated
resource to its backing container directly. Applied to Container Engine (OKE) clusters and
Functions. The Functions `fnserver` container is a shared singleton serving every application,
so it carries every label except `io.floci.resource-id` and `io.floci.compartment`.

The `io.floci.*` key set is shared with floci-aws, floci-gcp and floci-az, so
`docker ps --filter label=io.floci` lists every resource-backing container on a shared host.
