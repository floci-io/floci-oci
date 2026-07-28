# Functions

OCI Functions — API version `20181201`. **This is floci-oci's Docker-sidecar reference
service**: the management plane is emulated in-process, and real invocations run your
function image through a shared [Fn Project](https://fnproject.io) server — the same
open-source engine OCI Functions is built on.

## Supported operations

| Area | Operations |
|---|---|
| Applications | Create, Get, List, Update, Delete, ChangeCompartment |
| Functions | Create, Get, List, Update, Delete |
| Invocation | InvokeFunction (sync, `detached`, `is-dry-run`) |

## Wire notes

- **No work requests anywhere** — Functions is synchronous with lifecycle-state polling.
- Lists are bare JSON arrays; `ListFunctions` requires `applicationId`.
- Exact casing matters: **`memoryInMBs`**.
- `imageDigest` is deterministic per image and **changes when the image changes** —
  Terraform's `CustomizeDiff` depends on this, otherwise plans drift forever.
- `invokeEndpoint` is a stable host-only base URL; invocation is raw **binary in, binary
  out** — never JSON-wrapped by the emulator.
- Deleting an application with functions still in it is a `409`.

## Mock mode vs real invocation

| `floci-oci.services.functions.mock` | Behaviour |
|---|---|
| `false` (default) | Starts a shared `fnproject/fnserver` container on first invoke, mirrors the app/function into it, and proxies the call to the real image |
| `true` | No Docker at all; the management plane works fully and invocations return a synthetic body |

The test suite sets `mock: true` so it never needs Docker.

```bash
# Disable the sidecar entirely
FLOCI_OCI_SERVICES_FUNCTIONS_MOCK=true
```

Real invocation requires the Docker socket — the emulator's container mounts it, and
fnserver spawns your function containers as siblings.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci fn application create $E --compartment-id "$TENANCY" \
  --display-name demo-app --subnet-ids '["ocid1.subnet.oc1.iad.demo"]'
oci fn function create $E --application-id <appId> --display-name hello \
  --image <your-fdk-image>:latest --memory-in-mbs 128
oci fn function invoke $E --function-id <fnId> --body '{"name":"floci"}' --file -
```

## Notes & limitations

- Your image must be a **current FDK build** (http-stream contract). Older
  `fnproject/hello` images predate it and fail to initialize.
- Pre-built functions (PBF listings), provisioned concurrency, trace configs, and
  detached-invocation result tracking are not implemented.
- `fn-invoke-type: detached` returns `202` immediately and runs the function in the
  background; `is-dry-run: true` validates without executing.
