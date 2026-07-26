# Agent Guide — floci-oci

Guidance for AI coding agents working in the floci-oci repository.

## Project Overview

floci-oci is a Java-based local **Oracle Cloud Infrastructure (OCI)** emulator built on Quarkus.
Its goal is full OCI SDK and OCI CLI compatibility through real OCI wire protocols, not
convenience APIs. It is the OCI sibling of floci (AWS, port 4566), floci-az (Azure, 4577)
and floci-gcp (GCP, 4588).

- Port: **4599**
- Stack: Java 25, Quarkus, JUnit 5, RestAssured, Jackson
- Package root: `io.floci.oci`
- Config prefix: `floci-oci.*` / env `FLOCI_OCI_*`

## First Principles

1. Preserve OCI protocol compatibility
2. Match OCI SDK and CLI behavior
3. Reuse existing patterns
4. Prefer correctness over convenience
5. Keep changes narrow and testable

Critical rules:

- Do not introduce custom endpoint shapes
- Do not change request or response formats for convenience
- Never invent protocol behavior — consult the reference sources under `local/oracle/`
  (see "OCI Source as Reference" below; `make refs` downloads them)

## Architecture

Layered: **Controller** (JAX-RS, parses OCI REST input) → **Service** (business logic, throws
`OciException`) → **Model** (`model/Stored*.java`, `@RegisterForReflection`).

Core infrastructure (`io.floci.oci.*`):

- `config/EmulatorConfig` — single `@ConfigMapping(prefix = "floci-oci")` interface
- `core/common/` — `OciException` + `OciExceptionMapper` (error shape
  `{"code":"...","message":"..."}` + `opc-request-id` header), `ServiceRegistry` +
  `ServiceDescriptor` (self-registering), `ServiceEnabledFilter` (503 for disabled services),
  `RequestContext` (tenancy/user/region)
- `core/storage/` — `StorageBackend` (memory/persistent/hybrid/wal via `StorageFactory`),
  `TenancyAwareStorageBackend` (keys prefixed by tenancy OCID)
- `core/common/docker/` — sidecar container management
- `lifecycle/` — `EmulatorLifecycle`, init hooks, `/health` + `/_floci-oci/*` endpoints

## OCI Protocol Rules

- Every service except Object Storage uses a date-versioned path prefix
  (Identity `/20160918/…`); Object Storage uses `/n/{namespace}/b/{bucket}/o/{object}`.
  JAX-RS `@Path` matching dispatches directly — there is no routing filter.
- Errors: `{"code": "...", "message": "..."}` body + correct HTTP status. 404 is
  `NotAuthorizedOrNotFound` (OCI deliberately conflates the two).
- Every response carries an `opc-request-id` header.
- Pagination: `limit`/`page` query params in, `opc-next-page` response header out.
  Some list APIs return a bare JSON array — verify each against the SDK model.
- OCIDs: `ocid1.<type>.<realm>.<region>.<unique>` (region omitted for global resources).
- Auth: the `Authorization: Signature …` header is parsed for tenancy/user context only;
  the RSA signature is never verified.
- **Tenancy is the storage partition; compartment is a field on each resource** filtered
  via `?compartmentId=`. Do not conflate them.
- Async operations return 202 + `opc-work-request-id` and are polled via work requests.

## Registration Pattern (no service-keyed switches)

Each service registers itself in an `@Observes StartupEvent` method:

```java
void onStart(@Observes StartupEvent ev) {
    serviceRegistry.register(ServiceDescriptor.builder("objectstorage")
            .enabled(config.services().objectstorage().enabled())
            .storageKey("objectstorage")
            .resourceClasses(ObjectStorageController.class)
            .build());
}
```

`ServiceRegistry`, `ServiceEnabledFilter`, `StorageFactory` and the banner resolve service
metadata through descriptors. Adding a service must never require editing a switch in core.

## Services with Container Sidecars

Some services launch real Docker containers (sidecars). **`services/functions/` is the
reference implementation** — copy its shape:

1. **One `mock()` flag is the only container toggle** on the service's config
   (`@WithDefault("false")`, env `FLOCI_OCI_SERVICES_<SVC>_MOCK`). No separate opt-in.
   `src/test/resources/application.yml` always sets `mock: true` so the suite never
   starts containers.
2. **The Manager (driver) is flag-free** and owns only mechanics: lazy idempotent
   `ensureStarted()` (self-healing via `isContainerRunning`), a single cheap
   `boolean isReady()` probe, `stop()`. It goes through `ContainerBuilder` /
   `ContainerLifecycleManager` — never raw `dockerClient` calls — and MUST
   `portAllocator.release(port)` in the stop path (leaked ports exhaust the range).
3. **The service owns the gate**: every container interaction sits behind `!mock()`;
   mock mode keeps the management plane fully usable with synthetic data-plane results.
4. **Never block a request thread on readiness** — poll asynchronously or bound the wait
   to the data-plane call that actually needs the sidecar (Functions bounds it to invoke).
5. **Teardown**: `@PreDestroy` stops the sidecar, and the service implements
   `Resettable` so `POST /_floci-oci/state/reset` also removes containers/volumes.
6. **Tests**: the standard trio runs in mock mode; add a `<Svc>DockerTest` with
   `@TestProfile` flipping `mock=false`, `assumeTrue(docker socket)` in `@BeforeAll`,
   `PER_CLASS` + ordered methods, and a final cleanup test (not `@AfterAll` — the
   server port is gone by then).
7. Container/volume names go through `ContainerStorageHelper.dockerName()`.

Fn-specific: fnserver shares its iofs unix-socket directory with function containers via
a **named volume** (`FN_IOFS_DOCKER_PATH=<volumeName>`) — host bind mounts break unix
sockets on Docker Desktop. Old `fnproject/hello` images predate the http-stream FDK
contract; use a current FDK image (see `src/test/resources/fn-hello/`).

## Configuration Rules

- `application.yml` is the source of truth for effective defaults; keep `@WithDefault`
  values in agreement with it.
- When adding config: update `EmulatorConfig`, main `application.yml`, test
  `application.yml` if needed, and docs.

## Storage Rules

- Always use `StorageFactory.create(serviceName, fileName, typeReference)`
- Do not instantiate storage implementations directly in services
- Per-service overrides live under `floci-oci.storage.services.<key>` (a map, not
  per-service interfaces)

## Adding a New OCI Service

1. Create `services/<svc>/` with `<Svc>Controller`, `<Svc>Service`, `model/`
2. The service registers its own `ServiceDescriptor` at startup
3. Add `<Svc>ServiceConfig { enabled(); }` to `EmulatorConfig.ServicesConfig`
4. Add the YAML block to main `application.yml`
5. Wire storage through `StorageFactory`
6. Add the test trio: `<Svc>ServiceTest` (unit, package-private ctor),
   `<Svc>RestIntegrationTest` (`@QuarkusTest` + RestAssured),
   `<Svc>DisabledRestIntegrationTest` (profile flips `enabled=false`, asserts 503)
7. Update documentation

## Build & Run

    ./mvnw quarkus:dev
    ./mvnw test
    ./mvnw test -Dtest=SomeTest#method
    ./mvnw clean package -DskipTests

## Testing Rules

- Unit tests: `*ServiceTest.java`; integration tests: `*IntegrationTest.java`
- Prefer SDK-based validation (oci-java-sdk) for protocol behavior
- Assert `opc-request-id` presence and exact error bodies when touching protocol code

## Code Style

- Constructor injection; package-private constructors for testability
- Self-explanatory code over comments; always use braces
- JBoss Logging, structured, no noise in hot paths

## Pull Request Guidelines

- Conventional commits: `feat:`, `fix:`, `perf:`, `docs:`, `chore:`
- Keep changes focused; no unrelated refactors
- Do not add `Co-Authored-By` trailers for AI tools

## OCI Source as Reference

Never invent protocol behavior — verify request/response shapes, field casing, headers,
status codes and enums against the real OCI sources before implementing anything.
Do not read jars from `~/.m2` as protocol reference.

All OCI references live under this repo's gitignored `local/oracle/` (shallow clones;
fetch or refresh them all with `make refs`):

| Checkout | Use it for |
|---|---|
| `local/oracle/oci-go-sdk` | **Primary wire model.** Generated Go structs carry `json:"…"` tags, `mandatory:"true"`, enum constants, and `*_request_response.go` files declare every request/response header (`presentIn:"header"`) and body shape (`presentIn:"body"` — bare-array lists, binary bodies). `*_client.go` has the exact method + path per operation and the client `BasePath` (API version prefix). |
| `local/oracle/oci-java-sdk` | Cross-check for the Go model, and the client used by the default compat suite. Prefer the Go model on any disagreement. |
| `local/oracle/oci-python-sdk` | Python client behavior — e.g. `UploadManager`'s multipart flow, which required `opc-content-md5` on UploadPart responses. |
| `local/oracle/oci-typescript-sdk` | TypeScript client cross-check. |
| `local/oracle/oci-cli` | CLI-level behavior and parameter mapping (generated from the same specs). |
| `local/oracle/terraform-provider-oci` | Exactly which API calls and read-backs IaC performs. Bucket Read calling `ListRetentionRules` came from here. Client-name keys for `CLIENT_HOST_OVERRIDES` are the `RegisterOracleClient` names in `internal/client/*.go`. |

Precedence when sources disagree: **oci-go-sdk → oci-java-sdk → published API reference**.
There is no botocore equivalent and no reference implementation (no LocalStack/moto/Azurite
analog) — the generated SDK models are the closest thing OCI has to a wire contract.

Typical lookups:

```bash
# Response shape + headers for an operation
grep -A20 'type CreateBucketResponse struct' local/oracle/oci-go-sdk/objectstorage/create_bucket_request_response.go

# Path + method for an operation
grep -n 'HTTPRequest(http' local/oracle/oci-go-sdk/identity/identity_client.go

# What Terraform reads back after a create
grep -n 'func (s \*.*ResourceCrud) Get' local/oracle/terraform-provider-oci/internal/service/objectstorage/*.go
```

## Do not

- Do not execute `git add` or `git commit`; do not commit any changes
- Do not read jars from `~/.m2` as protocol reference — use `local/oracle/` checkouts
