# Compatibility Tests

Validates floci-oci against the real OCI SDKs and IaC tooling — not just theoretical
protocol adherence.

| Suite | Tooling | Coverage |
|---|---|---|
| `sdk-test-java` | oci-java-sdk (default suite) | Identity + Object Storage, incl. multipart and work requests |
| `sdk-test-python` | oci (Python SDK) | Identity + Object Storage, incl. `UploadManager` streaming |
| `compat-terraform` | Terraform + oracle/oci provider + bats | Full apply → no-drift plan → destroy cycle |
| `compat-opentofu` | OpenTofu + oracle/oci provider + bats | Same cycle as Terraform |

Planned: `sdk-test-go` (oci-go-sdk), `sdk-test-oci-cli` (bats).

## How the IaC suites reach the emulator

`terraform-provider-oci` has per-client host overrides (no per-service endpoint
attributes like other providers). The suites set:

```bash
TF_VAR_CLIENT_HOST_OVERRIDES="oci_identity.IdentityClient=http://localhost:4599;oci_object_storage.ObjectStorageClient=http://localhost:4599"
```

Each new service exercised by the IaC suites needs its client key added to that list
(keys are the `RegisterOracleClient` names in the provider's `internal/client/*.go`).

## Running locally

Against a locally running emulator (`make run` in the repo root):

```bash
make test-java-compat
make test-python-compat      # needs: pip install -r sdk-test-python/requirements.txt
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
