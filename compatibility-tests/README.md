# Compatibility Tests

Validates floci-oci against the real OCI SDKs and IaC tooling — not just theoretical
protocol adherence.

| Suite | Tooling | Coverage |
|---|---|---|
| `sdk-test-java` | oci-java-sdk (default suite) | Identity, Object Storage (incl. multipart and work requests), Queue, Streaming, KMS and Vault, OKE, PostgreSQL, Organizations, Governance Rules |
| `sdk-test-python` | oci (Python SDK) | Identity, Object Storage (incl. `UploadManager` streaming), Queue, Streaming, KMS, Vault and Secrets, the Functions management plane, PostgreSQL, Organizations, Governance Rules |
| `sdk-test-cli` | oci-cli (official CLI) + bats | Smoke CRUD across Identity, Object Storage, Queue, Streaming, KMS, Vault and Secrets, Functions, OKE, PostgreSQL and Organizations, incl. error codes and PAR access |
| `compat-terraform` | Terraform + oracle/oci provider + bats | Full apply → no-drift plan → destroy cycle over Identity, Object Storage, Queue, Streaming, KMS, Vault, OKE and PostgreSQL resources, plus an Organizations data source. No Functions: see the note in `main.tf` |
| `compat-opentofu` | OpenTofu + oracle/oci provider + bats | Same cycle as Terraform |
| `sdk-test-go` | oci-go-sdk + gotestsum | OKE cluster lifecycle |

The Java, Python, CLI and Go suites pin their client versions in `sdk-test-java/pom.xml`,
`sdk-test-python/requirements.txt`, `sdk-test-cli/Dockerfile` and `sdk-test-go/go.mod`.

## How the IaC suites reach the emulator

`terraform-provider-oci` has per-client host overrides (no per-service endpoint
attributes like other providers). The suites set:

```bash
TF_VAR_CLIENT_HOST_OVERRIDES="oci_identity.IdentityClient=http://localhost:4599;oci_object_storage.ObjectStorageClient=http://localhost:4599"
```

Each new service exercised by the IaC suites needs its client key added to that list
(keys are the `RegisterOracleClient` names in the provider's `internal/client/*.go`).

## How the CLI suite reaches OKE clusters

The CLI suite runs `kubectl get namespaces` through the exec kubeconfig that
`oci ce cluster create-kubeconfig` returns, so the cluster's API server must be reachable
from the suite's container. `compatibility.yml` and `make compat-docker` therefore run
floci-oci with `FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE=network`, which makes the kubeconfig
point at the k3s sidecar's container name on the shared network. `docker-compose.yml` keeps
`host` unless that variable is set. On a host-local run (`make test-cli-compat`), the case
skips when `kubectl` is not installed.

## Running locally

Against a locally running emulator (`make run` in the repo root):

```bash
make test-java-compat
make test-python-compat      # needs: pip install -r sdk-test-python/requirements.txt
make test-cli-compat         # needs: pip install oci-cli, bats, jq
make test-terraform-compat   # needs: terraform, bats
make test-opentofu-compat    # needs: tofu, bats
```

## Running in Docker

Each suite is a Docker image whose entrypoint runs the tests and writes JUnit XML to
`/results`. From the repo root:

```bash
make compat-docker
```

CI (`.github/workflows/compatibility.yml`) builds the native image once and runs each
suite from the same matrix.
