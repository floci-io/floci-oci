# Streaming

OCI Streaming — API version `20180418`. Kafka-like partitioned append-only logs with
cursor-based consumption.

## Supported operations

| Area | Operations |
|---|---|
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
```

## Notes & limitations

Stream pools are synthesized (a pool OCID is minted per stream) and their Kafka
connection settings, connect harnesses and the archiver are not implemented.
