# Contributing

Contributions are welcome! floci-oci follows the same architecture and conventions as its
siblings [floci](https://github.com/floci-io/floci) (AWS),
[floci-az](https://github.com/floci-io/floci-az) and
[floci-gcp](https://github.com/floci-io/floci-gcp).

## Development setup

Requirements: JDK 25, Docker (for the compat suite).

```bash
./mvnw quarkus:dev     # dev mode on port 4599
./mvnw test            # unit + integration tests
make test-java-compat  # oci-java-sdk suite against a running emulator
```

## Project structure

```
io.floci.oci
├── config/           EmulatorConfig (@ConfigMapping prefix floci-oci)
├── core/
│   ├── common/       OciException/mapper, opc-request-id filter, Ocids, OciPage, Etags,
│   │                 ServiceRegistry + self-registering ServiceDescriptor
│   ├── auth/         OCI Signature parsing (never verified)
│   ├── storage/      StorageBackend (memory/persistent/hybrid/wal), tenancy isolation
│   └── workrequest/  Shared async-operation plane
├── lifecycle/        Boot/shutdown, init hooks, /health + /_floci-oci/*
└── services/<svc>/   Controller (JAX-RS) → Service → model/Stored*.java
```

## Adding a new OCI service

1. Create `services/<svc>/` with `<Svc>Controller`, `<Svc>Service`, `model/`
2. The service registers its own `ServiceDescriptor` in an `@Observes StartupEvent` method,
   adding a service must never require a service-keyed switch in core
3. Add `<Svc>ServiceConfig { enabled(); }` to `EmulatorConfig.ServicesConfig` + the YAML block
4. Wire storage through `StorageFactory`
5. Add the test trio: `<Svc>ServiceTest` (unit), `<Svc>RestIntegrationTest`,
   `<Svc>DisabledRestIntegrationTest` (asserts 503)
6. Validate against the real SDK in `compatibility-tests/`
7. Add `docs/services/<svc>.md` and the mkdocs nav entry

**Never invent protocol behavior.** Check the wire contract against the OCI SDK sources
(the `oci-go-sdk` generated models are the closest thing OCI has to a machine-readable
wire model).

## Code style

[AGENTS.md](https://github.com/floci-io/floci-oci/blob/main/AGENTS.md#code-style) carries
the full list. The rules worth knowing before your first PR:

- **Write explicit types. Do not use `var`.** The concrete type at a call site is usually
  what a wire-contract reviewer needs to see. The one exception is a record deconstruction
  pattern.
- **Import the classes you use.** No fully-qualified names inline, except for a genuine name
  collision in that file, with a comment saying what collides.
- **No wildcard imports in `src/main`.** Static wildcards are fine in tests.
- **Never leave a `catch` block empty.** If swallowing is correct, name the variable
  `ignored` or `expected` and say why in a comment.
- **Always use braces in conditionals**, and use constructor injection.
- **Tests**: JUnit 5 with Hamcrest and RestAssured. Name methods as a camelCase sentence
  or `method_scenario_expectation`, never `testX`.
- **Docs**: no em-dashes. Use colons, commas, or periods.

Existing code does not yet satisfy all of these everywhere. Match the rules in code you add
or change; leave unrelated cleanups for their own PR.

## Pull requests

- Conventional commits: `feat:`, `fix:`, `perf:`, `docs:`, `chore:`
- Keep changes focused; behaviour changes come with a test
- See [AGENTS.md](https://github.com/floci-io/floci-oci/blob/main/AGENTS.md) for the full
  operating rules

## Releases

Stable releases ship on the **1st and 3rd Tuesday of each month**. Merging to `main` does not cut a release: the change rides the next train, and reaches the `nightly` image on the next nightly build.

Maintainers cut releases from `main` with the Release Cut workflow, which runs semantic-release over the Conventional Commits since the last tag. That is why the commit type matters: `feat:` and `fix:` move the version, `docs:` and `chore:` do not. `CHANGELOG.md` is generated from those messages and is not edited by hand; a genuine correction goes in a PR carrying the `changelog-edit` label.
