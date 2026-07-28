# Queue

OCI Queue — API version `20210201`. Control plane and data plane are both served on
port 4599; the queue's `messagesEndpoint` points back at the emulator.

## Supported operations

| Area | Operations |
|---|---|
| Queues | CreateQueue, GetQueue, ListQueues, UpdateQueue, DeleteQueue, ChangeCompartment, PurgeQueue |
| Messages | PutMessages, GetMessages, DeleteMessage(s), UpdateMessage(s) |
| Observability | GetStats, ListChannels |
| Work requests | Get, List, errors, logs (`/20210201/workRequests`) |

## Wire notes

- **Every control-plane mutation is work-request driven**: `202` with an
  `opc-work-request-id` header and **no body**. Poll `GetWorkRequest` — the emulator
  records the queue OCID in `resources[]` and sets `timeFinished` immediately, which is
  what Terraform's retry predicate requires.
- **Lists are wrapped** — `{"items":[…]}` — unlike Identity's bare arrays.
- `sortBy` values are camelCase (`timeCreated`, `displayName`), not the SCREAMING_CASE
  KMS uses.
- `retentionInSeconds` is set at create time and is **not updatable**.

## Message semantics

- `visibilityInSeconds` defaults to the queue's setting; **`0` is a peek** — the message
  stays visible but its `deliveryCount` still increments.
- `timeoutInSeconds > 0` long-polls until a message arrives (capped at 10s in the
  emulator); `0` short-polls.
- With `deadLetterQueueDeliveryCount > 0`, over-delivered messages move to the DLQ and
  show up under `dlq` in `GetStats`.
- Messages expire after `retentionInSeconds`.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci queue queue create $E --compartment-id "$TENANCY" --display-name demo
oci queue messages put-messages $E --queue-id <queueId> \
  --messages '[{"content":"hello queue"}]'
oci queue messages get-messages $E --queue-id <queueId>
```

## Notes & limitations

Consumer groups, channel consumption limits and per-channel purge filters beyond
`channelIds` are not implemented.
