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
- RemoveContainerVersion and RestoreContainerImage answer `501 MethodNotImplemented`:
  the registry API cannot remove one tag from a manifest.

## Pushing and pulling

The registry host is `<region-key>.ocir.localhost.floci.io:4599`, for example
`iad.ocir.localhost.floci.io:4599` for `us-ashburn-1`. `localhost.floci.io` resolves to
127.0.0.1, which Docker treats as an insecure registry, so no TLS setup is needed. Image
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

The sidecar starts on the first data-plane request. `POST /_floci-oci/state/reset` removes
it with its volume.

## Notes and limitations

Functions and OKE do not yet pull from the local registry: Functions pulls through the
Docker daemon, which reaches `*.ocir.localhost.floci.io:4599` only when floci-oci's port is
published on the daemon's host. Pull-through cache and multi-region repositories, image
signatures, and `_catalog` are not implemented.
