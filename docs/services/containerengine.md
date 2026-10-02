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
- **API Authentication**: Each cluster gets a random API token when it is created. It is stored with the cluster, never returned by `CreateCluster`, `GetCluster` or `ListClusters`, and handed out only in the kubeconfig. The token is cluster-admin (`system:masters`) on that cluster.
- **Storage Persistence**: Cluster state is stored in a Docker named volume (`/var/lib/rancher/k3s`) obeying the global storage persistence and `prune-volumes-on-delete` policies.
- **Mock Mode**: Setting `FLOCI_OCI_SERVICES_OKE_MOCK=true` (default in test profiles) bypasses Docker execution and returns synthetic control plane state instantly.
- **Teardown**: Sidecar containers and volumes are cleaned up on `DELETE`, application shutdown, or `POST /_floci-oci/state/reset`.

## Wire & Behavior Notes

- **Kubeconfig Generation**: `POST /20180222/clusters/{id}/kubeconfig/content` generates and streams raw YAML kubeconfig files pointing to `https://127.0.0.1:{hostPort}`, authenticating with the cluster's bearer token and `insecure-skip-tls-verify: true`. Real OKE only issues token version `2.0.0` kubeconfigs, which authenticate through `oci ce cluster generate-token` rather than a static token.
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

# Download kubeconfig
oci ce cluster create-kubeconfig $E --cluster-id "ocid1.cluster.oc1.iad.demo" --file ./kubeconfig
```
