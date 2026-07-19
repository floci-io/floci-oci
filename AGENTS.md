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
  (oci-go-sdk is the primary wire model; oci-java-sdk is the cross-check)

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

## Do not

- Do not execute `git add` or `git commit`; do not commit any changes
- Do not read jars from `~/.m2` as protocol reference — use `local/oracle/` checkouts
