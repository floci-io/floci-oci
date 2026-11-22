# Monitoring

OCI Monitoring, API version `20180401`. Custom metrics go in through PostMetricData and come
back out through SummarizeMetricsData with Monitoring Query Language (MQL). Alarms are
evaluated once a minute and send alarm messages to a Notifications topic or a stream. Served on
port 4599. Real OCI sends PostMetricData to the `telemetry-ingestion` host and everything else
to `telemetry`; floci-oci answers both on the same port.

## Supported operations

| Area | Operations |
|---|---|
| Metrics | PostMetricData, SummarizeMetricsData, ListMetrics |
| Alarms | CreateAlarm, GetAlarm, ListAlarms, UpdateAlarm, DeleteAlarm, ChangeAlarmCompartment, RemoveAlarmSuppression, ListAlarmsStatus, GetAlarmHistory, RetrieveDimensionStates, SummarizeAlarmSuppressionHistory |
| Alarm suppressions | CreateAlarmSuppression, GetAlarmSuppression, ListAlarmSuppressions, DeleteAlarmSuppression |

## Wire notes

- ListAlarms, ListAlarmsStatus, ListMetrics and SummarizeMetricsData return **bare arrays**;
  ListAlarmSuppressions, GetAlarmHistory, RetrieveDimensionStates and
  SummarizeAlarmSuppressionHistory return objects. Lists page with `limit`, `page` and
  `opc-next-page`.
- `compartmentIdInSubtree=true` is accepted only when `compartmentId` is the tenancy
  (`400 InvalidParameter` otherwise), as the SDK documents.
- Alarm and suppression OCIDs use `alarm` and `alarmsuppression`. Deleting either leaves a
  `DELETED` record that Get still returns and lists hide, which is the state Terraform waits for.

## Metrics

PostMetricData validates each metric object on its own and reports rejects in
`failedMetrics` with a `message`:

- The namespace starts with a letter and has only letters, digits and underscores. The
  reserved prefixes `oci_` and `oracle_` are rejected.
- The metric name and `resourceGroup` follow the SDK's character rules.
- Each metric has 1 to 20 dimensions, with no empty keys or values.
- Each data point's timestamp is less than two hours in the past and less than 10 minutes in
  the future.

With `batchAtomicity: ATOMIC`, one reject rejects the whole batch. Raw data points are kept for
14 days. Older points are dropped once a minute, and a stream left with no points is removed.

SummarizeMetricsData defaults to the last three hours and a `1m` resolution. The resolution
must not exceed the query interval. An aggregated data point stamped `t` covers
`(t - interval, t]`, and stamps step by the resolution. A query with a condition keeps only
the data points that meet it. Streams with no data in the range are left out. An omitted
`resourceGroup` matches only metrics posted without one, as the SDK documents. The time range is
capped from the current time by the resolution, as Oracle documents: 7 days below a 5 minute
resolution, 30 days below an hour, and 90 days otherwise.

ListMetrics matches `name` (with `*` wildcards), `namespace`, `resourceGroup` and
`dimensionFilters`. `groupBy` takes `namespace`, `name` and `resourceGroup`, and ignores
`dimensionFilters`, as the SDK documents.

## MQL

The supported grammar, from Oracle's [MQL reference](https://docs.oracle.com/iaas/Content/Monitoring/Reference/mql.htm):

- `Metric[interval]{dimensions}.grouping-function.statistic() condition`, for example
  `CpuUtilization[1m]{host =~ "web-*"}.groupBy(host).percentile(0.9) > 85`.
- Intervals `1m` to `60m`, `1h` to `24h`, and `1d`.
- Dimension filters with `=`, `!=` and the fuzzy `=~`, where `*` is a wildcard and `|` separates
  alternatives.
- `grouping()` and `groupBy(dim, ...)`.
- The statistics `mean()`, `avg()`, `count()`, `sum()`, `min()`, `max()`, `first()`, `last()`,
  `rate()`, `increment()`, `percentile(p)` and `absent([period])`. The default absence detection
  period is two hours, and `absent(20)` means 20 hours. The period must be from `1m` to `3d`.
  It counts from when the stream went absent, one interval after its last data point.
- The conditions `>`, `>=`, `<`, `<=`, `==`, `!=`, `in (a, b)` and `not in (a, b)`.
- Arithmetic with a number on either side, as in `100 - Cpu[1m].mean()`.
- `&&` and `||` between queries, applied left to right. `&&` keeps a data point only where the
  other query also has one for the same dimensions and timestamp; `||` keeps the data points of
  either. An alarm joins its queries minute by minute and then checks that the joined query held
  for the whole pending duration.
- Without a grouping function, streams with the same dimensions in different compartments stay
  separate series.
- A function given arguments it does not take, such as `mean(1)`, answers `400 InvalidParameter`.

Arithmetic between two metrics and nested queries answer `400 InvalidParameter`.

## Alarms

- **Defaults:** create fills in `resolution: 1m`, `pendingDuration: PT1M`, `messageFormat: RAW`,
  `ruleName: BASE`, `notificationVersion: 1.X`, `evaluationSlackDuration: PT3M` and the two
  boolean flags (`false`).
- **Query:** the query, and each override's query, needs a condition or `absent()`.
- **Compartments:** `metricCompartmentId`, like `compartmentId`, must be an active compartment
  (`400 RelatedResourceNotAuthorizedOrNotFound` otherwise). `metricCompartmentIdInSubtree: true` is
  accepted only when `metricCompartmentId` is the tenancy.
- **Durations:** `pendingDuration` must be PT1M to PT1H, `repeatNotificationDuration` PT1M to
  P30D, and `evaluationSlackDuration` PT3M to PT2H. The only accepted `resolution` is `1m`.
- **Destinations:** one Notifications topic and one stream at most. A destination that doesn't
  exist is `400 RelatedResourceNotAuthorizedOrNotFound` while its service is enabled.

Every alarm is evaluated once a minute
(`FLOCI_OCI_SERVICES_MONITORING_ALARM_EVALUATION_INTERVAL_SECONDS`):

- An evaluation at time `now` reads data up to `now - evaluationSlackDuration`.
- A metric stream fires when every one-minute evaluation within `pendingDuration` meets the
  condition, or for `absent()`, finds no data. It is OK again as soon as the latest minute
  doesn't.
- Overrides are evaluated in order before the base rule, and the first rule that fires for a
  stream sets its severity and `ruleName`.
- A disabled alarm is not evaluated.

Alarm messages follow Oracle's [alarm message format](https://docs.oracle.com/iaas/Content/Monitoring/alarm-message-format.htm):

- **Fields:** `dedupekey`, `title`, `body`, `type`, `severity`, `timestampEpochMillis`,
  `timestamp`, `alarmMetaData` and `version`.
- **Types:** `OK_TO_FIRING` and `FIRING_TO_OK`, plus `REPEAT` while the alarm keeps firing and
  `repeatNotificationDuration` is set.
- **Delivery:** a topic receives the message as JSON with the notification title, and a stream
  receives it as one message. `PRETTY_JSON` indents the JSON.
- **Per stream:** with `isNotificationsPerMetricDimensionEnabled`, each metric stream gets its
  own messages.

ListAlarmsStatus reports `OK`, `FIRING` or `SUSPENDED`. GetAlarmHistory lists state changes,
newest first. RetrieveDimensionStates lists each tracked stream.

## Suppressions

An alarm is suppressed while one of these is in its window:

- its `suppression` window;
- an `ACTIVE` alarm suppression of level `ALARM` that targets the alarm, its compartment, or
  the tenancy with `compartmentIdInSubtree`.

A suppressed alarm is not evaluated and reports `SUSPENDED`. A `DIMENSION` suppression, the
default level, which needs `dimensions`, silences only the streams whose dimensions include
the suppressed ones.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

oci monitoring metric-data post $E --metric-data "[{\"namespace\":\"my_app\",\"compartmentId\":\"$TENANCY\",
  \"name\":\"latency\",\"dimensions\":{\"host\":\"web-1\"},\"datapoints\":[{\"timestamp\":\"$NOW\",\"value\":120}]}]"
oci monitoring metric-data summarize-metrics-data $E --compartment-id "$TENANCY" \
  --namespace my_app --query-text 'latency[1m].max()'
```

## Notes and limitations

These are floci-oci's choices where Oracle's documentation is silent:

- The aggregation window `(t - interval, t]`.
- `rate()` and `increment()` are worked out inside one window.
- The system-generated `alarmSummary` text.
- The 14 day retention.
- Where the absence detection period starts: one interval after the stream's last data point.
- A dimension name in a filter is any name ingestion accepts (printable ASCII without spaces), ended by
  `=`, `!`, `~`, `,`, `{`, `}` or `"`, so a name containing one of those cannot be filtered.

Not emulated:

- `RESET` messages and the internal reset period.
- Dynamic variables in `body` and `notificationTitle`, which are sent as written.
- The `ONS_OPTIMIZED` layout, which is sent as `RAW`.
- Evaluation of recurrence `suppressionConditions`: a suppression with conditions applies
  across its whole window.
- The `serviceName` and `entityId` filters of ListAlarmsStatus.
- Service metrics: only custom metrics exist.
- Gzip-encoded PostMetricData bodies.
