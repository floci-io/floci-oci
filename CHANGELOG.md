# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **release:** one-button releases — new "Release Cut" workflow (`workflow_dispatch`, with a dry-run option) runs semantic-release from `main`: bumps `pom.xml`, writes `CHANGELOG.md`, commits, tags, and creates the GitHub Release; the tag then triggers the existing publishing workflow. `release/x.y.x` branches are retired for now
- **docker:** every emulator-created container and volume is now labelled `floci=true` and `floci_emulator=floci-oci` (plus `floci_namespace` when `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE` is set), so one emulator's resources can be filtered or pruned without touching sibling Floci emulators on the same Docker host: `docker volume prune --filter label=floci_emulator=floci-oci`
- **docs:** documented `FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE`

### Changed

- **docker:** the `floci-oci-` container/volume name prefix is now owned by `ContainerStorageHelper` instead of being spelled at call sites. Default names are unchanged (`floci-oci-fnserver`); with a resource namespace configured, the namespace now lands after the cloud token (`floci-oci-<ns>-fnserver`, previously `floci-<ns>-oci-fnserver`). If you run namespaced instances, remove orphaned containers from earlier versions with `docker rm -f $(docker ps -aq --filter name=^/floci-)`

## [0.1.0] - 2026-07-27

Initial release. floci-oci emulates seven Oracle Cloud Infrastructure services on a
single port (`4599`) over real OCI wire protocols, so the OCI SDKs, the OCI CLI,
Terraform and OpenTofu work unchanged against `http://localhost:4599`.

### Added

- **config:** `@ConfigMapping`-based `EmulatorConfig` under `floci-oci.*`; every setting overridable via `FLOCI_OCI_*` environment variables, with per-service enable flags and per-service storage overrides
- **core:** OCI error contract — `{"code": "...", "message": "..."}` bodies with correct HTTP status, `NotAuthorizedOrNotFound` on 404 exactly as real OCI conflates missing and unauthorized
- **core:** protocol primitives — `opc-request-id` on every response (echoing the caller's when supplied), `opc-client-request-id` echo, `limit`/`page` pagination with the `opc-next-page` header, OCID minting (`ocid1.<type>.<realm>.<region>.<unique>`), and etag generation for `if-match` / `if-none-match` concurrency
- **core:** self-registering `ServiceRegistry` / `ServiceDescriptor` — each service declares its own enablement, storage key and JAX-RS resources at startup, so adding a service never requires editing a switch in core; `ServiceEnabledFilter` answers `503` for disabled services
- **core:** shared work-request plane — `202` + `opc-work-request-id` for async operations, partitioned per owning service so each service exposes work requests under its own API version prefix
- **auth:** OCI request-signature parsing (draft-cavage HTTP Signatures) — tenancy, user and region are derived from the `Authorization: Signature …` header for request context; the RSA signature is parsed, never verified. `FLOCI_OCI_AUTH_REQUIRE_SIGNATURE` rejects unsigned requests with `401`
- **storage:** four storage modes — `memory` (default), `persistent`, `hybrid`, `wal` — behind a single `StorageFactory`, with `TenancyAwareStorageBackend` partitioning every key by tenancy OCID
- **identity:** Identity/IAM API (`/20160918/…`) — compartment CRUD including `compartmentIdInSubtree` listing and async delete via work request; users, groups and user-group memberships; policies with statements stored verbatim; reference data (availability domains, regions, region subscriptions, tenancy); etag concurrency on every mutation; bare-array list shapes
- **objectstorage:** Object Storage API (`/n/{namespace}/b/{bucket}/o/{object}`) — namespaces; bucket CRUD (delete requires an empty bucket); objects with `Content-MD5` verification, `opc-meta-*` user metadata, Range reads with `206`, and conditional headers; `ListObjects` with `prefix`/`start`/`end`/`delimiter`/`fields` and `nextStartWith` truncation; rename; async copy via work request; multipart uploads returning per-part `etag` and `opc-content-md5`; pre-authenticated requests with an anonymous `/p/{token}/…` data path
- **queue:** Queue API (`/20210201/…`) — work-request-driven control plane where mutations return no body, only `opc-work-request-id`; data plane with visibility timeouts, `0`-visibility peeks that still increment delivery count, long polling bounded by `timeoutInSeconds`, dead-letter queues, per-channel filtering and stats; wrapped `{"items":[…]}` list shape
- **kms:** Key Management API (`/20180608/…`) — vaults and keys with `scheduleDeletion`/`cancelDeletion` in place of a DELETE verb, key versions and rotation; **real cryptography** — AES-GCM encrypt/decrypt with an envelope that embeds the key-version id so ciphertext survives rotation, RSA and ECDSA sign/verify through JCA, CRC32 `plaintextChecksum` on decrypt and generated data keys. Keys reach `ENABLED`, not `ACTIVE`
- **vault:** Vault Secrets (`/20180608/secrets…`) and secret retrieval (`/20190301/secretbundles…`) — secret versions with `CURRENT`/`PREVIOUS`/`LATEST` stages; the `Secret` shape never echoes content, retrieval goes through secret bundles including the bodyless `POST` `getSecretBundleByName`
- **streaming:** Streaming API (`/20180418/…`) — partitioned append-only log with key-hash placement; opaque cursors (`TRIM_HORIZON`, `LATEST`, `AT_OFFSET`, `AFTER_OFFSET`, `AT_TIME`), group cursors with commit and heartbeat resume, `opc-next-cursor` paging; `CreateStream` returns a full body *and* a work-request id, matching the real dual-mode response
- **functions:** Functions API (`/20181201/…`) — applications and functions with deterministic image digests (`sha256:…` that changes when the image changes, satisfying the Terraform provider's diff contract); invocation is proxied to a shared **`fnproject/fnserver` sidecar** that runs your real FDK image as a sibling container, honouring `fn-invoke-type: detached` and `is-dry-run`. `FLOCI_OCI_SERVICES_FUNCTIONS_MOCK=true` disables Docker entirely and keeps the management plane fully usable — this service is the reference implementation for container sidecars
- **lifecycle:** boot orchestration with configurable init hooks; `/health` plus the `/_floci-oci/{health,info,diagnose,config,init}` endpoints and `/_floci-oci/state/{reset,nuke}` for test isolation, which also tear down any sidecar containers
- **dns:** embedded DNS server so containers on the emulator network resolve OCI hostnames back to floci-oci
- **tls:** self-signed HTTPS served on the same port through a protocol-sniffing proxy, plus an optional dedicated `443` binding for clients that assume it
- **docker:** container lifecycle management (`ContainerBuilder`, `ContainerLifecycleManager`, port allocation with release on stop, log rotation), JVM and native images, and a `docker-compose.yml` mounting `/var/run/docker.sock` for sidecar orchestration
- **cli:** `ocilocal` wrapper that points the OCI CLI at the emulator without per-command endpoint flags
- **tests:** 414 unit and REST integration tests covering the protocol core and every service, all running without Docker
- **compat:** four compatibility suites run against a real emulator — oci-java-sdk (18 tests), oci Python SDK (20 tests), Terraform (6) and OpenTofu (6), the IaC suites covering 11 resources through apply → zero-drift plan → destroy. `make compat-docker` runs the whole matrix on a shared Docker network
- **docs:** MkDocs site with per-service reference pages, configuration and environment-variable guides, and a compatibility-suite guide

### Fixed

- **objectstorage:** answer `GET /n/{ns}/b/{bucket}/retentionRules` with an empty list — the Terraform provider calls it unconditionally on every bucket read and a `404` silently dropped the bucket from state
- **objectstorage:** return `opc-content-md5` on `UploadPart`, required by the Python SDK's `UploadManager` multipart flow
- **queue:** emit an empty `systemTags` object on queue reads, without which `oci_queue_queue` drifted on every Terraform plan

### Known deviations

- **kms:** real OCI gives each vault its own management and crypto hostname. The OCI SDKs reject endpoints containing a path, so the emulator cannot encode the vault in a path suffix and serves every vault from the single emulator host, resolving the vault from the request's compartment instead.

---

[Unreleased]: https://github.com/floci-io/floci-oci/compare/0.1.0...HEAD
[0.1.0]: https://github.com/floci-io/floci-oci/releases/tag/0.1.0
