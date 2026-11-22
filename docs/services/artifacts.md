# Container Registry (OCIR)

OCI Container Registry: the Artifacts management API (version `20160918`, under
`/20160918/container`) and a Docker Registry V2 data plane, both on port 4599. Images are
stored in one shared `registry:2` sidecar container.

## Supported operations

| Area | Operations |
|---|---|
| Repositories | CreateContainerRepository, GetContainerRepository, ListContainerRepositories, UpdateContainerRepository, DeleteContainerRepository, ChangeContainerRepositoryCompartment |
| Images | ListContainerImages, GetContainerImage, DeleteContainerImage |
| Configuration | GetContainerConfiguration, UpdateContainerConfiguration |
| Data plane | `docker login`, `docker push`, `docker pull`, and every repository operation of the Registry HTTP API V2 (`/v2/<namespace>/<repository>/manifests`, `blobs`, `tags`) |

## Wire notes

- Every management operation is synchronous. Lists return collection objects:
  `ContainerRepositoryCollection` (`items`, `repositoryCount`, `imageCount`, `layerCount`,
  `layersSizeInBytes`, `remainingItemsCount`) and `ContainerImageCollection`
  (`items`, `remainingItemsCount`), paginated with `opc-next-page`.
- Repository names follow the registry grammar (lowercase letters, digits, `.`, `_`, `-`
  and `/` separators) and are unique per tenancy and region; a duplicate is
  `409 NotAuthorizedOrResourceAlreadyExists`. New repositories are private and mutable
  unless `isPublic` or `isImmutable` say otherwise.
- `readme` is returned only when it was set.
- Image counts, layers and sizes are read from the registry, so they always match what
  was pushed. An image is one manifest digest; its tags are its `versions`. Image OCIDs are
  derived from the repository and the digest, so they stay the same across restarts.
- An image whose tag moved to a newer push stays listed, untagged, as
  `<repository>:unknown@<digest>`, until it is deleted.
- A multi-platform image (an OCI image index or Docker manifest list) is one image. Its layers
  and `layersSizeInBytes` are those of its platform manifests, which are not listed on their
  own and are deleted with it.
- When the registry cannot be reached or answers with an error, image reads and deletes fail
  with `503 ExternalServerUnreachable` or `503 ExternalServerInvalidResponse`, and a repository
  whose images could not be deleted is kept.
- RemoveContainerVersion and RestoreContainerImage answer `501 MethodNotImplemented`:
  the registry API cannot remove one tag from a manifest.

## Pushing and pulling

The registry host is `<region-key>.ocir.localhost.floci.io:4599`, for example
`iad.ocir.localhost.floci.io:4599` for `us-ashburn-1`. `localhost.floci.io` resolves to
127.0.0.1 and the registry speaks plain HTTP. Docker Engine 29 with the containerd image store
uses plain HTTP only for `localhost` and `127.0.0.1` names, so if a push fails with an HTTPS
error, add `iad.ocir.localhost.floci.io:4599` to the daemon's `insecure-registries`. Image
references follow OCIR's shape, `<host>/<namespace>/<repository>:<tag>`, where the
namespace is the tenancy's Object Storage namespace (`floci-local` for the default
tenancy).

```bash
docker login iad.ocir.localhost.floci.io:4599 -u floci-local/user -p anything
docker tag alpine:3.20 iad.ocir.localhost.floci.io:4599/floci-local/project/app:v1
docker push iad.ocir.localhost.floci.io:4599/floci-local/project/app:v1
```

- Any `<namespace>/<user>` and password is accepted; the token is issued and never
  checked.
- A push into a repository that does not exist is refused with `NAME_UNKNOWN`, unless the
  tenancy's container configuration has `isRepositoryCreatedOnFirstPush: true`. Then a
  private repository is created in the root compartment.
- An immutable repository refuses manifest pushes with `DENIED`.
- A namespace is known to the data plane once its tenancy has used the management API
  (created a repository or read the container configuration).
- The data plane only answers on a Host with an `ocir` label. Hosts of the form
  `<region-key>.ocir.<domain>`, `<region>.ocir.<domain>` and
  `ocir.<region>.oci.<domain>` are recognised.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `FLOCI_OCI_SERVICES_ARTIFACTS_ENABLED` | `true` | Enable/disable Container Registry |
| `FLOCI_OCI_SERVICES_ARTIFACTS_MOCK` | `false` | Skip the registry sidecar: repositories and configuration work, image lists are empty, `/v2/` answers 503 |
| `FLOCI_OCI_SERVICES_ARTIFACTS_REGISTRY_IMAGE` | `registry:2` | Docker Distribution image |
| `FLOCI_OCI_SERVICES_ARTIFACTS_REGISTRY_BASE_PORT` | `5200` | First host port for the registry sidecar |
| `FLOCI_OCI_SERVICES_ARTIFACTS_REGISTRY_MAX_PORT` | `5299` | Last host port for the registry sidecar |

The sidecar starts on the first data-plane request, or on the first image read of a
repository that has had a push. Its images live in a Docker volume that outlives the container,
so they survive a restart of the sidecar or of floci-oci. `POST /_floci-oci/state/reset`
removes the sidecar with its volume.

## Running pushed images

A function whose image is in this registry runs as it is: create it with the OCIR reference,
for example `iad.ocir.localhost.floci.io/floci-local/project/app:v1`, and floci-oci hands
fnserver `localhost:<registry port>/iad/floci-local/project/app:v1` instead. The registry
publishes its port on the Docker daemon's host, so the daemon pulls from its own `localhost`,
which works for a remote daemon too. The function's `image` keeps the OCIR reference.

An image is rewritten only when its namespace belongs to a tenancy this emulator knows, so a
reference to real OCIR (`iad.ocir.io/<real namespace>/...`) is passed through unchanged. With
`FLOCI_OCI_SERVICES_ARTIFACTS_MOCK=true` nothing is rewritten.

## Notes and limitations

OKE does not yet pull from the local registry. Pull-through cache and multi-region
repositories, image signatures, and `_catalog` are not implemented.
