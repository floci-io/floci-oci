# Container Instances

OCI Container Instances, API version `20210415` (SDK package `containerinstances`, client
`ContainerInstanceClient`, CLI `oci container-instances`). Each container instance runs for real
on Docker, so its containers serve traffic and write logs you can read back. Real OCI serves the
API on `compute-containers.{region}.oci.{domain}`; floci-oci serves it on port 4599 with the other
services.

## Supported operations

| Area | Operations |
|---|---|
| Container instances | `CreateContainerInstance`, `GetContainerInstance`, `ListContainerInstances`, `UpdateContainerInstance`, `DeleteContainerInstance`, `ChangeContainerInstanceCompartment` |
| Runtime | `StartContainerInstance`, `StopContainerInstance`, `RestartContainerInstance` |
| Containers | `GetContainer`, `ListContainers`, `UpdateContainer`, `RetrieveLogs` |
| Catalog | `ListContainerInstanceShapes` |
| Work requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs` |

Every instance gets one VNIC on the subnet it names. Networking serves it on `GetVnic` and
`UpdateVnic` (see [Networking](networking.md#vnics)), which is what the Terraform provider reads
back for `oci_container_instances_container_instance`.

## How a container instance runs

- **One pod per instance.** A pause container (`registry.k8s.io/pause:3.10` by default) holds the
  instance's network namespace on the shared Docker network, and each container joins it
  (`--network container:<pause>`), so the containers of one instance reach each other on
  `localhost`, as in OCI.
- **The VNIC address is the pod's address.** `privateIp` on the VNIC is the pause container's IP
  on its Docker network, recorded each time the pod starts, so other containers on that network
  (the compat suites, a client in `docker compose`) reach `http://<privateIp>:<port>`. A stopped
  and started instance can come back with another address, and the VNIC follows it. A
  `privateIp` given on create is kept as sent, and nothing on Docker answers on it. On macOS, the host cannot reach container IPs; use a
  container on the same network.
- **Images.** Each image is pulled before anything starts. An image pushed to the local
  [Container Registry](artifacts.md) (`<region-key>.ocir.localhost.floci.io/<namespace>/<repo>`)
  is pulled from the registry sidecar. `imagePullSecrets` of type `BASIC` (base64 username and
  password) and `VAULT` (a secret in this emulator's Vault holding
  `{"username": ..., "password": ...}`) are used for their registry.
- **Containers.** `command` replaces the image's entrypoint and `arguments` its command, as the
  SDK describes them; `environmentVariables` and `workingDirectory` pass through.
  `resourceConfig.vcpusLimit` and `memoryLimitInGBs` become Docker CPU and memory limits.
- **Volumes.** Each volume of the instance is one named Docker volume, shared by every container
  that mounts it and removed with the instance: `EMPTYDIR` on disk, or on tmpfs for
  `backingStore: MEMORY`; `CONFIGFILE` filled with its files before any container starts, so they
  appear at `<mountPath>/<path>/<fileName>`. A mount with `isReadOnly: true` is a read-only
  mount for every volume type. Volume names are case-sensitive: `Cache` and `cache` are two
  volumes. `subPath` is stored and returned but not applied.
- **Work requests.** Create, delete, start, stop and restart return a work request that finishes
  when Docker has done the work; the instance is `CREATING` (or `UPDATING`, `DELETING`) until
  then. A pull or start failure leaves the instance `FAILED` with the error in
  `lifecycleDetails` and on `ListWorkRequestErrors`; so does a delete that Docker cannot finish
  (the instance keeps its VNIC and can be deleted again), and a stop or restart Docker rejects
  fails its work request the same way. A container or volume that is already gone does not
  count as a failure. Update and change compartment finish at once.
- **Graceful shutdown.** Stop, restart, the restart policy and a shutdown of floci-oci give each
  container `gracefulShutdownTimeoutInSeconds` to exit before Docker kills it. Left out, it is
  `0` and the containers are killed at once (inferred: the SDK documents no default).
- **Restart policy.** floci-oci checks the containers every five seconds. A container that
  exited is restarted under `ALWAYS`, and under `ON_FAILURE` when its exit code is not zero, and
  `containerRestartAttemptCount` counts it; otherwise it becomes `INACTIVE` with its `exitCode`
  and `timeTerminated`. An instance whose containers have all stopped becomes `INACTIVE`.
- **Logs.** `RetrieveLogs` returns the container's stdout and stderr as text (at most 20 MB).
  `isPrevious` is accepted and ignored.
- **Restarts of floci-oci.** A create that was running is marked `FAILED`; an `ACTIVE` instance
  is started again; a delete that was running finishes.

With `FLOCI_OCI_SERVICES_CONTAINERINSTANCES_MOCK=true` nothing touches Docker: instances and
containers are `ACTIVE` as soon as they are created, work requests are `SUCCEEDED`, the VNIC gets
the next free address of its subnet, and `RetrieveLogs` is empty.

## Wire notes

- Create answers `200` with the `ContainerInstance`, its `etag` and `opc-work-request-id`; every
  other write answers `202` with only `opc-work-request-id`. The SDKs record no status codes; these
  follow the other work-request-driven services.
- Lists are `{"items": [...]}` collections with `opc-next-page`. `ListContainerInstances` and
  `ListContainers` sort by `timeCreated` (newest first) or `displayName`.
- The work request resource has `entityType: containerInstance`, populated from the start: the
  Terraform provider reads the `CREATED` resource from it. `WorkRequest` has no `errors` field;
  errors are on `ListWorkRequestErrors`.
- `ContainerInstanceSummary` carries no `containers`, `vnics` or `volumes`, and `ContainerSummary`
  no command, arguments or health checks, as in the SDK models.
- Deleted instances stay readable with `lifecycleState: DELETED`.

## Validation

- `availabilityDomain` must be one of the tenancy's (`ListAvailabilityDomains`), `faultDomain`
  one of `FAULT-DOMAIN-1` to `3`.
- `shape` must be offered in the region and `shapeConfig` within its range (below).
- There is exactly one VNIC, and its `subnetId` and `nsgIds` must exist in Networking
  (`400 RelatedResourceNotAuthorizedOrNotFound`). The NSGs must be in the subnet's VCN, and a
  requested `privateIp` cannot be one of the subnet's reserved addresses (`400
  InvalidParameter`, see [Networking](networking.md#vnics)).
- A volume mount must name one of the instance's volumes.
- A start of an instance that is not `INACTIVE`, a stop or restart of one that is not `ACTIVE`,
  and a delete of one already `DELETING` or `DELETED` are `409 IncorrectState`.

## Shapes

From Oracle's
[Container Instances Shapes](https://docs.oracle.com/en-us/iaas/Content/container-instances/container-instance-shapes.htm)
page:

| Shape | OCPUs | Memory | Bandwidth |
|---|---|---|---|
| `CI.Standard.E4.Flex` | 1 to 64 | 1 GB (or one per OCPU) to 64 GB per OCPU, at most 1024 GB | 1 Gbps per OCPU, at most 40 |
| `CI.Standard.E5.Flex` | 1 to 94 | 1 GB to 64 GB per OCPU, at most 1504 GB | 1 Gbps per OCPU, at most 100 |
| `CI.Standard.A1.Flex` | 1 to 76 | 1 GB (or one per OCPU) to 64 GB per OCPU, at most 488 GB | 1 Gbps per OCPU, at most 40 |

`CI.Standard.E5.Flex` is listed only in the regions that page names. The processor descriptions,
and the memory an instance gets when `shapeConfig.memoryInGBs` is left out (16 GB per OCPU, 6 for
A1), are not on that page: they follow the Compute shapes of the same series and are inferred.

## Not emulated

- `OCI_FSS_FILE_SYSTEM` volumes (`400 InvalidParameter`).
- Health checks are stored and returned with `status: UNKNOWN`; they are not run.
- `securityContext` and `dnsConfig` are stored and returned, and not applied to the containers.
- `subPath` on a volume mount is stored and ignored.

## Quickstart

The subnet has to exist first: create one as in the [Networking quickstart](networking.md#quickstart).

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000
AD=$(oci iam availability-domain list $E -c "$TENANCY" --query 'data[0].name' --raw-output)

oci container-instances container-instance create $E --compartment-id "$TENANCY" \
  --availability-domain "$AD" --shape CI.Standard.E4.Flex \
  --shape-config '{"ocpus": 1, "memoryInGBs": 4}' \
  --containers '[{"imageUrl": "nginx:1.27"}]' \
  --vnics '[{"subnetId": "<subnetId>"}]' \
  --wait-for-state SUCCEEDED

oci container-instances container retrieve-logs $E --container-id <containerId> --file -
```
