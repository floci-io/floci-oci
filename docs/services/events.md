# Events

OCI Events, API version `20181201`. Rules match the events floci-oci services emit and
deliver them to Functions and Streaming. Served on port 4599 beside the Functions API,
which shares the same version prefix.

## Supported operations

| Area | Operations |
|---|---|
| Rules | CreateRule, GetRule, ListRules, UpdateRule, DeleteRule, ChangeRuleCompartment |

## Wire notes

- Every operation is synchronous. Create, get and update return the rule with an `etag`;
  delete returns `204`. ListRules returns a **bare array** of RuleSummary (no `actions`)
  with `opc-next-page`; `sortBy` is `TIME_CREATED`, `ID` or `DISPLAY_NAME`.
- A rule is `ACTIVE` at once, or `INACTIVE` when `isEnabled` is false. Each action carries
  an `id`, `lifecycleState` `ACTIVE` and an empty `lifecycleMessage`.
- `condition` is stored exactly as sent, so Terraform reads back the string it wrote.
  It must parse as a JSON object (`400 InvalidParameter` otherwise).
- UpdateRule with `actions` replaces the whole list.
- An OSS action needs a `streamId`. A `functionId` or `streamId` that does not exist is
  `400 RelatedResourceNotAuthorizedOrNotFound` while that service is enabled.
- Rule OCIDs use the `eventrule` resource type. No local Oracle source shows a real rule
  OCID, so treat the type as unconfirmed.

## Matching

Conditions follow Oracle's filter rules:

- `{}` matches every event.
- Every field the condition names must exist in the event with the same nesting, and must
  match. Fields the condition does not name are ignored.
- An array matches when any of its values matches.
- `*` is a wildcard anywhere in a string value, and a lone `*` matches any value except
  null. The period has no special meaning.

A rule receives events from its own compartment and every child compartment.

## Events emitted

| Source | Event types | When |
|---|---|---|
| Object Storage buckets | `com.oraclecloud.objectstorage.createbucket`, `updatebucket`, `deletebucket` | Always |
| Object Storage objects | `com.oraclecloud.objectstorage.createobject`, `updateobject`, `deleteobject` | Only on buckets with `objectEventsEnabled: true` |

Object events fire on PutObject, CommitMultipartUpload, CopyObject (when the destination is
in the same region), RenameObject (a delete for the old name and a create or update for the
new one), DeleteObject and BatchDeleteObjects.

The payload is the CloudEvents 0.1 envelope from Oracle's examples:

```json
{
  "cloudEventsVersion": "0.1",
  "eventID": "<uuid>",
  "eventType": "com.oraclecloud.objectstorage.createobject",
  "source": "objectstorage",
  "eventTypeVersion": "2.0",
  "eventTime": "2026-11-18T13:37:11Z",
  "contentType": "application/json",
  "extensions": { "compartmentId": "ocid1.compartment..." },
  "data": {
    "compartmentId": "ocid1.compartment...",
    "compartmentName": "apps",
    "resourceName": "uploads/a.txt",
    "resourceId": "",
    "availabilityDomain": "all",
    "additionalDetails": {
      "eTag": "...", "namespace": "floci-local", "archivalState": null,
      "bucketName": "uploads", "bucketId": "ocid1.bucket..."
    }
  }
}
```

## Delivery

| Action | Delivery |
|---|---|
| `FAAS` | The function is invoked with the envelope as its JSON body (a synthetic result in Functions mock mode) |
| `OSS` | The envelope is put on the stream as one message, base64-encoded in `value` |
| `ONS` | Stored and validated, not delivered: Notifications is not emulated yet |

Delivery runs in the background after the request that caused the event has returned. A
failed delivery is logged and never fails that request.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci os bucket create $E --compartment-id "$TENANCY" --name uploads --object-events-enabled true
oci events rule create $E --compartment-id "$TENANCY" --display-name on-upload --is-enabled true \
  --condition '{"eventType":"com.oraclecloud.objectstorage.createobject"}' \
  --actions '{"actions":[{"actionType":"OSS","isEnabled":true,"streamId":"<streamId>"}]}'
```

## Notes and limitations

Only Object Storage emits events today. Delivery has no retries and no dead-letter
handling.
