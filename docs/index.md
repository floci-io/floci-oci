# floci-oci

Fast, free, open-source local **Oracle Cloud Infrastructure (OCI)** emulator — single port, native-image ready, SDK-compatible.

floci-oci implements real OCI wire protocols so the official OCI SDKs, the OCI CLI and other tooling work unchanged against `http://localhost:4599`.

## The Floci family

| Emulator | Cloud | Port |
|---|---|---|
| [floci](https://github.com/floci-io/floci) | AWS | 4566 |
| [floci-az](https://github.com/floci-io/floci-az) | Azure | 4577 |
| [floci-gcp](https://github.com/floci-io/floci-gcp) | GCP | 4588 |
| **floci-oci** | **OCI** | **4599** |

## Supported services

| Service | API | Coverage |
|---|---|---|
| Identity (IAM) | `/20160918/…` | Compartments, users, groups, memberships, policies, availability domains, regions, tenancy, work requests |
| Object Storage | `/n/{ns}/b/{bucket}/o/{object}` | Namespaces, buckets, objects, listing, rename, copy, multipart uploads, pre-authenticated requests, work requests |

## Quick start

```bash
docker compose up -d
curl http://localhost:4599/_floci-oci/health
oci os ns get --endpoint http://localhost:4599
```

See the [Quick Start](getting-started/quick-start.md) for details.
