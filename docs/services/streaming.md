# Streaming

OCI Streaming, API version `20180418`. Kafka-like partitioned append-only logs with
cursor-based consumption.

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
  `400 RelatedResourceNotAuthorizedOrNotFound`.
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

Private endpoints, customer-managed KMS keys, connect harnesses and the archiver are not
implemented.
