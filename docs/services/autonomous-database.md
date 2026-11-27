# Autonomous Database

OCI Autonomous Database (Serverless) on the Database service, API version `20160918` (SDK package
`database`, client `DatabaseClient`, CLI `oci db autonomous-database`). Real OCI serves the API on
`database.{region}.{domain}`; floci-oci serves it on port 4599 under the `/20160918` root it shares
with Identity and Networking.

!!! note "Management plane only"
    floci-oci emulates Autonomous Databases in mock mode for now: a database is a record that
    becomes `AVAILABLE` at once, with connection strings and a wallet that nothing listens behind.
    Set `FLOCI_OCI_SERVICES_DATABASE_MOCK=true`. Without it `CreateAutonomousDatabase` answers
    `501 MethodNotImplemented`, the only 5xx OCI SDKs do not retry, rather than pretend a database
    runs. A real database on the Autonomous Database Free container is the next step.

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
  It is never stored and never returned.
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
- **Lifecycle.** A database is `AVAILABLE` on create. `StopAutonomousDatabase` needs it
  `AVAILABLE` and leaves it `STOPPED`; `StartAutonomousDatabase` needs it `STOPPED`;
  `RestartAutonomousDatabase` needs it `AVAILABLE`. Any other state answers `409 Conflict`
  (inferred): nothing changes a mock database's state on its own, so the condition never clears
  and the SDKs should not retry it, as they would `IncorrectState`.
- **Delete** answers `204` with `opc-work-request-id` and keeps the database readable as
  `TERMINATED`, which Terraform and `--wait-for-state` poll. Every other operation on a terminated
  database answers `404 NotAuthorizedOrNotFound`.
- **Work requests.** Every mutation records a `SUCCEEDED` work request on the core work-request
  client with one resource of `entityType` `autonomousDatabase` (action `CREATED`, `UPDATED` or
  `DELETED`); Terraform finds the new database's id there after a create. Operation types
  (`CREATE_AUTONOMOUS_DATABASE` and so on) are inferred.
- **Connection strings** follow the Autonomous Database Free container: the five mTLS aliases
  `<db>_high`, `_medium`, `_low`, `_tp` and `_tpurgent`, TCPS on `localhost:1522`, service names
  `<db>_<level>.adb.oraclecloud.com`. They change with `dbName`. Nothing listens on them in mock
  mode.
- **Wallet.** `GenerateAutonomousDatabaseWallet` returns a zip (`application/octet-stream`, with
  `etag`, `content-length` and `last-modified`) holding `tnsnames.ora`, `sqlnet.ora`,
  `ojdbc.properties` and a `README`. `password` must be at least 8 characters with a letter and a
  number or special character. A mock wallet carries no keystore.

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

Running databases (the Autonomous Database Free sidecar), clones and backups, scaling,
`ListAutonomousDbVersions`, regional wallets, private endpoints, Data Guard, and
`ChangeAutonomousDatabaseCompartment`.
