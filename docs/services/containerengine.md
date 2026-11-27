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
| Clusters | `CreateCluster`, `GetCluster`, `ListClusters`, `UpdateCluster`, `DeleteCluster`, `UpdateClusterEndpointConfig`, `ClusterMigrateToNativeVcn`, `CreateKubeconfig` |
| Node Pools | `CreateNodePool`, `GetNodePool`, `ListNodePools`, `UpdateNodePool`, `DeleteNodePool` |
| Options | `GetClusterOptions`, `GetNodePoolOptions` |
| Work Requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs` |

## Real-Mode k3s Docker Integration

When real mode is enabled (`FLOCI_OCI_SERVICES_OKE_MOCK=false`), OKE provisions real local Kubernetes clusters using `rancher/k3s:v1.30.1-k3s1` Docker sidecar containers:

- **Control Plane**: Each sidecar runs `k3s server` (Traefik disabled), a self-contained single-node control plane that needs no external server or join token.
- **Dynamic Port Allocation**: Each cluster's Kubernetes API server is bound to a dynamic host port in the configured range (default: `6443..6543`). The port is published on all host interfaces, so the API is reachable from other machines that can reach the host.
- **Reaching the API from other containers**: by default (`FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE=host`) `endpoints.kubernetes` and the kubeconfig `server` are `https://127.0.0.1:<hostPort>`, which a client in another container cannot reach. Set `FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE=network` when floci-oci runs in a container (as in `docker-compose.yml`) and the clients are containers on the same Docker network: the address becomes `https://<sidecar container name>:6443`, for example `https://floci-oci-oke-ocid1.cluster.oc1.iad.<unique>:6443`. Every sidecar's serving certificate carries its container name as a SAN, so TLS still verifies against the kubeconfig CA. When floci-oci runs on the host, `network` falls back to `host`, as floci-aws EKS does. It also falls back, with a warning in the log, when the sidecar's network is Docker's default `bridge` (or `host`, `none`), since those networks have no container-name lookup: run floci-oci on a user-defined network, as `docker-compose.yml` does, or set `FLOCI_OCI_SERVICES_DOCKER_NETWORK` to one. The container name contains dots and can have a label that starts with a digit: kubectl and other Go clients accept it, but Java's `java.net.URI` does not parse it as a host.
- **Lifecycle**: `CreateCluster` returns the cluster `CREATING` with an `IN_PROGRESS` work request. floci-oci polls the k3s API in the background; once it answers, the cluster becomes `ACTIVE` and the work request `SUCCEEDED`, which is what Terraform and `oci ce cluster create --wait-for-state` wait on. A sidecar that exits, or an API that is not ready within `FLOCI_OCI_SERVICES_OKE_READY_TIMEOUT_SECONDS` (default 300), marks both `FAILED`.
- **kubectl Authentication**: The kubeconfig is the real OKE token version `2.0.0` shape: its user runs `oci ce cluster generate-token --cluster-id <ocid> --region <region>`, and its cluster entry carries the k3s CA, so TLS is verified. k3s does not know these tokens, so it sends each one to floci-oci in a Kubernetes `TokenReview` (a token webhook meant for the k3s sidecar and unauthenticated like every `/_floci-oci` endpoint, at `/_floci-oci/oke/token-webhook/<tenancy>/<cluster>`, the same design as floci-aws EKS).
- **What the webhook checks**: the token must be the signed `https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>` URL `generate-token` builds, for this cluster and its region, with an `rsa-sha256` Signature over `date (request-target) host`, a keyId in the cluster's tenancy, and a date within 5 minutes. Like every floci-oci request, the RSA signature itself is verified only when `FLOCI_OCI_AUTH_SIGNATURE_VERIFICATION` is `known-keys` or `strict` ([Request signing](identity.md#request-signing)); by default it is **not verified**.
- **Who the caller becomes**: the Kubernetes username and uid are the caller's user OCID, and the groups are `system:masters` and `system:authenticated`, so any accepted caller is cluster-admin. Only a caller whose key belongs to the cluster's own tenancy is accepted, the same rule floci-aws applies to EKS. Real OKE evaluates IAM policy and leaves the rest to Kubernetes RBAC; floci-oci stores policy statements but never evaluates them, so a narrower default would leave every caller unable to do anything.
- **Token lifetime**: floci-oci accepts a token whose date is within 5 minutes of now. No Oracle source states OKE's server-side lifetime. `generate-token` itself tells kubectl to refresh after 4 minutes ("before the token is actually expired", oci-cli `containerengine_cli_extended.py`), so the 5 minutes is floci-oci's choice, just above that.
- **Prerequisites for kubectl**: the `oci` CLI on `PATH` with a config whose key can sign (any key works while signature verification is `off`, the default). Session auth works too: with `--auth security_token`, `generate-token` signs with a `ST$<token>` keyId, and floci-oci reads the caller from the token's `tenant` and `sub` claims without verifying it, as the OCI SDKs read them. The same applies to every floci-oci API, not only OKE.
- **Static token alternative**: Each cluster also gets a random API token, stored with the cluster and never returned by `CreateCluster`, `GetCluster` or `ListClusters`. Set `FLOCI_OCI_SERVICES_OKE_KUBECONFIG_AUTH=token` to receive it in the kubeconfig instead of the `exec` user, for clients without the oci CLI. It is cluster-admin on that cluster.
- **Pulling from the local OCIR**: with Artifacts in real mode too (`FLOCI_OCI_SERVICES_ARTIFACTS_MOCK=false`, the default), a pod can use an image pushed to this emulator's OCIR by its OCIR reference, for example `iad.ocir.localhost.floci.io:4599/<namespace>/project/app:v1`. k3s runs its own containerd, where that host name resolves to the sidecar itself, so floci-oci writes a k3s `registries.yaml` ([k3s private registry configuration](https://docs.k3s.io/installation/private-registry)) that mirrors the cluster region's OCIR host names (`<key>.ocir.localhost.floci.io`, `<region>.ocir.localhost.floci.io` and `ocir.<region>.oci.localhost.floci.io`, with and without floci-oci's port, and the same under `FLOCI_OCI_HOSTNAME` when set) to the registry sidecar over plain HTTP, rewriting `<namespace>/<repo>` to the `<key>/<namespace>/<repo>` path the registry stores it under. The sidecar is reached at `host.docker.internal:<registry port>` when floci-oci runs on the host, and at its IP on the shared network when floci-oci runs in a container. Only the cluster's own region is mirrored. Creating a cluster starts the registry sidecar if it is not running yet; if the registry cannot start, the cluster runs without the mirror. k3s reads the file when it starts. When the registry sidecar comes back at another address (it died and restarted on another port, or the Artifacts state was reset and the next push started it on another port or IP), floci-oci rewrites `registries.yaml` in each running cluster sidecar whose mirror no longer matches and restarts that container in place, off the request thread. The restart keeps the container, so the node and the cluster's objects survive and pods start again on that node as their restart policy allows; the Kubernetes API is unavailable for the few seconds k3s takes to start, while the cluster stays `ACTIVE`. A sidecar created by an older floci-oci, whose entrypoint would rewrite the file from its creation-time settings, is recreated on its volume instead. Deleting a cluster or resetting state while its sidecar is being refreshed waits for that refresh, and a refresh never brings back a cluster deleted in the meantime. When floci-oci restarts and adopts a running sidecar, it checks the live `registries.yaml` the same way, so a sidecar refreshed before the restart is brought back to the registry's current address even when the registry returned to its original port. A cluster created while Artifacts was mocked gets the mirror when floci-oci next recreates its sidecar.
- **Storage Persistence**: Cluster state is stored in a Docker named volume (`/var/lib/rancher/k3s`) obeying the global storage persistence and `prune-volumes-on-delete` policies.
- **Mock Mode**: Setting `FLOCI_OCI_SERVICES_OKE_MOCK=true` (default in test profiles) bypasses Docker execution and returns synthetic control plane state instantly.
- **Teardown**: `DELETE` and application shutdown remove the sidecar container and keep or drop the named volume by the policy above, so a persistent cluster comes back after a restart. `POST /_floci-oci/state/reset` forgets every cluster and so always removes its volume too, whatever `prune-volumes-on-delete` says. The anonymous volumes the k3s image declares always go with the container.

## Wire & Behavior Notes

- **Kubeconfig Generation**: `POST /20180222/clusters/{id}/kubeconfig/content` accepts the SDK's `CreateClusterKubeconfigContentDetails` (`tokenVersion`, `expiration`, `endpoint`). `expiration` is deprecated in the SDK and ignored. `endpoint` must be one of `LEGACY_KUBERNETES`, `PUBLIC_ENDPOINT`, `PRIVATE_ENDPOINT` or `VCN_HOSTNAME` (any case; otherwise `400 InvalidParameter`); floci-oci has one reachable API address, `https://127.0.0.1:{hostPort}`, so every value resolves to it. Which endpoint real OKE picks when `endpoint` is omitted is not stated in the SDKs or the CLI (both leave it optional with no default), so that case resolves to the same address. Before the cluster is `ACTIVE` (or in mock mode) there is no CA yet, and the kubeconfig falls back to `insecure-skip-tls-verify: true`.
- **Cluster settings read back as sent**: `type`, `endpointConfig`, `options`, `imagePolicyConfig` and `clusterPodNetworkOptions` are stored from `CreateClusterDetails` and returned by `GetCluster` and `ListClusters` exactly as given, reduced to the fields of their SDK types. Nothing is filled in for a setting that was not sent, since the defaults real OKE applies are not documented in the SDKs. Enum values (`type`, `cniType`, `ipFamilies`) are checked (`400 InvalidParameter`). They are management-plane only: the k3s sidecar does not change with them. `availableKubernetesUpgrades` is always `[]` and `systemTags` `{}`.
- **Network references**: `vcnId`, `endpointConfig.subnetId`, `endpointConfig.nsgIds` and `options.serviceLbSubnetIds` (and the NSGs of `UpdateClusterEndpointConfig` and `ClusterMigrateToNativeVcn`) must exist in Networking; an unknown one is `400 RelatedResourceNotAuthorizedOrNotFound`. The endpoint and load balancer subnets must also be in `vcnId` (the cluster's VCN for `ClusterMigrateToNativeVcn`); a subnet from another VCN is `400 InvalidParameter`. That code is inferred: the SDKs and the CLI do not say what real OKE returns, so floci-oci uses the one Networking answers for the same mismatch. A cluster is not attached to a real network.
- **Cluster updates**: `UpdateCluster` changes the name, version, tags, `type`, `imagePolicyConfig`, and the options `UpdateClusterOptionsDetails` allows (`admissionControllerOptions`, `persistentVolumeConfig`, `serviceLbConfig`, OpenID Connect); the network options set on create stay. `UpdateClusterEndpointConfig` changes the NSGs, public IP and security attributes and keeps the subnet; `ClusterMigrateToNativeVcn` gives a cluster created without one its `endpointConfig` (what Terraform calls when `endpoint_config` is added later). Both answer `202` with a `CLUSTER_UPDATE` work request. `GetCluster` returns an `etag`, which `if-match` on these three operations checks.
- **Public endpoint**: with `endpointConfig.isPublicIpEnabled` true, `endpoints.publicEndpoint` is the cluster's one reachable address, as host:port.
- **Work Requests**: Asynchronous mutations (such as cluster and node pool creation or deletion) return `202 Accepted` with an `opc-work-request-id` header, pollable via `/20180222/workRequests/{id}`. `CreateCluster` and `CreateNodePool` return no body, as in the SDK: the new resource's OCID is the work request's `resources[0].identifier`. A cluster create that fails (the k3s sidecar exits, or is not ready in time) leaves its reason both in the cluster's `lifecycleDetails` and as an `InternalServerError` entry of `ListWorkRequestErrors`, which is where Terraform reads it.
- **Node pools need an `ACTIVE` cluster**: on a `CREATING` cluster `CreateNodePool` is `409 IncorrectState` (which SDKs retry), and on a `FAILED`, `DELETING` or `DELETED` one it is `409 Conflict`. OCI documents neither case, so both are floci-oci's choice. `GetNodePool` and `ListNodePools` always carry `systemTags` `{}`, as the cluster does, so the oracle/oci provider (9.12 and later) plans no drift after an apply.
- **Immutable Container Naming**: Sidecar containers are named using the immutable cluster OCID (`floci-oci-oke-ocid1.cluster.oc1.iad.xxx`), ensuring display name updates via `UpdateCluster` do not affect container lifecycle or teardown.

## Quickstart

The VCN has to exist first: create one as in the [Networking quickstart](networking.md#quickstart).

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Create a cluster
oci ce cluster create $E --compartment-id "$TENANCY" \
  --name demo-cluster --vcn-id "<vcnId>" --kubernetes-version "v1.30.1"

# List clusters
oci ce cluster list $E --compartment-id "$TENANCY"

# Download kubeconfig (once the cluster is ACTIVE, so it carries the CA)
oci ce cluster create-kubeconfig $E --cluster-id "ocid1.cluster.oc1.iad.demo" --file ./kubeconfig

# kubectl runs `oci ce cluster generate-token` for each request; real mode only
KUBECONFIG=./kubeconfig kubectl get namespaces

# Push an image to the local OCIR, then run it in the cluster by its OCIR reference
NS=$(oci artifacts container configuration get $E --compartment-id "$TENANCY" --query data.namespace --raw-output)
oci artifacts container repository create $E --compartment-id "$TENANCY" --display-name demo/busybox
docker login iad.ocir.localhost.floci.io:4599 -u "$NS/user" -p anything
docker tag busybox:1.36 iad.ocir.localhost.floci.io:4599/$NS/demo/busybox:1
docker push iad.ocir.localhost.floci.io:4599/$NS/demo/busybox:1
KUBECONFIG=./kubeconfig kubectl run hello --restart=Never \
  --image=iad.ocir.localhost.floci.io:4599/$NS/demo/busybox:1 -- echo hello from OCIR
```
