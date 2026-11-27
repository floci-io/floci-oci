# Autonomous Database

OCI Autonomous Database (Serverless) on the Database service, API version `20160918` (SDK package
`database`, client `DatabaseClient`, CLI `oci db autonomous-database`). Real OCI serves the API on
`database.{region}.{domain}`; floci-oci serves it on port 4599 under the `/20160918` root it shares
with Identity and Networking.

Two modes:

- **Real (the default).** Each Autonomous Database runs in its own
  [Autonomous Database Free](https://container-registry.oracle.com) container
  (`container-registry.oracle.com/database/adb-free:latest-26ai`). Clients connect over mTLS with
  the wallet `GenerateAutonomousDatabaseWallet` returns, as they would to OCI.
- **Mock** (`FLOCI_OCI_SERVICES_DATABASE_MOCK=true`). No container starts: a database is a record
  that becomes `AVAILABLE` at once, with connection strings and a wallet that nothing listens
  behind. Use it for management-plane tests, or wherever the requirements below cannot be met.

## Real mode requirements

!!! warning "A large image and about 4 GB of memory per database"
    - **Image:** about 12 GB, linux/amd64. The first create pulls it, which can take several
      minutes; the default 5 minute pull limit is often too short. Raise
      `FLOCI_OCI_DOCKER_IMAGE_PULL_TIMEOUT_SECONDS` (for example to `1800`), or pull the image
      beforehand with `docker pull container-registry.oracle.com/database/adb-free:latest-26ai`.
    - **Memory:** each database peaks at about 4 GB. Oracle recommends 4 CPUs and 8 GB for the
      container.
    - **Start time:** about 3 minutes from a first start to `AVAILABLE`, about 20 seconds after a
      stop and start (measured on a 6 vCPU amd64 host).
    - **Docker:** the container needs `--cap-add SYS_ADMIN` and `--device /dev/fuse`, which
      floci-oci adds; it does not run privileged. The Docker host must have `/dev/fuse`. The
      container needs a network interface but no internet access: the database ships in the image.

floci-oci publishes only the container's mTLS listener (container port 1522) on a host port from
`FLOCI_OCI_SERVICES_DATABASE_BASE_PORT` to `FLOCI_OCI_SERVICES_DATABASE_MAX_PORT` (11522 to 11621).

## Supported operations

| Area | Operations |
|---|---|
| Databases | `CreateAutonomousDatabase`, `GetAutonomousDatabase`, `ListAutonomousDatabases`, `UpdateAutonomousDatabase`, `DeleteAutonomousDatabase` |
| Runtime | `StartAutonomousDatabase`, `StopAutonomousDatabase`, `RestartAutonomousDatabase` |
| Wallet | `GenerateAutonomousDatabaseWallet` |
| Work requests | `GetWorkRequest` on the core work-request client (`/20160918/workRequests/{id}`) |

## Behaviour

- **Create** takes `source` `NONE` (or no `source`). Clones, backups, refreshable clones and Data
  Guard sources answer `400 InvalidParameter`. `compartmentId` must be an active compartment, and
  `subnetId` and `nsgIds`, when sent, must exist in Networking. `adminPassword` is required unless
  `secretId` is sent; it must be 12 to 30 characters with an upper-case letter, a lower-case
  letter and a number, and no double quote or the word `admin` (Oracle's documented ADMIN rule).
  It is never stored and never returned. In real mode it reaches only the container's
  environment, and `secretId` without `adminPassword` answers `501 MethodNotImplemented`, since
  floci-oci does not read the password from Vault yet.
- **dbName** must start with a letter and hold only letters and digits, at most 30, and must be
  unique in the tenancy (the CLI documents both rules). Names compare case-insensitively, and a
  terminated database frees its name (inferred). A clash answers `400 InvalidParameter`.
- **Echoed fields.** Every create and update field that the AutonomousDatabase model also has comes
  back exactly as sent: `dbVersion`, `characterSet`, `ncharacterSet`, `computeModel`,
  `computeCount`, `whitelistedIps`, tags and the rest, so Terraform plans no diff. Sizes stay
  consistent: `dataStorageSizeInGBs` sets `dataStorageSizeInTBs` (rounded up) and the reverse.
- **Defaults** for what a request leaves out are inferred from Oracle's Serverless documentation,
  not pinned by the SDK: `dbName` `DB<yyyyMMddHHmmss>`, `displayName` the `dbName`, `characterSet`
  `AL32UTF8`, `ncharacterSet` `AL16UTF16`, `computeModel` `ECPU` with `computeCount` 2,
  `dataStorageSizeInTBs` 1, `dbWorkload` `OLTP`, `dbVersion` `19c`, `licenseModel`
  `LICENSE_INCLUDED`, `isMtlsConnectionRequired` true, `backupRetentionPeriodInDays` 60.
- **Lifecycle in mock mode.** A database is `AVAILABLE` on create. `StopAutonomousDatabase` needs
  it `AVAILABLE` and leaves it `STOPPED`; `StartAutonomousDatabase` needs it `STOPPED`;
  `RestartAutonomousDatabase` needs it `AVAILABLE`. Any other state answers `409 Conflict`
  (inferred): nothing changes a mock database's state on its own, so the condition never clears
  and the SDKs should not retry it, as they would `IncorrectState`.
- **Lifecycle in real mode.** Every mutation answers at once with the transient state and an
  `IN_PROGRESS` work request; the Docker work runs in the background, and the work request ends
  `SUCCEEDED` or `FAILED`.

    | Operation | Answers | Then | Container |
    |---|---|---|---|
    | Create | `PROVISIONING` | `AVAILABLE` | pulled, created and started |
    | Stop | `STOPPING` | `STOPPED` | `docker stop` |
    | Start | `STARTING` | `AVAILABLE` | `docker start` of the same container |
    | Restart | `RESTARTING` | `AVAILABLE` | `docker restart` |
    | Delete | `TERMINATING` | `TERMINATED` | removed |

    A database is `AVAILABLE` once Docker reports the container `healthy` (the image's own
    `HEALTHCHECK`: the instance runs and the listener serves every service). A request while an
    operation is under way answers `409 IncorrectState`, which SDKs retry; in a settled state that
    does not allow it, `409 Conflict`.
- **Failures in real mode (inferred).** The Autonomous Database API has no `FAILED` state, so a
  failed operation leaves the database `UNAVAILABLE` with the reason in `lifecycleDetails`, and
  fails its work request. That covers a container that exits, one that is not healthy within
  `FLOCI_OCI_SERVICES_DATABASE_READY_TIMEOUT_SECONDS` (600), and a failed image pull. A first
  start that fails removes its container, since it holds no data; `lifecycleDetails` then carries
  the end of the container's log. `StartAutonomousDatabase` also accepts an `UNAVAILABLE`
  database, to try again.
- **A container is never recreated.** The database lives in the container's own filesystem, and a
  new container would start an empty database with an admin password floci-oci never keeps. So
  stop and start keep the container and its data, and its host port stays reserved while it is
  stopped. When floci-oci stops, it stops every container (with in-memory storage, where the
  databases are forgotten, it removes them); when it starts again it takes each container over,
  found by name and its `io.floci.resource-id` label, and starts the ones whose database was
  running. An operation the restart interrupted carries on under its own work request. A database
  whose container is gone is `UNAVAILABLE` with a `lifecycleDetails` saying so (inferred): delete
  it and create a new one. `POST /_floci-oci/state/reset` removes every container.
- **Update in real mode** changes the management-plane fields only. A new `dbName` or
  `adminPassword` answers `501 MethodNotImplemented`: the container was created with them.
- **Delete** answers `204` with `opc-work-request-id` and keeps the database readable as
  `TERMINATED`, which Terraform and `--wait-for-state` poll. Every other operation on a terminated
  database answers `404 NotAuthorizedOrNotFound`.
- **Work requests.** Every mutation records a work request on the core work-request client with
  one resource of `entityType` `autonomousDatabase` (action `CREATED`, `UPDATED` or `DELETED`);
  Terraform finds the new database's id there after a create. In mock mode it is `SUCCEEDED` at
  once. Operation types (`CREATE_AUTONOMOUS_DATABASE` and so on) are inferred.
- **Connection strings** follow the Autonomous Database Free container: the five mTLS aliases
  `<db>_high`, `_medium`, `_low`, `_tp` and `_tpurgent` over TCPS, service names
  `<db>_<level>.adb.oraclecloud.com`. In mock mode they name `localhost:1522`, change with
  `dbName`, and nothing listens on them. In real mode they are read from the container's own
  `tnsnames.ora` once it is healthy, with each address rewritten to the published host port
  (`localhost:<hostPort>` when floci-oci runs on the host, the container's name and 1522 when it
  runs in a container, following `FLOCI_OCI_DOCKER_ENDPOINT_MODE`). They keep the service names and
  the match on the server certificate's DN (`CN=localhost`), which is why any host name works.
  The container's `_tls` aliases are left out: they address its 1521 listener, which is not
  published. The container is given the database's `dbName` as `DATABASE_NAME` and
  `WORKLOAD_TYPE` `ADW` for `dbWorkload` `DW`, `ATP` otherwise.
- **Wallet.** `GenerateAutonomousDatabaseWallet` returns a zip (`application/octet-stream`, with
  `etag`, `content-length` and `last-modified`). `password` must be at least 8 characters with a
  letter and a number or special character.
    - In mock mode it holds `tnsnames.ora`, `sqlnet.ora`, `ojdbc.properties` and a `README`, and
      no keystore.
    - In real mode it is the container's own `tls_wallet` directory, once the database has been
      `AVAILABLE` (before that, `409 IncorrectState`). `tnsnames.ora` is rewritten as the
      connection strings are. `ewallet.p12`, `ewallet.pem`, `keystore.jks` and `truststore.jks`
      are re-encrypted from the container's own wallet password, which floci-oci generates per
      database and keeps only in the container's environment, to the request's `password`.
      `ewallet.p12` keeps every bag, including the Oracle secret bag a JDK keystore would drop.
      `cwallet.sso`, the auto-login wallet, needs no password and is passed through, so a JDBC
      URL such as `jdbc:oracle:thin:@<db>_high?TNS_ADMIN=/path/to/wallet` connects as `ADMIN`
      with the admin password. `generateType` makes no difference. The container's lock and
      state files (`*.lck`, `.runstate`) are left out.
    - Connect with `cwallet.sso` present. Without it, the JDBC thin driver opens `ewallet.p12`
      through the JDK's PKCS12 keystore, which cannot read the Oracle secret entry the
      Autonomous Database Free wallet carries and fails with `ORA-17957: Unable to initialize
      the key store`. The container's own `ewallet.p12` fails the same way, so this is not a
      floci-oci re-encryption difference.

## Wire notes

- `ListAutonomousDatabases` returns a bare JSON array of `AutonomousDatabaseSummary`, paged with
  `opc-next-page`, and requires `compartmentId`. It filters on `lifecycleState`,
  `lifecycleStateNotEqualTo`, `displayName`, `dbWorkload`, `dbVersion`, `isFreeTier`,
  `isDataGuardEnabled`, `infrastructureType` and `autonomousContainerDatabaseId`, sorts by
  `TIMECREATED` (newest first) or `DISPLAYNAME` (ascending), and lists terminated databases.
- Create, get, update, start, stop and restart return the AutonomousDatabase body with its `etag`;
  the mutations also return `opc-work-request-id`. Update, delete, start, stop and restart honour
  `if-match` (`412 NoEtagMatch`).

## Clients

- **OCI SDKs and CLI** work end to end: `oci db autonomous-database create`, `get`, `list`,
  `update`, `start`, `stop`, `restart`, `generate-wallet` and `delete`, with work requests read
  through `oci work-requests work-request get`.
- **Terraform and OpenTofu** cannot apply `oci_database_autonomous_database` against floci-oci
  yet. After the create, the provider waits on the `opc-work-request-id` through its core
  `WorkRequestClient`, which it builds outside `CLIENT_HOST_OVERRIDES`, so the wait goes to the
  real `iaas.<region>.oraclecloud.com` instead of floci-oci.

## Not supported yet

Clones and backups, scaling, `ListAutonomousDbVersions`, regional wallets, private endpoints, Data
Guard, and `ChangeAutonomousDatabaseCompartment`. In real mode: walletless TLS
(`isMtlsConnectionRequired` false is echoed, but connections still need the wallet), the ORDS,
APEX and Database Actions URLs (the container's port 8443 is not published), the MongoDB API,
changing `dbName` or `adminPassword`, and an admin password from a Vault `secretId`.
