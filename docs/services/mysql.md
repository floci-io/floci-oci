# MySQL HeatWave

OCI MySQL HeatWave DB systems, API version `20190415` (SDK package `mysql`, clients
`DbSystemClient`, `MysqlaasClient` and `WorkRequestsClient`, CLI `oci mysql`). Each DB system is
backed by a real MySQL container, so the endpoint `GetDbSystem` returns is one an application can
connect to with the admin user it was created with. Real OCI serves the API on
`mysql.{region}.ocp.{domain}`; floci-oci serves it on port 4599 with the other services.

## Supported operations

| Area | Operations |
|---|---|
| DB systems | `CreateDbSystem`, `GetDbSystem`, `ListDbSystems`, `UpdateDbSystem`, `DeleteDbSystem` |
| Runtime | `StartDbSystem`, `StopDbSystem`, `RestartDbSystem` |
| Catalog | `ListShapes`, `ListVersions` |
| Configurations | `CreateConfiguration`, `GetConfiguration`, `ListConfigurations`, `UpdateConfiguration`, `DeleteConfiguration` |
| Work requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs`, `CancelWorkRequest` |

## How a DB system runs

- **One container per DB system.** `CreateDbSystem` returns the system `CREATING` with an
  `IN_PROGRESS` work request and starts a `mysql:<version>` container in the background. Once
  MySQL answers on TCP the system becomes `ACTIVE` and the work request `SUCCEEDED`. Terraform
  waits on the lifecycle state, `oci mysql db-system create --wait-for-state` on the work request.
  A container that exits, or one that is not ready within
  `FLOCI_OCI_SERVICES_MYSQL_READY_TIMEOUT_SECONDS` (default 180), marks the system `FAILED` (MySQL
  has no `NEEDS_ATTENTION` state) and its work request `FAILED`, with the reason listed by
  `ListWorkRequestErrors`.
- **Version and image.** `mysqlVersion` takes a version from `ListVersions` (`8.4.4`) or a family
  (`8.4`, its newest version); without one a DB system runs `8.4.4`. The version picks the image
  through `FLOCI_OCI_SERVICES_MYSQL_IMAGE` (default `mysql:{version}`). A new `mysqlVersion` on
  `UpdateDbSystem` restarts the server on the new image.
- **Admin user.** `adminUsername` and `adminPassword` are required when the sidecar runs. The admin
  is created with every privilege on the first start; the password is not stored. A new
  `adminPassword` on `UpdateDbSystem` is accepted and not applied to the server.
- **Endpoint.** MySQL's port is published on a host port from
  `FLOCI_OCI_SERVICES_MYSQL_BASE_PORT`..`MAX_PORT` (default `13306..13405`), one per DB system,
  and `endpoints[0]` carries the host and port to connect to: `localhost` and that port when
  floci-oci runs on the host, the container's name and `3306` when it runs in a container (clients
  on the same Docker network). The top-level `port` and `portX` stay as requested (default `3306`
  and `33060`), since Terraform replaces a DB system whose `port` changes. The X Protocol port is
  not published.
- **Stop, start and restart** keep the data volume. `StopDbSystem` and `RestartDbSystem` require
  `shutdownType` (`IMMEDIATE`, `FAST` or `SLOW`); the container is stopped the same way for each.
- **Delete** removes the container. The data volume goes with it in `memory` storage mode or when
  `FLOCI_OCI_STORAGE_PRUNE_VOLUMES_ON_DELETE` is set. The system stays readable as `DELETED`.
- **Mock mode** (`FLOCI_OCI_SERVICES_MYSQL_MOCK=true`) starts nothing: DB systems become
  `ACTIVE` at once, with an endpoint nothing listens on, and the admin credentials are optional.

## Wire notes

- Every list returns a bare JSON array (`[]DbSystemSummary`, `[]ShapeSummary`,
  `[]VersionSummary`, `[]ConfigurationSummary`, `[]WorkRequestSummary`), paged with
  `opc-next-page` where the SDK pages it; `ListShapes` and `ListVersions` are not paged. Every
  list requires `compartmentId`.
- `CreateDbSystem` answers `200` with the DbSystem, `etag`, `location` and `opc-work-request-id`.
  Update, delete, start, stop and restart answer `202` with only `opc-work-request-id`. The SDKs
  record no status codes; these follow the other floci-oci services with work requests.
- `adminUsername` and `adminPassword` never appear in a response, as in the SDK model.
- `source` is always `{"sourceType": "NONE"}`.
- `subnetId` and `nsgIds` must exist in Networking, otherwise
  `400 RelatedResourceNotAuthorizedOrNotFound`. Nothing is attached to a real network.

## Emulator choices

These defaults are floci-oci's own; no Oracle source in the reference corpus states them.

- When omitted: `dataStorageSizeInGBs` 50, fault domain `FAULT-DOMAIN-1`, maintenance window
  `SUN 00:00`, a backup policy enabled with window `00:00` and 7 days of retention,
  `deletionPolicy` `DELETE` / `SKIP_FINAL_BACKUP` / not protected, `crashRecovery` `ENABLED`,
  `databaseManagement` `DISABLED`, `READ_WRITE` / `UNRESTRICTED`, and system-generated
  certificates. A display name defaults to `mysqldbsystem<timestamp>`.
- The maintenance window comes back exactly as sent (`sun 01:00` stays lowercase), since Terraform
  compares it as a string.
- A DB system with `deletionPolicy.isDeleteProtected` true answers `DeleteDbSystem` with
  `409 IncorrectState` until the protection is turned off.
- `CancelWorkRequest` answers `409 IncorrectState`: operations are not cancellable.
- `dataStorageSizeInGBs` may grow, never shrink (`400 InvalidParameter`). Storage limits, backup
  policies and maintenance windows are recorded, not acted on.

## Shapes, versions and configurations

- **Shapes** and their CPU and memory sizes come from Oracle's
  [Supported Shapes](https://docs.oracle.com/en-us/iaas/mysql-database/doc/supported-shapes.html)
  page: the ECPU shapes (`MySQL.Free`, `MySQL.2` to `MySQL.384`), a subset of the OCPU shapes
  (`MySQL.VM.Standard.E3.*`, `E4.*`, `Standard3.*`) and the `MySQL.HeatWave.*` shapes.
  `isSupportedFor` lists `HEATWAVECLUSTER` for the shapes that page says support one. A shape
  changes nothing about the container.
- **Versions** are a subset of Oracle's
  [MySQL Server Versions](https://docs.oracle.com/en-us/iaas/mysql-database/doc/mysql-server-versions.html),
  picked so each one exists as a `mysql` image tag: `8.0.39` to `8.0.41`, `8.4.2` to `8.4.4`,
  `9.1.0` and `9.2.0`. The family labels (`8.4 - Long Term Support release`) are floci-oci's.
- **Default configurations** are `<shape>.Standalone` and `<shape>.HA` for every shape, the names
  Oracle's page gives. Their OCIDs are stable across restarts, they belong to the tenancy, and
  they carry no variables. A DB system created without `configurationId` gets the Standalone (or,
  with `isHighlyAvailable`, the HA) default of its shape.
- **Custom configurations** take a `shapeName`, an optional `parentConfigurationId` whose variables
  they start from, and `variables`. The variables are stored and returned but not applied to the
  server. A configuration a DB system uses cannot be deleted (`409 Conflict`), and a DB system's
  configuration must be for its shape (`400 InvalidParameter`).

## Not emulated

Backups and restore, HeatWave clusters, read replicas, channels, blue-green deployments,
maintenance events and DB system status reports. Creating a DB system from a source other than
`NONE` answers `400 InvalidParameter`. `isHighlyAvailable` is recorded; there is one server either
way.

## Quickstart

The subnet has to exist first: create one as in the [Networking quickstart](networking.md#quickstart).

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Create a DB system and wait for it
oci mysql db-system create $E --compartment-id "$TENANCY" --shape-name MySQL.2 \
  --subnet-id <subnetId> --admin-username admin_user --admin-password 'BEstrO0ng_#11' \
  --wait-for-state SUCCEEDED

# Where to connect
oci mysql db-system get $E --db-system-id <dbSystemId> --query 'data.endpoints[0]'
mysql -h 127.0.0.1 -P <port> -u admin_user -p
```
