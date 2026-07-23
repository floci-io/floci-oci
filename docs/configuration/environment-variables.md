# Environment Variables

All configuration lives under the `floci-oci.*` prefix; every property maps to an
`FLOCI_OCI_*` environment variable via the standard MicroProfile Config rules
(dots and dashes become underscores, uppercase).

| Variable | Default | Description |
|---|---|---|
| `FLOCI_OCI_PORT` | `4599` | Listen port |
| `FLOCI_OCI_BASE_URL` | `http://localhost:4599` | Base URL used in returned URLs |
| `FLOCI_OCI_HOSTNAME` | – | Overrides the hostname in returned URLs (multi-container setups) |
| `FLOCI_OCI_DEFAULT_REGION` | `us-ashburn-1` | Region for OCIDs and reference data |
| `FLOCI_OCI_DEFAULT_REALM` | `oc1` | Realm key used when minting OCIDs |
| `FLOCI_OCI_DEFAULT_TENANCY_ID` | `ocid1.tenancy.oc1..flocilocal…` | Tenancy used for unsigned requests |
| `FLOCI_OCI_DEFAULT_NAMESPACE` | `floci-local` | Object Storage namespace |
| `FLOCI_OCI_MAX_REQUEST_SIZE` | `2048` | Max request body size in MB |
| `FLOCI_OCI_STORAGE_MODE` | `memory` | `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_OCI_STORAGE_PERSISTENT_PATH` | `./data` | Where persisted state is written |
| `FLOCI_OCI_AUTH_REQUIRE_SIGNATURE` | `false` | Reject unsigned requests with 401 |
| `FLOCI_OCI_TLS_ENABLED` | `false` | Serve HTTPS + HTTP on the same port |
| `FLOCI_OCI_TLS_HTTPS_PORT` | `443` | Extra HTTPS binding for clients that assume 443 (0 disables) |
| `FLOCI_OCI_SERVICES_IDENTITY_ENABLED` | `true` | Enable/disable Identity |
| `FLOCI_OCI_SERVICES_OBJECTSTORAGE_ENABLED` | `true` | Enable/disable Object Storage |
| `FLOCI_OCI_SERVICES_DOCKER_NETWORK` | – | Shared Docker network for sidecar containers |

Per-service storage overrides use the map form:

```bash
FLOCI_OCI_STORAGE_SERVICES_OBJECTSTORAGE_MODE=wal
FLOCI_OCI_STORAGE_SERVICES_OBJECTSTORAGE_FLUSH_INTERVAL_MS=5000
```
