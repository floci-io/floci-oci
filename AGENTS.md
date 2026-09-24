# Agent Guide: floci-oci

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
- Never invent protocol behavior: consult the reference sources under `local/oracle/`
  (see "OCI Source as Reference" below; `make refs` downloads them)

## Architecture

Layered: **Controller** (JAX-RS, parses OCI REST input) → **Service** (business logic, throws
`OciException`) → **Model** (`model/Stored*.java`, `@RegisterForReflection`).

Core infrastructure (`io.floci.oci.*`):

- `config/EmulatorConfig`: single `@ConfigMapping(prefix = "floci-oci")` interface
- `core/common/`: `OciException` + `OciExceptionMapper` (error shape
  `{"code":"...","message":"..."}` + `opc-request-id` header), `ServiceRegistry` +
  `ServiceDescriptor` (self-registering), `ServiceEnabledFilter` (503 for disabled services),
  `RequestContext` (tenancy/user/region)
- `core/storage/`: `StorageBackend` (memory/persistent/hybrid/wal via `StorageFactory`),
  `TenancyAwareStorageBackend` (keys prefixed by tenancy OCID)
- `core/common/docker/`: sidecar container management
- `lifecycle/`: `EmulatorLifecycle`, init hooks, `/health` + `/_floci-oci/*` endpoints

## OCI Protocol Rules

- Every service except Object Storage uses a date-versioned path prefix
  (Identity `/20160918/…`); Object Storage uses `/n/{namespace}/b/{bucket}/o/{object}`.
  JAX-RS `@Path` matching dispatches directly: there is no routing filter.
- Errors: `{"code": "...", "message": "..."}` body + correct HTTP status. 404 is
  `NotAuthorizedOrNotFound` (OCI deliberately conflates the two).
- Every response carries an `opc-request-id` header.
- Pagination: `limit`/`page` query params in, `opc-next-page` response header out.
  Some list APIs return a bare JSON array: verify each against the SDK model.
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
reference implementation**: copy its shape:

1. **One `mock()` flag is the only container toggle** on the service's config
   (`@WithDefault("false")`, env `FLOCI_OCI_SERVICES_<SVC>_MOCK`). No separate opt-in.
   `src/test/resources/application.yml` always sets `mock: true` so the suite never
   starts containers.
2. **The Manager (driver) is flag-free** and owns only mechanics: lazy idempotent
   `ensureStarted()` (self-healing via `isContainerRunning`), a single cheap
   `boolean isReady()` probe, `stop()`. It goes through `ContainerBuilder` /
   `ContainerLifecycleManager`, never raw `dockerClient` calls, and MUST
   `portAllocator.release(port)` in the stop path (leaked ports exhaust the range).
3. **The service owns the gate**: every container interaction sits behind `!mock()`;
   mock mode keeps the management plane fully usable with synthetic data-plane results.
4. **Never block a request thread on readiness**: poll asynchronously or bound the wait
   to the data-plane call that actually needs the sidecar (Functions bounds it to invoke).
5. **Teardown**: `@PreDestroy` stops the sidecar, and the service implements
   `Resettable` so `POST /_floci-oci/state/reset` also removes containers/volumes.
6. **Tests**: the standard trio runs in mock mode; add a `<Svc>DockerTest` with
   `@TestProfile` flipping `mock=false`, `assumeTrue(docker socket)` in `@BeforeAll`,
   `PER_CLASS` + ordered methods, and a final cleanup test (not `@AfterAll`, because the
   server port is gone by then).
7. Container/volume names go through `ContainerStorageHelper.dockerName()`.

Fn-specific: fnserver shares its iofs unix-socket directory with function containers via
a **named volume** (`FN_IOFS_DOCKER_PATH=<volumeName>`): host bind mounts break unix
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

### General

- Use constructor injection; keep constructors package-private where tests need them
- Prefer self-explanatory code over comments
- Avoid unnecessary comments
- Always use braces in conditionals
- Never leave a `catch` block empty. If an exception is intentionally tolerated, log it with
  enough context to diagnose it later. When swallowing really is correct and logging would be
  noise, name the variable `ignored` or `expected` and say in a comment why it is safe. A bare
  `catch (Exception e) {}` is never acceptable.
- Follow existing project patterns
- Use modern Java features only when they improve clarity

### Types and names

- **Do not use `var`. Write the explicit type.** floci-oci reproduces OCI wire contracts, so the
  concrete type at a call site is usually the thing under review: whether a value is a
  `LinkedHashMap` or a `Map`, a `Stored*` model or a JDK one, is exactly what a reviewer needs to
  see. This covers local declarations, enhanced-for (`for (StoredBucket bucket : buckets)`),
  classic for-init, and try-with-resources. The one exception is a record deconstruction pattern
  (`case Node(var left, var right) ->`), where naming the component types is pure noise.
- **Import the classes you use. Do not write fully-qualified names inline.**
  `new ArrayList<>()`, never `new java.util.ArrayList<>()`. The only reason to qualify inline is a
  genuine name collision inside one file: import the type used more often, qualify the other, and
  leave a short comment naming the clash. The real example in this repo is
  `jakarta.inject.Provider` versus `jakarta.ws.rs.ext.Provider` in the JAX-RS filters. Class names
  inside strings (`@RegisterForReflection(classNames = {...})`) are not inline qualification.

### Imports

- No wildcard imports in `src/main`. Static wildcards stay fine in tests, where
  `Assertions.*` and `Matchers.*` are the established idiom.
- Import order: non-`java`/`javax` imports alphabetically, then `java.*` and `javax.*` last. This
  is the IntelliJ default layout and what most of the tree already uses.

### Conventions the codebase already follows

Written down so they stay true. New code should match them without thinking. A handful of files
predate them; a violation you find in the tree is a straggler, not a precedent.

- 4-space indentation, K&R braces. Never indent with a tab.
- JBoss Logging, in a field named `LOG`, using the parameterized `...v()` form. No string
  concatenation in log calls. Keep logs structured and out of hot paths.
- No `printStackTrace`, anywhere. No `System.out` or `System.err` in `src/main`. A test may print
  a failure repro just before failing, but an assertion message usually says it better.
- `java.time` for everything floci-oci owns. `Calendar` and `SimpleDateFormat` appear nowhere and
  must not be introduced. A `Date` survives only at a third-party boundary that forces one, such
  as the BouncyCastle certificate builder in `CertificateGenerator`. Convert at that boundary with
  `Date.from(instant)` and keep `java.time` on floci-oci's side of it.
- Constructor injection in `src/main`. Field injection is fine in tests, and `Instance<T>` field
  injection is a legitimate CDI pattern.
- `Optional` as a return type, and never as a field. It reaches a parameter only where a Quarkus
  config `Optional<T>` is threaded through; do not introduce it as a parameter for anything else.
- Switch expressions over switch statements. Pattern-matching `instanceof` over
  cast-after-check.
- `OciException` for domain errors.
- `final` on service fields, but not on locals or parameters.

### Tests

These describe `src/test`. `compatibility-tests/sdk-test-java` is a separate module that uses
AssertJ. Follow the module you are in.

- Name test methods either as a camelCase sentence (`putAndGetObject`) or as
  `method_scenario_expectation`. Both are established. `testX` names exist in older tests and are
  not the pattern to copy.
- JUnit 5 assertions with Hamcrest and RestAssured matchers.
- `@DisplayName` is not the pattern here. The method name carries the intent.

## Documentation Style

- No em-dashes anywhere, in any content. Use colons, commas, or periods.

## Pull Request Guidelines

- Conventional commits: `feat:`, `fix:`, `perf:`, `docs:`, `chore:`
- Keep changes focused; no unrelated refactors
- Do not add `Co-Authored-By` trailers for AI tools

## OCI Source as Reference

Never invent protocol behavior. Verify request/response shapes, field casing, headers,
status codes and enums against the real OCI sources before implementing anything.
Do not read jars from `~/.m2` as protocol reference.

All OCI references live under this repo's gitignored `local/oracle/` (shallow clones).
`make refs` fetches or refreshes the Oracle SDKs, CLI, Terraform provider and `fn`; the
rows marked *manual* below are not in `make refs` and need a one-off
`git clone --depth 1` into `local/oracle/`.

| Checkout | Use it for |
|---|---|
| `local/oracle/oci-go-sdk` | **Primary wire model.** Generated Go structs carry `json:"…"` tags, `mandatory:"true"`, enum constants, and `*_request_response.go` files declare every request parameter (`contributesTo:"path\|query\|header\|body"` + `name:`) and response field (`presentIn:"header"`, `presentIn:"header-collection"` + `prefix:`, `presentIn:"body"` for bare-array lists, `encoding:"binary"` bodies). `*_client.go` carries the exact method + path per operation in its `request.HTTPRequest(…)` call, and the `apiReferenceLink` string on each operation gives the docs slug + API version prefix. There is no `BasePath` constant. |
| `local/oracle/oci-python-sdk` | **Independent structured model, and the natural cross-check for the Go tags.** It is generated from the same internal spec, but as literal assignments that are easier to read than struct tags: `swagger_types`, `attribute_map`, `resource_path`, `method`, `required_arguments`, `api_reference_link`. Go and Python agreeing is the strongest evidence OCI offers; disagreement is a real signal. Also the source for client behavior such as `UploadManager`'s multipart flow, which required `opc-content-md5` on UploadPart responses. |
| `local/oracle/oci-java-sdk` | Cross-check for the Go model, and the client used by the default compat suite. Prefer the Go model on any disagreement. |
| `local/oracle/oci-typescript-sdk` | TypeScript client cross-check. |
| `local/oracle/oci-cli` | CLI-level behavior and parameter mapping (generated from the same specs). Two assets are worth knowing by path: **`services/object_storage/tests/objectstorage_cassettes/*.yml`** (33 recorded request/response pairs against real `objectstorage.us-ashburn-1.oraclecloud.com`; the only artifact in the tree showing what OCI actually *sends*, as opposed to what the SDK is prepared to parse; **Object Storage only**), and **`services/*/tests/util/generated/command_to_api.py`** (CLI command → SDK method, e.g. `"os.create_bucket": "oci.object_storage.ObjectStorageClient.create_bucket"`). |
| `local/oracle/terraform-provider-oci` | Exactly which API calls and read-backs IaC performs. Bucket Read calling `ListRetentionRules` came from here. Client-name keys for `CLIENT_HOST_OVERRIDES` are the `RegisterOracleClient` names in `internal/client/*.go`. |
| `local/oracle/oci-ansible-collection` (*manual*) | A second IaC read-back consumer, independent of Terraform. Reach for it when a read-back surprise is suspected but Terraform does not show it. |
| `local/oracle/fn` | The Fn Project, the engine behind OCI Functions. **`docs/swagger_v2.yml` + `docs/swagger_invoke.yml` are the only formal API spec anywhere in the tree.** Server-side behavior lives in `api/server/` (`error_response.go` in particular). |
| `local/oracle/fn_go` (*manual*, `fnproject/fn_go`) | The swagger-generated Go client for the Fn API, a typed cross-check against `fn/docs/swagger_v2.yml`. Useful because `oci-go-sdk/functions` is the least tag-derivable of the emulated services. |
| `local/oracle/fn-cli` (*manual*, `fnproject/cli`) | What the `fn` CLI (the one Oracle tells Functions users to install) actually puts on the wire. |

Precedence when sources disagree: **oci-go-sdk → oci-java-sdk → published API reference**,
with oci-python-sdk as the independent cross-check.
There is no botocore equivalent and no usable reference implementation: the generated SDK
models are the closest thing OCI has to a wire contract. Consequences worth stating
outright, so they are not re-researched:

- **OCI declares no per-operation error lists.** Nothing anywhere maps an operation to the
  codes it can throw, so the check inverts: a code must exist in Oracle's *global* catalog
  ([apierrors.htm](https://docs.oracle.com/en-us/iaas/Content/API/References/apierrors.htm),
  31 `(status, code)` pairs). Never claim an operation "declares" an error. Retryability is
  in `oci-go-sdk/common/retry.go`.
- **IAM policy verbs and resource-types exist only as HTML.** There is no machine-readable
  OCI policy vocabulary, official or otherwise; the source is
  [policyreference](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/policyreference.htm).
- `cameritelabs/oci-emulator` is the only other OCI emulator and is **deliberately not
  cloned**: stale since 2025, Object-Storage-only, and citing another emulator's guess would
  launder it into an authority.

Typical lookups:

```bash
# Request parameters + response headers/body for an operation
grep -A25 'type GetObjectRequest struct' local/oracle/oci-go-sdk/objectstorage/get_object_request_response.go

# Path + method + docs link for an operation
grep -n 'HTTPRequest(http' local/oracle/oci-go-sdk/identity/identity_client.go
grep -n 'apiReferenceLink :=' local/oracle/oci-go-sdk/objectstorage/objectstorage_client.go

# Does this list operation return a bare JSON array? (`Items []T` = yes)
grep -n 'presentIn:"body"' local/oracle/oci-go-sdk/objectstorage/list_buckets_request_response.go

# Cross-check the same operation in the Python SDK
grep -n 'resource_path\|required_arguments\|api_reference_link' \
  local/oracle/oci-python-sdk/src/oci/object_storage/object_storage_client.py

# What Terraform reads back after a create
grep -n 'func (s \*.*ResourceCrud) Get' local/oracle/terraform-provider-oci/internal/service/objectstorage/*.go
```

## Do not

- Do not execute `git add` or `git commit`; do not commit any changes
- Do not read jars from `~/.m2` as protocol reference: use `local/oracle/` checkouts
