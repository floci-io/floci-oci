# Contributing to floci-oci

Thank you for your interest in contributing! floci-oci is a local Oracle Cloud (OCI)
emulator built on Quarkus, sibling to floci (AWS), floci-az (Azure) and floci-gcp (GCP).

## Getting started

Requirements: JDK 25, Docker (for the compatibility suite).

```bash
./mvnw quarkus:dev     # dev mode on port 4599
./mvnw test            # unit + integration tests
make test-java-compat  # oci-java-sdk suite against a running emulator
```

## Ground rules

1. **Preserve OCI protocol compatibility.** floci-oci implements real OCI wire protocols —
   request/response shapes, error codes (`NotAuthorizedOrNotFound`, `BucketNotFound`, …),
   headers (`opc-request-id`, `opc-next-page`, `etag`, `opc-work-request-id`) must match
   what the real services return. Never invent protocol behavior: check the OCI SDK
   sources (oci-go-sdk models are the reference).
2. **No custom endpoint shapes.** If it isn't an OCI API, it doesn't belong on the wire
   (emulator-internal endpoints live under `/_floci-oci`).
3. **Behaviour changes come with a test.** Prefer SDK-based validation via
   `compatibility-tests/` in addition to RestAssured integration tests.
4. **No service-keyed switches in core.** Services register their own `ServiceDescriptor`;
   `ServiceRegistry`, `ServiceEnabledFilter` and `StorageFactory` resolve metadata through
   descriptors.
5. **`application.yml` is the source of truth** for effective defaults; keep
   `@WithDefault` annotations in agreement (`ApplicationDefaultsTest` enforces this).

## Adding a new OCI service

See [docs/contributing.md](docs/contributing.md) for the step-by-step checklist, and
[AGENTS.md](AGENTS.md) for the full operating rules.

## Pull requests

- Conventional commits: `feat:`, `fix:`, `perf:`, `docs:`, `chore:`
- Keep changes focused; avoid unrelated refactors
- Update docs when behaviour is user-facing

## Release awareness

Merges to `main` do not imply a release. Release branches define stable lines; tags
trigger the publishing workflows.
