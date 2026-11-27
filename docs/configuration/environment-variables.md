# Environment Variables

All configuration lives under the `floci-oci.*` prefix; every property maps to an
`FLOCI_OCI_*` environment variable via the standard MicroProfile Config rules
(dots and dashes become underscores, uppercase).

| Variable | Default | Description |
|---|---|---|
| `FLOCI_OCI_PORT` | `4599` | Listen port |
| `FLOCI_OCI_BASE_URL` | `http://localhost:4599` | Base URL used in returned URLs |
| `FLOCI_OCI_HOSTNAME` | – | Overrides the hostname in returned URLs (multi-container setups) |
| `FLOCI_OCI_DEFAULT_REGION` | `us-ashburn-1` | Home region given to a tenancy the first time it is seen, which is also where its requests land when the `Host` names no OCI region |
| `FLOCI_OCI_DEFAULT_REALM` | `oc1` | Realm key for OCIDs when the region is not a known OCI region; known regions use their own realm (e.g. `uk-gov-london-1` is `oc4`) |
| `FLOCI_OCI_DEFAULT_TENANCY_ID` | `ocid1.tenancy.oc1..flocilocal…` | Tenancy used for unsigned requests |
| `FLOCI_OCI_DEFAULT_NAMESPACE` | `floci-local` | Object Storage namespace |
| `FLOCI_OCI_MAX_REQUEST_SIZE` | `2048` | Max request body size in MB |
| `FLOCI_OCI_STORAGE_MODE` | `memory` | `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_OCI_STORAGE_PERSISTENT_PATH` | `./data` | Where persisted state is written |
| `FLOCI_OCI_AUTH_REQUIRE_SIGNATURE` | `false` | Reject unsigned requests with 401. `/health`, `/_floci/*`, `/_floci-oci/*`, pre-authenticated requests (`/p/*`) and the OCIR registry protocol (`/v2/*` and its token endpoint) are always exempt |
| `FLOCI_OCI_AUTH_SIGNATURE_VERIFICATION` | `off` | Verify OCI request signatures: `off` (keyId parsed only), `known-keys` (security tokens and uploaded or trusted API keys must verify; unknown API keys pass unverified) or `strict` (every API key must be known and verify, and OCIR `docker login` takes an Identity auth token as its password). Failures are `401 NotAuthenticated`. See [Request signing](../services/identity.md#request-signing) |
| `FLOCI_OCI_AUTH_TRUSTED_PUBLIC_KEYS` | – | Comma-separated PEM files (public, or unencrypted private) whose key verifies any keyId with that key's fingerprint, whatever tenancy and user it names. Bootstraps the first `UploadApiKey` in `strict` mode. An unreadable file stops startup |
| `FLOCI_OCI_TLS_ENABLED` | `false` | Serve HTTPS + HTTP on the same port |
| `FLOCI_OCI_TLS_HTTPS_PORT` | `443` | Extra HTTPS binding for clients that assume 443 (0 disables) |
| `FLOCI_OCI_DNS_HOST_PORT` | – | When floci-oci runs on the host, the embedded DNS server also answers on `127.0.0.1:<port>` (for example `dig @127.0.0.1 -p 5354 app.example.internal`). Ignored in a container, where it serves port 53 to the sidecars |
| `FLOCI_OCI_SERVICES_IDENTITY_ENABLED` | `true` | Enable/disable Identity |
| `FLOCI_OCI_SERVICES_OBJECTSTORAGE_ENABLED` | `true` | Enable/disable Object Storage |
| `FLOCI_OCI_SERVICES_OBJECTSTORAGE_LIFECYCLE_SWEEP_INTERVAL_SECONDS` | `60` | How often object lifecycle policies run |
| `FLOCI_OCI_SERVICES_QUEUE_ENABLED` | `true` | Enable/disable Queue |
| `FLOCI_OCI_SERVICES_NETWORKING_ENABLED` | `true` | Enable/disable Networking (VCNs, subnets, gateways, route tables, security lists, NSGs, DHCP options) |
| `FLOCI_OCI_SERVICES_KMS_ENABLED` | `true` | Enable/disable KMS (vaults, keys, crypto) |
| `FLOCI_OCI_SERVICES_VAULT_ENABLED` | `true` | Enable/disable Vault secrets + bundles |
| `FLOCI_OCI_SERVICES_STREAMING_ENABLED` | `true` | Enable/disable Streaming |
| `FLOCI_OCI_SERVICES_STREAMING_MOCK` | `false` | Skip the Redpanda sidecars: streams keep their messages in floci-oci and pools have no Kafka endpoint. Set it when no Docker daemon is available |
| `FLOCI_OCI_SERVICES_STREAMING_KAFKA_IMAGE` | `redpandadata/redpanda:v24.2.7` | Redpanda image for stream pool brokers |
| `FLOCI_OCI_SERVICES_STREAMING_KAFKA_BASE_PORT` | `19092` | First host port for stream pool Kafka endpoints |
| `FLOCI_OCI_SERVICES_STREAMING_KAFKA_MAX_PORT` | `19191` | Last host port for stream pool Kafka endpoints |
| `FLOCI_OCI_SERVICES_STREAMING_READY_TIMEOUT_SECONDS` | `60` | How long a stream pool's broker may take to start before the pool goes `FAILED` |
| `FLOCI_OCI_SERVICES_ORGANIZATIONS_ENABLED` | `true` | Enable/disable Organizations (child tenancies, organizations, links) |
| `FLOCI_OCI_SERVICES_GOVERNANCERULES_ENABLED` | `true` | Enable/disable Governance Rules (rules, inclusion criteria, tenancy attachments) |
| `FLOCI_OCI_SERVICES_FUNCTIONS_ENABLED` | `true` | Enable/disable Functions |
| `FLOCI_OCI_SERVICES_EVENTS_ENABLED` | `true` | Enable/disable Events (rules, event delivery to Functions, Streaming and Notifications) |
| `FLOCI_OCI_SERVICES_LOGGING_ENABLED` | `true` | Enable/disable Logging (log groups, logs, ingestion, search) |
| `FLOCI_OCI_SERVICES_NOSQL_ENABLED` | `true` | Enable/disable NoSQL Database (tables, indexes, rows, queries) |
| `FLOCI_OCI_SERVICES_NOSQL_TTL_SWEEP_INTERVAL_SECONDS` | `60` | Seconds between sweeps that delete rows whose time to live has passed |
| `FLOCI_OCI_SERVICES_DNS_ENABLED` | `true` | Enable/disable DNS (zones, records, views) |
| `FLOCI_OCI_SERVICES_MONITORING_ENABLED` | `true` | Enable/disable Monitoring (metrics, alarms, suppressions) |
| `FLOCI_OCI_SERVICES_MONITORING_ALARM_EVALUATION_INTERVAL_SECONDS` | `60` | Seconds between alarm evaluations (OCI evaluates once a minute) |
| `FLOCI_OCI_SERVICES_NOTIFICATIONS_ENABLED` | `true` | Enable/disable Notifications (topics, subscriptions, message delivery) |
| `FLOCI_OCI_SERVICES_FUNCTIONS_MOCK` | `false` | Skip the Fn sidecar; invocations return a synthetic body |
| `FLOCI_OCI_SERVICES_FUNCTIONS_SERVER_IMAGE` | `fnproject/fnserver:latest` | Fn Project server image |
| `FLOCI_OCI_SERVICES_ARTIFACTS_ENABLED` | `true` | Enable/disable Container Registry (OCIR) |
| `FLOCI_OCI_SERVICES_ARTIFACTS_MOCK` | `false` | Skip the registry sidecar; image lists are empty and `/v2/` answers 503 |
| `FLOCI_OCI_SERVICES_ARTIFACTS_REGISTRY_IMAGE` | `registry:2` | Docker Distribution image for the OCIR data plane |
| `FLOCI_OCI_SERVICES_OKE_ENABLED` | `true` | Enable/disable Container Engine for Kubernetes (OKE) |
| `FLOCI_OCI_SERVICES_OKE_MOCK` | `false` | Skip the k3s sidecar; clusters become `ACTIVE` at once with synthetic endpoints |
| `FLOCI_OCI_SERVICES_OKE_DEFAULT_IMAGE` | `rancher/k3s:v1.30.1-k3s1` | k3s image for cluster sidecars |
| `FLOCI_OCI_SERVICES_OKE_API_SERVER_BASE_PORT` | `6443` | First host port for cluster API servers |
| `FLOCI_OCI_SERVICES_OKE_API_SERVER_MAX_PORT` | `6543` | Last host port for cluster API servers |
| `FLOCI_OCI_SERVICES_OKE_KUBECONFIG_AUTH` | `exec` | Kubeconfig user: `exec` (real OKE `oci ce cluster generate-token`) or `token` (static bearer token). Any other value stops startup while OKE is enabled |
| `FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE` | `host` | Cluster API address in `endpoints.kubernetes` and the kubeconfig: `host` (`https://127.0.0.1:<hostPort>`, for the Docker host) or `network` (the k3s sidecar's container name, for other containers on floci-oci's network; only when floci-oci runs in a container). Any other value stops startup while OKE is enabled |
| `FLOCI_OCI_SERVICES_OKE_READY_TIMEOUT_SECONDS` | `300` | How long a cluster may stay `CREATING` before it goes `FAILED` |
| `FLOCI_OCI_SERVICES_CONTAINERINSTANCES_ENABLED` | `true` | Enable/disable Container Instances |
| `FLOCI_OCI_SERVICES_CONTAINERINSTANCES_MOCK` | `false` | Start no containers; container instances become `ACTIVE` at once and their logs are empty |
| `FLOCI_OCI_SERVICES_CONTAINERINSTANCES_PAUSE_IMAGE` | `registry.k8s.io/pause:3.10` | Image of the container that holds each container instance's network namespace |
| `FLOCI_OCI_SERVICES_PSQL_ENABLED` | `true` | Enable/disable Database with PostgreSQL |
| `FLOCI_OCI_SERVICES_PSQL_MOCK` | `false` | Skip the PostgreSQL sidecar; DB systems become `ACTIVE` at once with nothing listening on their endpoint |
| `FLOCI_OCI_SERVICES_PSQL_IMAGE` | `postgres:{major}-alpine` | PostgreSQL image for DB system sidecars; `{major}` is replaced with the major of the DB system's `dbVersion` |
| `FLOCI_OCI_SERVICES_PSQL_BASE_PORT` | `15432` | First host port for DB system endpoints |
| `FLOCI_OCI_SERVICES_PSQL_MAX_PORT` | `15531` | Last host port for DB system endpoints |
| `FLOCI_OCI_SERVICES_PSQL_READY_TIMEOUT_SECONDS` | `120` | How long a DB system may take to accept connections before it goes `FAILED` |
| `FLOCI_OCI_SERVICES_MYSQL_ENABLED` | `true` | Enable/disable MySQL HeatWave |
| `FLOCI_OCI_SERVICES_MYSQL_MOCK` | `false` | Skip the MySQL sidecar; DB systems become `ACTIVE` at once with nothing listening on their endpoint |
| `FLOCI_OCI_SERVICES_MYSQL_IMAGE` | `mysql:{version}` | MySQL image for DB system sidecars; `{version}` is replaced with the DB system's `mysqlVersion` |
| `FLOCI_OCI_SERVICES_MYSQL_BASE_PORT` | `13306` | First host port for MySQL DB system endpoints |
| `FLOCI_OCI_SERVICES_MYSQL_MAX_PORT` | `13405` | Last host port for MySQL DB system endpoints |
| `FLOCI_OCI_SERVICES_MYSQL_READY_TIMEOUT_SECONDS` | `180` | How long a MySQL DB system may take to accept connections before it goes `FAILED` |
| `FLOCI_OCI_SERVICES_DATABASE_ENABLED` | `true` | Enable/disable Autonomous Database |
| `FLOCI_OCI_SERVICES_DATABASE_MOCK` | `false` | Emulate Autonomous Databases without containers: they become `AVAILABLE` at once. Until the Autonomous Database Free sidecar lands, `CreateAutonomousDatabase` answers `501 MethodNotImplemented` when this is `false` |
| `FLOCI_OCI_SERVICES_DATABASE_IMAGE` | `container-registry.oracle.com/database/adb-free:latest-26ai` | Autonomous Database Free image for the coming sidecar (about 12 GB) |
| `FLOCI_OCI_SERVICES_DATABASE_BASE_PORT` | `11522` | First host port for Autonomous Database mTLS listeners (coming sidecar) |
| `FLOCI_OCI_SERVICES_DATABASE_MAX_PORT` | `11621` | Last host port for Autonomous Database mTLS listeners (coming sidecar) |
| `FLOCI_OCI_SERVICES_DATABASE_READY_TIMEOUT_SECONDS` | `600` | How long an Autonomous Database may take to become healthy (coming sidecar) |
| `FLOCI_OCI_SERVICES_DOCKER_NETWORK` | – | Shared Docker network for sidecar containers |
| `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE` | – | Namespace inserted into sidecar container/volume names (`floci-oci-<ns>-…`) so parallel emulator instances on one Docker host don't collide |
| `FLOCI_OCI_DOCKER_IMAGE_PULL_TIMEOUT_SECONDS` | `300` | How long one sidecar image pull may take before the start fails. Raise it for large images on a slow connection, or pull the image beforehand |
| `FLOCI_OCI_DOCKER_ENDPOINT_MODE` | `auto` | How a sidecar's published port is addressed in the endpoints returned to clients: `auto` (`localhost` when floci-oci runs on the host, the sidecar's container name when it runs in a container) or `published` (always the Docker daemon's host and the published port; for a remote daemon, or host-side clients of a containerised floci-oci). Only services that build their client endpoint from this setting follow it: OKE cluster endpoints do not, and are always `127.0.0.1` |

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
| `io.floci.region` | e.g. `us-ashburn-1` | The region the resource belongs to (an OKE cluster's own region). The shared Fn server serves every region and carries no region label |
| `floci_service` | same value as `io.floci.service` | Legacy alias, written alongside `io.floci.service`; prefer the new key |

This makes `docker ps --filter label=io.floci.resource-id=<cluster-ocid>` resolve an emulated
resource to its backing container directly. Applied to Container Engine (OKE) clusters and
Functions. The Functions `fnserver` container is a shared singleton serving every application,
so it carries every label except `io.floci.resource-id` and `io.floci.compartment`.

The `io.floci.*` key set is shared with floci-aws, floci-gcp and floci-az, so
`docker ps --filter label=io.floci` lists every resource-backing container on a shared host.
