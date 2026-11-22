# Logging

OCI Logging covers three APIs, all served on port 4599. Real OCI spreads them over the
`logging` and `ingestion.logging` hosts.

| API | Version | Use |
|---|---|---|
| Logging management | `20200531` | Log groups, logs, saved searches, work requests |
| Logging Ingestion | `20200831` | PutLogs |
| Logging Search | `20190909` | SearchLogs with the Logging query language |

## Supported operations

| Area | Operations |
|---|---|
| Log groups | CreateLogGroup, GetLogGroup, ListLogGroups, UpdateLogGroup, DeleteLogGroup, ChangeLogGroupCompartment |
| Logs | CreateLog, GetLog, ListLogs, UpdateLog, DeleteLog, ChangeLogLogGroup |
| Saved searches | CreateLogSavedSearch, GetLogSavedSearch, ListLogSavedSearches, UpdateLogSavedSearch, DeleteLogSavedSearch, ChangeLogSavedSearchCompartment |
| Work requests | GetWorkRequest, ListWorkRequests, DeleteWorkRequest, ListWorkRequestErrors, ListWorkRequestLogs |
| Ingestion | PutLogs |
| Search | SearchLogs |

## Wire notes

- **Asynchronous mutations:** log group and log create, update, delete and move answer `202`
  with `opc-work-request-id` and no body.
  - The work request is already `SUCCEEDED`, and its single resource names the entity
    (`loggroup` or `log`) and the action (`CREATED`, `UPDATED`, `DELETED`). That is where
    Terraform reads the new OCID from.
  - DeleteWorkRequest answers `409 Conflict`, because there is nothing left to cancel.
- **Saved searches** are synchronous and return the saved search with an `etag`.
- **List shapes:** ListLogGroups, ListLogs, ListWorkRequests and the work request errors and logs
  lists are bare arrays. ListLogSavedSearches is `{"items": [...]}`. Lists sort by `timeCreated`
  (default) or `displayName`.
- **Names:** display names must be unique within their enclosing resource: a log group in its
  compartment, a log in its log group. A duplicate is `409 Conflict`, including one a move to
  another compartment or log group would create.
- **Delete:** a log group that still has logs can't be deleted (`409 Conflict`). A deleted log group
  or log then answers `404`.
- **Logs:**
  - Defaults are `isEnabled: true` and `retentionDuration: 30`; the retention takes 30-day steps
    up to 180.
  - A `SERVICE` log needs a `configuration.source` with `sourceType: OCISERVICE`, `service`,
    `resource` and `category`.
  - GetLog carries `tenancyId`, which LogSummary does not.
- **OCIDs** use `loggroup`, `log` and `logsavedsearch`.

## Ingestion

PutLogs takes `specversion: "1.0"` and `logEntryBatches`. Each batch has `source`, `type`,
`defaultlogentrytime`, an optional `subject` and `entries` of `data`, `id` and an optional
`time`.

Each entry is stored in Oracle's [log format](https://docs.oracle.com/iaas/Content/Logging/Reference/top_level_logging_format.htm):
`data`, `id`, `source`, `specversion`, `subject`, `time`, `type`, and `oracle` with
`compartmentid`, `ingestedtime`, `loggroupid`, `logid` and `tenantid`.

- **JSON data:** when `data` is a JSON object it is stored as an object, so `data.<field>`
  queries reach it. Anything else is stored as text.
- **Disabled logs:** a disabled log drops what it receives.
- **Retention:** entries older than the log's `retentionDuration` are removed, and searches never
  return them, even from a log that has stopped receiving entries.

## Search

SearchLogs takes `timeStart`, `timeEnd`, `searchQuery` and `isReturnFieldInfo`, and pages with
`limit`, `page` and `opc-next-page`.

- **Result shape:** each result is `{"data": {"datetime": <epoch millis>, "logContent": <entry>}}`,
  the shape the OCI CLI's examples read, until a query projects it.
- **Field info:** `isReturnFieldInfo` adds `fields` with each leaf field and its type.

The supported subset of the [Logging query language](https://docs.oracle.com/iaas/Content/Logging/Reference/query_language_specification.htm):

- `search "compartment[/logGroupNameOrOcid[/logNameOrOcid]]"`, one or more scopes. The tenancy
  OCID searches every compartment; any other compartment is searched on its own.
- `where <expr>`, where the `where` keyword is optional. Expressions take:
  - comparisons `= != > >= < <=`, `and`, `or`, `not()` and arithmetic;
  - string literals in single quotes, with `*` as a wildcard in `=`;
  - the functions `contains_ci`, `contains_cs`, `upper`, `lower`, `concat`, `substr`, `isnull`,
    `isnotnull` and `rounddown(datetime, '15m')`.
  - A field such as `data.level` or `type` is read from the entry. `logContent = '*text*'`
    matches any value in the entry.
- `sort by <expr> [asc|desc], ...`, `top N by <expr>`, `dedup <expr>, ...`,
  `select <expr> [as name], ...` and `count`.
- `summarize count() | count(x) | sum | avg | min | max | first | last [as name], ... [by <expr> [as name], ...]`.
- Comments: `--` to the end of the line, and `/* ... */`, outside quoted values.
- An unknown function, or a function given the wrong number of arguments, is `400 InvalidParameter`
  when the query is parsed, so a saved search with one is rejected too. A computed value longer
  than 1,048,576 characters, as a chain of `concat()` can build, is `400 InvalidParameter`.

Unfiltered results come oldest first.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci logging log-group create $E --compartment-id "$TENANCY" --display-name app
# the new OCIDs are in the work requests: oci logging work-request get --work-request-id ...
oci logging log create $E --log-group-id "$GROUP" --display-name api --log-type CUSTOM
oci logging-ingestion put-logs $E --log-id "$LOG" --specversion 1.0 --log-entry-batches \
  "[{\"source\":\"api-1\",\"type\":\"app\",\"defaultlogentrytime\":\"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)\",
    \"entries\":[{\"id\":\"1\",\"data\":\"{\\\"level\\\":\\\"ERROR\\\"}\"}]}]"
oci logging-search search-logs $E --time-start 2026-01-01T00:00:00Z --time-end 2030-01-01T00:00:00Z \
  --search-query "search \"$TENANCY\" | where data.level = 'ERROR'"
```

## Notes and limitations

These are floci-oci's choices where Oracle's documentation is silent:

- `datetime` is epoch milliseconds.
- JSON `data` is parsed into an object.
- Unfiltered results come oldest first.
- `sort` and `top` order missing values first, then numbers by value, then text alphabetically.
- A compartment scope does not include child compartments.
- Logs keep at most 100,000 entries.

Not emulated:

- `extend`, query parameters (`$name`, `query.from`), `time_format`, and joins across logs.
- Unified agent configurations and `/v2/registry/services` (the service log catalog).
- Archiving to Object Storage.
- Service logs that floci-oci services write themselves: a SERVICE log records its
  configuration but receives no entries.
