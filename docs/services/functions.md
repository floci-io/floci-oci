# Functions

OCI Functions, on API versions `20260325` (every current SDK and OCI CLI 3.94) and `20181201`
(older SDKs, the `fn` CLI and `fn_go`). **This is floci-oci's Docker-sidecar reference
service**: the management plane is emulated in-process, and real invocations run your
function image through a shared [Fn Project](https://fnproject.io) server — the same
open-source engine OCI Functions is built on.

## Supported operations

| Area | Operations |
|---|---|
| Applications | Create, Get, List, Update, Delete, ChangeCompartment |
| Functions | Create, Get, List, Update, Delete |
| Invocation | InvokeFunction (sync, `detached`, `is-dry-run`) |
| Work requests (`20260325`) | GetWorkRequest, ListWorkRequests, ListWorkRequestErrors, ListWorkRequestLogs, CancelWorkRequest |
| Catalogs (`20260325`) | ListFunctionsRuntimes, GetFunctionsRuntime, ListFunctionsRuntimeVersions, GetFunctionsRuntimeVersion, ListPbfListings, GetPbfListing, ListPbfListingVersions, GetPbfListingVersion, ListTriggers |

## Two API versions

Both versions serve the same applications and functions, so a function created through one is
visible through the other. They differ where the SDKs differ:

| | `20181201` | `20260325` |
|---|---|---|
| Create | `200`, body, `etag` | `200`, body, `etag`, `opc-work-request-id` |
| Update, Delete, ChangeCompartment | `200` with body (`204` for delete) | `202`, no body, `opc-work-request-id` |
| A function's image | top-level `image`, `imageDigest` | `sourceDetails` `{sourceType: CONTAINER_IMAGE, image, imageDigest}` |

- The SDKs declare no status codes; `202` for the operations that answer with only a work
  request is floci-oci's choice, as in its other services.
- Every mutation finishes before it returns, so its work request is `SUCCEEDED` with
  `timeFinished` set, which is what Terraform's waiters poll for. Resources are reported as
  `functionsApplication` and `functionsFunction`, which contain the substrings Terraform matches.
  Errors and logs are always empty, and cancel is `409 Conflict`. A change-compartment reports
  `UPDATE_APPLICATION`: the SDK has no operation type of its own for it.
- `sourceDetails` must be `CONTAINER_IMAGE`: fnserver runs container images only, so `ARCHIVE`
  and `PRE_BUILT_FUNCTIONS` are `501 MethodNotImplemented`. A digest sent with the image is
  kept; otherwise one is derived.
- The runtime catalog holds `java17.ol9` and `python312.ol9`, names taken from the OCI CLI's
  tests; their other values are floci-oci's. No pre-built functions are listed.
- The optional settings (an application's `networkSecurityGroupIds`, `syslogUrl`, `traceConfig`,
  `logging`, `imagePolicyConfig`, `securityAttributes`; a function's
  `provisionedConcurrencyConfig`, `detachedModeTimeoutInSeconds`, `failureDestination`,
  `successDestination`, `traceConfig`) are accepted on both versions and returned as sent. They
  do not change how a function runs.

## Wire notes

- On `20181201` there are no work requests: Functions is synchronous with lifecycle-state polling.
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
- Pre-built functions and archive sources are not runnable (`501`), provisioned concurrency,
  trace configs and destinations are recorded only, and detached-invocation results are not
  tracked.
- `fn-invoke-type: detached` returns `202` immediately and runs the function in the
  background; `is-dry-run: true` validates without executing.
