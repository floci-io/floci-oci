# Database with PostgreSQL

OCI Database with PostgreSQL, API version `20220915` (SDK package `psql`, client
`PostgresqlClient`, CLI `oci psql`). Each DB system is backed by a real PostgreSQL container, so
the endpoint and certificate returned by `GetConnectionDetails` are ones an application can
connect to. Real OCI serves the API on `postgresql.{region}.oci.{domain}`; floci-oci serves it on
port 4599 with the other services.

## Supported operations

| Area | Operations |
|---|---|
| DB systems | `CreateDbSystem`, `GetDbSystem`, `ListDbSystems`, `UpdateDbSystem`, `DeleteDbSystem`, `ChangeDbSystemCompartment` |
| Runtime | `StartDbSystem`, `StopDbSystem`, `RestartDbInstanceInDbSystem`, `ResetMasterUserPassword` |
| Connection | `GetConnectionDetails`, `GetPrimaryDbInstance`, `UpdateDbSystemDbInstance` |
| Catalog | `ListShapes`, `ListDefaultConfigurations`, `GetDefaultConfiguration` |
| Work requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs` |

## How a DB system runs

- **One container per DB system.** `CreateDbSystem` returns the system `CREATING` with an
  `IN_PROGRESS` work request and starts a `postgres:<major>-alpine` container in the background.
  Once PostgreSQL accepts connections the system becomes `ACTIVE` and the work request
  `SUCCEEDED`, which is what Terraform and `oci psql db-system create --wait-for-state` wait on.
  A container that exits, or one that is not ready within
  `FLOCI_OCI_SERVICES_PSQL_READY_TIMEOUT_SECONDS` (default 120), marks both `FAILED`, and the
  reason is listed by `ListWorkRequestErrors`.
- **Version and image.** `dbVersion` takes a major (`15`) or a major and minor (`15.8`); its
  major picks the image through `FLOCI_OCI_SERVICES_PSQL_IMAGE` (default
  `postgres:{major}-alpine`). Once the server is up, `dbVersion` reports the server's own
  version, such as `15.8`.
- **Endpoint.** PostgreSQL's port is published on a host port from
  `FLOCI_OCI_SERVICES_PSQL_BASE_PORT`..`MAX_PORT` (default `15432..15531`), one per DB system.
  `GetConnectionDetails` returns `localhost` and that port when floci-oci runs on the host. When
  floci-oci runs in a container it returns the PostgreSQL container's name and port `5432`, for
  clients on the same Docker network; set `FLOCI_OCI_DOCKER_ENDPOINT_MODE=published` to get the
  Docker host and the published port instead, for clients on the host.
- **TLS.** Every DB system has its own self-signed certificate, returned as `caCertificate`.
  PostgreSQL serves it, so `sslmode=verify-full` works with that file as `sslrootcert`.
  Unencrypted connections are accepted too.
- **Credentials.** The admin user and password come from `credentials` (`PLAIN_TEXT`, or
  `VAULT_SECRET` read from a secret in floci-oci's own Vault). As on real OCI the password is
  never returned, and floci-oci does not store it either. `ResetMasterUserPassword` changes it
  in the running database.
- **Stop, start, restart.** `StopDbSystem` removes the container and keeps the data volume
  (`INACTIVE`); `StartDbSystem` and `RestartDbInstanceInDbSystem` bring it back with the same
  data, and the same host port when it is still free.
- **Delete.** `DeleteDbSystem` removes the container. The DB system stays readable as `DELETED`,
  as on real OCI, since clients poll it to see the delete finish. The data volume is removed in
  `memory` storage mode or when `prune-volumes-on-delete` is set, like the other sidecars.
- **Mock mode.** `FLOCI_OCI_SERVICES_PSQL_MOCK=true` starts no container: every DB system is
  `ACTIVE` at once and every operation completes immediately, with nothing listening on the
  endpoint. Useful where Docker is not available.
- **Teardown.** Containers are stopped on shutdown and removed, with their volumes, by
  `POST /_floci-oci/state/reset`, whatever `prune-volumes-on-delete` says. After a restart of
  floci-oci with persistent storage, the DB systems that were `ACTIVE` are started again. An
  operation the restart interrupted is settled first: a delete finishes, a create ends
  `FAILED` (its master password was never stored), and a stop, restart or password reset fails
  its work request, leaving the system `ACTIVE` as it was.

## Wire notes

- `CreateDbSystem` returns `200` with the DB system, an `etag` and `opc-work-request-id`. Every
  other mutation returns `202` with `opc-work-request-id` and no body.
- Lists are wrapped (`{"items":[…]}`) and paged with `opc-next-page`.
- `shape` accepts what Terraform sends (`PostgreSQL.VM.Standard.E4.Flex.2.32GB`, or
  `PostgreSQL.VM.Standard.E4.Flex` with `instanceOcpuCount` and `instanceMemorySizeInGBs`) and
  the bare shape. The response carries the bare shape and both sizes.
- `storageDetails.availabilityDomain` is required when `isRegionallyDurable` is `false` and
  rejected when it is `true` (`400 InvalidParameter`).
- Operations need the right state: `StartDbSystem` an `INACTIVE` system, the others an `ACTIVE`
  one. Otherwise they return `409 IncorrectState`.
- `compartmentId` must be an existing compartment or the tenancy, and the request must be sent
  to a region the tenancy is subscribed to, like every other regional service.

## Emulator choices

OCI's SDKs leave these open, so floci-oci picks:

- **Versions and shapes.** Majors `14` and `15` and shapes `VM.Standard.E4.Flex` and
  `VM.Standard.E5.Flex` are the ones Oracle's Terraform examples use. `16` and `17` are accepted
  as well so newer versions can be tried. Anything else is `400 InvalidParameter`.
- **OCIDs.** `ocid1.postgresqldbsystem…`, `ocid1.postgresqldbinstance…` and
  `ocid1.postgresqldefaultconfiguration…`. A default configuration OCID is the same on every
  start.
- **Default configurations.** Each version has a flexible one and a fixed one per shape and
  size. A DB system created with a sized shape (`PostgreSQL.VM.Standard.E4.Flex.2.32GB`) gets the
  fixed configuration for that size, and one created with a bare shape and separate sizes gets
  the flexible one, which is what Terraform needs to show no changes on the next plan.
- **Work requests.** The resource entity type is `postgresqlDbSystem`. Start, stop, restart and
  password reset report `UPDATE_POSTGRESQL_DB_SYSTEM`, since the API has no value of their own.
- **`credentials` is required** on create, because only a fresh DB system can be created.
- **Several instances are one database.** `instanceCount` above 1 gives that many instance
  records, all served by the one container; there are no replicas.
- **Defaults.** When omitted: 2 OCPUs and 32 GB, 75000 IOPS, maintenance window `SUN 00:00`,
  backup policy `NONE`, and the default configuration for the version.
- **Network.** `subnetId` and `nsgIds` are stored unchecked, since there is no networking
  service.
- **Update is metadata only.** Shape, sizes, IOPS and management policy change what the API
  reports, not the running container.

## Not implemented yet

Backups, restore and point-in-time recovery; custom configurations (`configId` must be a default
configuration); replicas, failover, switchover, role changes and `PatchDbSystem`; Kerberos and
insights; creating a DB system from a backup or another DB system (`501`). The DB system service
limit is not enforced.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Create a DB system and wait for it
ID=$(oci psql db-system create $E --compartment-id "$TENANCY" --display-name demo-db \
  --db-version 16 --shape PostgreSQL.VM.Standard.E4.Flex.2.32GB \
  --network-details '{"subnetId":"ocid1.subnet.oc1.iad.demo"}' \
  --storage-details '{"systemType":"OCI_OPTIMIZED_STORAGE","isRegionallyDurable":true}' \
  --credentials '{"username":"admin_user","passwordDetails":{"passwordType":"PLAIN_TEXT","password":"BEstrO0ng_#11"}}' \
  --wait-for-state SUCCEEDED --query 'data.resources[0].identifier' --raw-output)

# Where to connect, and the certificate to trust
oci psql connection-details get $E --db-system-id "$ID" > connection.json
jq -r '.data."ca-certificate"' connection.json > ca.pem
PORT=$(jq -r '.data."primary-db-endpoint".port' connection.json)

psql "host=localhost port=$PORT dbname=postgres user=admin_user sslmode=verify-full sslrootcert=ca.pem"
```
