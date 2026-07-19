# floci-oci

Fast, free, open-source local **Oracle Cloud Infrastructure (OCI)** emulator — single port, native-image ready, SDK-compatible.

floci-oci is the OCI sibling of [Floci](https://github.com/floci-io/floci) (AWS), [floci-az](https://github.com/floci-io/floci-az) (Azure) and [floci-gcp](https://github.com/floci-io/floci-gcp) (GCP).

| Emulator | Cloud | Port |
|---|---|---|
| floci | AWS | 4566 |
| floci-az | Azure | 4577 |
| floci-gcp | GCP | 4588 |
| **floci-oci** | **OCI** | **4599** |

## Status

Early development. Current milestone targets:

- **Identity** — compartments, users, groups, policies (`/20160918/…`)
- **Object Storage** — namespaces, buckets, objects (`/n/{namespace}/b/{bucket}/o/{object}`)
- **Work Requests** — the OCI async-operation plane

## Quick start

```bash
# Dev mode
./mvnw quarkus:dev

# Or via Docker
docker compose up -d

# Health check
curl http://localhost:4599/_floci-oci/health
```

Point any OCI SDK or the OCI CLI at `http://localhost:4599`:

```bash
oci os ns get --endpoint http://localhost:4599
# or use the bundled wrapper
bin/ocilocal os ns get
```

The emulator parses the OCI request signature for tenancy/user context but does not
verify the RSA signature — any locally generated API key works.

## Build & test

```bash
./mvnw test                    # unit + integration tests
./mvnw clean package           # JVM build
./mvnw clean package -Dnative  # GraalVM native image
```

## Configuration

Configuration lives under the `floci-oci.*` prefix (env: `FLOCI_OCI_*`). Key settings:

| Property | Default | Description |
|---|---|---|
| `floci-oci.port` | `4599` | Listen port |
| `floci-oci.default-region` | `us-ashburn-1` | Region used in OCIDs and responses |
| `floci-oci.default-namespace` | `floci-local` | Object Storage namespace |
| `floci-oci.storage.mode` | `memory` | `memory`, `persistent`, `hybrid`, or `wal` |
| `floci-oci.auth.require-signature` | `false` | Reject unsigned requests with 401 |

## License

MIT
