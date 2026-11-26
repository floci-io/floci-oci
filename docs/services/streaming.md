# Streaming

OCI Streaming, API version `20180418`. Partitioned append-only logs with cursor-based
consumption, grouped in stream pools. Outside mock mode each pool runs a Redpanda broker that
holds its streams and serves Kafka clients.

!!! warning "Behaviour change: Streaming needs Docker by default"
    `streaming.mock` defaults to `false`, as for the other sidecar services, so a stream pool
    starts a Redpanda container and its streams become topics on it. Without a Docker daemon,
    set `FLOCI_OCI_SERVICES_STREAMING_MOCK=true`: streams then keep their messages in
    floci-oci's own storage, as before, and pools have no Kafka endpoint. Streams created
    before the switch keep their messages where they are.

## Supported operations

| Area | Operations |
|---|---|
| Stream pools | CreateStreamPool, GetStreamPool, ListStreamPools, UpdateStreamPool, DeleteStreamPool, ChangeStreamPoolCompartment |
| Streams | CreateStream, GetStream, ListStreams, UpdateStream, DeleteStream |
| Produce | PutMessages |
| Consume | CreateCursor, CreateGroupCursor, GetMessages |
| Groups | ConsumerCommit, ConsumerHeartbeat, GetGroup, UpdateGroup |
| Work requests | Get, List, errors, logs (`/20180418/workRequests`) |

## Wire notes

- **CreateStream is dual-mode**: it returns the full `Stream` body *and* an
  `opc-work-request-id` header. Terraform waits on the work request and reads the stream
  OCID from its `resources[]` (entityType containing `stream`, actionType `CREATED`).
- Lists are **bare JSON arrays**. `retentionInHours` is on `Stream` but **not** on
  `StreamSummary`, so it only appears on Get.
- `GetMessages` takes a **mandatory `cursor` query parameter**, returns a bare array and
  advances through the **`opc-next-cursor`** response header.
- Message `key` and `value` are base64; `Message.stream` carries the stream **name**,
  not its OCID.
- Commit and heartbeat are POSTs with the cursor in the query string and an empty body.

## Stream pools

- Every stream belongs to a pool. CreateStream with only a `streamPoolId` puts the stream in
  that pool and gives it the pool's compartment. An unknown pool is
  `400 RelatedResourceNotAuthorizedOrNotFound`, and so is a pool whose compartment has been
  deleted since (a `DefaultPool` does not keep its compartment alive), exactly as when that
  compartment is named in `compartmentId`.
- CreateStream with only a `compartmentId` puts the stream in that compartment's
  `DefaultPool`, created on first use and shared by every later pool-less stream there
  (*inferred* from the Streaming docs, which name the default pool but not its record).
- CreateStreamPool and UpdateStreamPool return the full `StreamPool` body with `etag` and
  `opc-work-request-id`. DeleteStreamPool (202) and ChangeStreamPoolCompartment (200) return
  `opc-work-request-id` only. The work request's resource has `entityType: "stream_pool"`,
  which is what Terraform's `oci_streaming_stream_pool` matches (it lowercases the entity
  type and looks for `stream_pool`).
- `kafkaSettings` and `customEncryptionKey` are always present. The defaults when a create
  leaves them out (`autoCreateTopicsEnable: false`, `logRetentionHours: 24`,
  `numPartitions: 1`) are *inferred*. `customEncryptionKey.keyState` is `NONE` without a key
  and `ACTIVE` with one (*inferred*); the key is stored and echoed, not used. `isPrivate` is
  always `false`.
- ListStreamPools is a bare JSON array and needs `compartmentId` (`400 MissingParameter`
  without it). It filters on `id`, `name` and `lifecycleState`. `sortBy=TIMECREATED`
  (the default) sorts newest first and `NAME` sorts A to Z, unless `sortOrder` says otherwise.
- Deleting a pool that still holds streams is `409 IncorrectState` (*inferred*).
  ChangeStreamPoolCompartment moves the pool only; its streams keep their compartment
  (*inferred*).
- A pool created with CreateStreamPool keeps its compartment from being deleted. A
  `DefaultPool` does not, since it was never asked for (*inferred*).
- Streams created before stream pools existed carry a pool OCID with no record. At startup
  floci-oci gives each such OCID a `DefaultPool` record in the stream's compartment; a
  stream that had only a pool OCID, and so no compartment, takes the tenancy's root
  compartment.

## Kafka endpoint

With `mock: false` (the default) every stream pool, the `DefaultPool` included, runs one
`redpandadata/redpanda` container:

- CreateStreamPool answers `CREATING` with an `IN_PROGRESS` work request. A background poller
  moves the pool to `ACTIVE`, fills `kafkaSettings.bootstrapServers` and finishes the work
  request `SUCCEEDED` once the broker answers. A broker that exits, or is not ready within
  `ready-timeout-seconds`, leaves the pool `FAILED` with `lifecycleStateDetails` and a `FAILED`
  work request. Terraform waits on the work request and then on `ACTIVE`, so both cover the
  start.
- CreateStream answers `CREATING` with an `IN_PROGRESS` work request too, and becomes `ACTIVE`
  once its pool is `ACTIVE` and the stream's topic exists. The topic is the stream's **name**,
  with the stream's partitions and `retention.ms` from `retentionInHours`. Two streams of one
  pool cannot share a name: the second one is `409 Conflict` (*inferred*: OCI accepts it and
  then fails Kafka authentication for the duplicates).
- The broker is the only copy of the messages. PutMessages produces through Redpanda's HTTP
  proxy and GetMessages reads with `rpk` in the container, so OCI offsets are Kafka offsets and
  a message written by either side is read by the other at the same offset. PutMessages reports
  the time of the produce as each entry's `timestamp`, since the proxy does not return the
  broker's (*inferred*); GetMessages returns the broker's record timestamps.
- PutMessages, GetMessages and cursors on a stream, or a pool, that is not `ACTIVE` are
  `409 IncorrectState` (*inferred*).
- `bootstrapServers` is the broker's published Kafka port: `localhost:<port>` (ports
  `19092..19191`) when floci-oci runs on the host, the container's name and `9092` when it runs
  in a container on a shared network, and the Docker daemon's host with
  `FLOCI_OCI_DOCKER_ENDPOINT_MODE=published`.
- **No authentication.** The listener is `PLAINTEXT` without SASL. OCI's own endpoint is
  `cell-1.streaming.<region>.oci.oraclecloud.com:9092` with `SASL_SSL`, mechanism `PLAIN`,
  username `<tenancy>/<user>/<streamPoolOCID>` and an auth token as the password
  (`local/oracle/oci-docs/streaming-kafka*.md`); floci-oci has no auth tokens yet, so clients
  connect without credentials.
- Consumer groups of Kafka clients and OCI group cursors are separate: group cursors keep their
  committed offsets in floci-oci.
- The data stays on a named volume per pool. A restart of floci-oci starts the brokers again
  (their pools are `UPDATING` until they answer); a deleted pool's volume is removed in memory
  storage mode or with `prune-volumes-on-delete`, and `POST /_floci-oci/state/reset` removes
  every broker and volume.

```bash
POOL=$(oci streaming admin stream-pool create $E --compartment-id "$TENANCY" \
  --name demo-pool --query data.id --raw-output)
oci streaming admin stream create $E --name orders --partitions 1 --stream-pool-id "$POOL"
BOOTSTRAP=$(oci streaming admin stream-pool get $E --stream-pool-id "$POOL" \
  --query 'data."kafka-settings"."bootstrap-servers"' --raw-output)

# Any Kafka client works; the topic is the stream name.
echo hello | kcat -b "$BOOTSTRAP" -t orders -P
kcat -b "$BOOTSTRAP" -t orders -C -e
```

## Partitioning and cursors

- Messages with a `key` hash to a stable partition; keyless messages round-robin.
- Cursor types: `TRIM_HORIZON`, `LATEST`, `AT_OFFSET`, `AFTER_OFFSET`, `AT_TIME`.
  Group cursors support only `TRIM_HORIZON`, `LATEST`, `AT_TIME`.
- Cursors are opaque (base64) and bound to their stream — using one against another
  stream is a 400.
- Committing a group cursor persists per-partition offsets; a fresh group cursor for the
  same group resumes from them.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci streaming admin stream create $E --compartment-id "$TENANCY" \
  --name demo-stream --partitions 2

oci streaming admin stream-pool create $E --compartment-id "$TENANCY" --name demo-pool
```

## Notes & limitations

Private endpoints, customer-managed KMS keys, Kafka SASL authentication,
`autoCreateTopicsEnable`, connect harnesses and the archiver are not implemented. Topics
created by Kafka clients are not streams.
