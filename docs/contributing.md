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
2. The service registers its own `ServiceDescriptor` in an `@Observes StartupEvent` method —
   adding a service must never require a service-keyed switch in core
3. Add `<Svc>ServiceConfig { enabled(); }` to `EmulatorConfig.ServicesConfig` + the YAML block
4. Wire storage through `StorageFactory`
5. Add the test trio: `<Svc>ServiceTest` (unit), `<Svc>RestIntegrationTest`,
   `<Svc>DisabledRestIntegrationTest` (asserts 503)
6. Validate against the real SDK in `compatibility-tests/`
7. Add `docs/services/<svc>.md` and the mkdocs nav entry

**Never invent protocol behavior** — check the wire contract against the OCI SDK sources
(the `oci-go-sdk` generated models are the closest thing OCI has to a machine-readable
wire model).

## Pull requests

- Conventional commits: `feat:`, `fix:`, `perf:`, `docs:`, `chore:`
- Keep changes focused; behaviour changes come with a test
- See [AGENTS.md](https://github.com/floci-io/floci-oci/blob/main/AGENTS.md) for the full
  operating rules
