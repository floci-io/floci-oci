# floci-oci

<p align="center">
  <img src="assets/floci.svg" alt="floci-oci" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free — now for Oracle Cloud</em></p>

---

floci-oci is a fast, free, and open-source local **Oracle Cloud Infrastructure (OCI)** emulator built for developers who need reliable OCI services in development and CI without cost, complexity, or vendor lock-in.

It implements real OCI wire protocols so the official OCI SDKs, the OCI CLI, Terraform, and OpenTofu work unchanged against `http://localhost:4599`.

## The Floci family

| Emulator                                               | Cloud | Port |
|--------------------------------------------------------|---|---|
| [floci](https://github.com/floci-io/floci)             | AWS | 4566 |
| [floci-az](https://github.com/floci-io/floci-az)       | Azure | 4577 |
| [floci-gcp](https://github.com/floci-io/floci-gcp)     | GCP | 4588 |
| **[floci-oci](https://github.com/floci-io/floci-oci)** | **OCI** | **4599** |

## Supported services

| Service | API | Coverage |
|---|---|---|
| Identity (IAM) | `/20160918/…` | Compartments, users, groups, memberships, policies, availability domains, regions, tenancy, work requests |
| Object Storage | `/n/{ns}/b/{bucket}/o/{object}` | Namespaces, buckets, objects, listing, rename, copy, multipart uploads, pre-authenticated requests, work requests |
| Queue | `/20210201/…` | Queues, messages, visibility timeouts, dead-letter queues, stats |
| Vault, KMS & Secrets | `/20180608/…`, `/20190301/…` | Vaults, keys, real AES-GCM/RSA/ECDSA crypto, secrets and bundles |
| Streaming | `/20180418/…` | Streams, partitions, cursors, consumer groups |
| Functions | `/20181201/…` | Applications, functions, real invocation via an Fn Project sidecar |
| Container Engine for Kubernetes (OKE) | `/20180222/…` | Clusters, node pools, options, kubeconfig generation, work requests, real k3s sidecar |

## Quick start

```bash
docker compose up -d
curl http://localhost:4599/_floci-oci/health
oci os ns get --endpoint http://localhost:4599
```

See the [Quick Start](getting-started/quick-start.md) for details.
