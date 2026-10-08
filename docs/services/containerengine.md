# Container Engine for Kubernetes (OKE)

The **Oracle Cloud Infrastructure (OCI) Container Engine for Kubernetes (OKE)** emulator service provides local Kubernetes cluster lifecycle management, node pool configuration, kubeconfig generation, and real-mode Kubernetes cluster sidecar execution.

## Service / API / SDK Client

| Area | API Endpoint | SDK Client |
|---|---|---|
| OKE Clusters | `/20180222/clusters` | `ContainerEngineClient` |
| OKE Node Pools | `/20180222/nodePools` | `ContainerEngineClient` |
| OKE Options | `/20180222/options` | `ContainerEngineClient` |
| OKE Kubeconfig | `/20180222/clusters/{clusterId}/kubeconfig/content` | `ContainerEngineClient` |
| OKE Work Requests | `/20180222/workRequests` | `ContainerEngineClient` |

## Supported Operations

| Area | Operations |
|---|---|
| Clusters | `CreateCluster`, `GetCluster`, `ListClusters`, `UpdateCluster`, `DeleteCluster`, `CreateKubeconfig` |
| Node Pools | `CreateNodePool`, `GetNodePool`, `ListNodePools`, `UpdateNodePool`, `DeleteNodePool` |
| Options | `GetClusterOptions`, `GetNodePoolOptions` |
| Work Requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs` |

## Real-Mode k3s Docker Integration

When real mode is enabled (`FLOCI_OCI_SERVICES_OKE_MOCK=false`), OKE provisions real local Kubernetes clusters using `rancher/k3s:v1.30.1-k3s1` Docker sidecar containers:

- **Control Plane**: Each sidecar runs `k3s server` (Traefik disabled), a self-contained single-node control plane that needs no external server or join token.
- **Dynamic Port Allocation**: Each cluster's Kubernetes API server is bound to a dynamic host port in the configured range (default: `6443..6543`). The port is published on all host interfaces, so the API is reachable from other machines that can reach the host.
- **Lifecycle**: `CreateCluster` returns the cluster `CREATING` with an `IN_PROGRESS` work request. floci-oci polls the k3s API in the background; once it answers, the cluster becomes `ACTIVE` and the work request `SUCCEEDED`, which is what Terraform and `oci ce cluster create --wait-for-state` wait on. A sidecar that exits, or an API that is not ready within `FLOCI_OCI_SERVICES_OKE_READY_TIMEOUT_SECONDS` (default 300), marks both `FAILED`.
- **kubectl Authentication**: The kubeconfig is the real OKE token version `2.0.0` shape: its user runs `oci ce cluster generate-token --cluster-id <ocid> --region <region>`, and its cluster entry carries the k3s CA, so TLS is verified. k3s does not know these tokens, so it sends each one to floci-oci in a Kubernetes `TokenReview` (a token webhook meant for the k3s sidecar and unauthenticated like every `/_floci-oci` endpoint, at `/_floci-oci/oke/token-webhook/<tenancy>/<cluster>`, the same design as floci-aws EKS).
- **What the webhook checks**: the token must be the signed `https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>` URL `generate-token` builds, for this cluster and its region, with an `rsa-sha256` Signature over `date (request-target) host`, a keyId in the cluster's tenancy, and a date within 5 minutes. Like every floci-oci request, the RSA signature itself is **not verified**, and there is no IAM policy evaluation: a well-formed token for the cluster is accepted as cluster-admin (`system:masters`). Real OKE verifies the signature and applies IAM policy.
- **Prerequisites for kubectl**: the `oci` CLI on `PATH` with a config whose key can sign (any key works, since signatures are not verified). Session or security-token auth (`--auth security_token`) is not supported yet.
- **Static token alternative**: Each cluster also gets a random API token, stored with the cluster and never returned by `CreateCluster`, `GetCluster` or `ListClusters`. Set `FLOCI_OCI_SERVICES_OKE_KUBECONFIG_AUTH=token` to receive it in the kubeconfig instead of the `exec` user, for clients without the oci CLI. It is cluster-admin on that cluster.
- **Storage Persistence**: Cluster state is stored in a Docker named volume (`/var/lib/rancher/k3s`) obeying the global storage persistence and `prune-volumes-on-delete` policies.
- **Mock Mode**: Setting `FLOCI_OCI_SERVICES_OKE_MOCK=true` (default in test profiles) bypasses Docker execution and returns synthetic control plane state instantly.
- **Teardown**: Sidecar containers and volumes are cleaned up on `DELETE`, application shutdown, or `POST /_floci-oci/state/reset`.

## Wire & Behavior Notes

- **Kubeconfig Generation**: `POST /20180222/clusters/{id}/kubeconfig/content` accepts the SDK's `CreateClusterKubeconfigContentDetails` (`tokenVersion`, `expiration`, `endpoint`). `expiration` is deprecated in the SDK and ignored. `endpoint` must be one of `LEGACY_KUBERNETES`, `PUBLIC_ENDPOINT`, `PRIVATE_ENDPOINT` or `VCN_HOSTNAME` (any case; otherwise `400 InvalidParameter`); floci-oci has one reachable API address, `https://127.0.0.1:{hostPort}`, so every value resolves to it. Before the cluster is `ACTIVE` (or in mock mode) there is no CA yet, and the kubeconfig falls back to `insecure-skip-tls-verify: true`.
- **Work Requests**: Asynchronous mutations (such as cluster and node pool creation or deletion) return `202 Accepted` with an `opc-work-request-id` header, pollable via `/20180222/workRequests/{id}`.
- **Immutable Container Naming**: Sidecar containers are named using the immutable cluster OCID (`floci-oci-oke-ocid1.cluster.oc1.iad.xxx`), ensuring display name updates via `UpdateCluster` do not affect container lifecycle or teardown.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Create a cluster
oci ce cluster create $E --compartment-id "$TENANCY" \
  --name demo-cluster --vcn-id "ocid1.vcn.oc1.iad.demovcn" --kubernetes-version "v1.30.1"

# List clusters
oci ce cluster list $E --compartment-id "$TENANCY"

# Download kubeconfig (once the cluster is ACTIVE, so it carries the CA)
oci ce cluster create-kubeconfig $E --cluster-id "ocid1.cluster.oc1.iad.demo" --file ./kubeconfig

# kubectl runs `oci ce cluster generate-token` for each request; real mode only
KUBECONFIG=./kubeconfig kubectl get namespaces
```
