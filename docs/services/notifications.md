# Notifications

OCI Notifications (ONS), API version `20181201`. Topics fan published messages out to their
subscriptions: HTTPS endpoints get a real POST, function subscriptions invoke the function,
and email, Slack, PagerDuty and SMS messages are recorded in an inspection endpoint instead
of being sent. Events rules with an `ONS` action publish to a topic. Served on port 4599
beside the Functions and Events APIs, which share the same version prefix.

## Supported operations

| Area | Operations |
|---|---|
| Topics | CreateTopic, GetTopic, ListTopics, UpdateTopic, DeleteTopic, ChangeTopicCompartment |
| Subscriptions | CreateSubscription, GetSubscription, ListSubscriptions, UpdateSubscription, DeleteSubscription, ChangeSubscriptionCompartment, GetConfirmSubscription, GetUnsubscription, ResendSubscriptionConfirmation |
| Messages | PublishMessage |

## Wire notes

- Every operation is synchronous. Create, get and update return the resource with an `etag`
  and honour `if-match`; delete returns `204`, and the deleted resource then answers
  `404 NotAuthorizedOrNotFound`. ListTopics and ListSubscriptions return **bare arrays** with
  `opc-next-page`. ListTopics sorts by `TIMECREATED` (the default, newest first) or
  `LIFECYCLESTATE`.
- A topic is `ACTIVE` at once and carries a six-character `shortTopicId`. Its name must be
  unique (`409 Conflict`). `apiEndpoint` is the emulator's base URL. Deleting a topic also
  deletes its subscriptions.
- A subscription must be created in its topic's compartment (`400 InvalidParameter`
  otherwise), and an unknown `topicId` is `400 RelatedResourceNotAuthorizedOrNotFound`.
  `protocol` is one of `CUSTOM_HTTPS`, `EMAIL`, `HTTPS`, `ORACLE_FUNCTIONS`, `PAGERDUTY`,
  `SLACK` and `SMS`.
- Endpoints are checked per protocol: URL protocols need an `http` or `https` URL without
  query parameters, `EMAIL` an address, `SMS` an E.164 number, and `ORACLE_FUNCTIONS` an
  existing function while Functions is enabled. A URL that names the instance metadata
  address is refused.
- GetSubscription returns the delivery policy as a JSON **string** in `deliverPolicy`, and
  ListSubscriptions returns it as an object in `deliveryPolicy`, as the SDK models do. The
  default is `{"backoffRetryPolicy":{"maxRetryDuration":7200000,"policyType":"EXPONENTIAL"}}`.
  UpdateSubscription answers with UpdateSubscriptionDetails (`deliveryPolicy`, tags), not the
  subscription.
- GetUnsubscription answers with a bare JSON string.
- PublishMessage takes `body` (required, at most 64 KB) and `title`, and returns
  `messageId` and `timeStamp`. With the `messageType: JSON` header, `body` must be a JSON
  object with a `default` key. Email subscriptions receive its `Email` value when present,
  and every other subscription receives `default`.
- Subscription OCIDs use `onssubscription` and topic OCIDs `onstopic`.

## Confirmation

A function subscription is `ACTIVE` at once. Every other subscription is `PENDING` and
receives a confirmation message carrying a confirmation URL. Opening that URL (an unsigned
`GET .../subscriptions/{id}/confirmation?token=...&protocol=...`) makes it `ACTIVE`. Messages
go only to `ACTIVE` subscriptions.

- HTTPS endpoints receive the confirmation as a POST with `Content-Type: application/json`,
  a `{"ConfirmationURL": "..."}` body, and the `X-OCI-NS-MessageType: SubscriptionConfirmation`,
  `X-OCI-NS-ConfirmationURL` and `X-OCI-NS-SubscriptionId` headers.
- For email, Slack, PagerDuty and SMS, read the confirmation URL from the inspection
  endpoint below.

The confirmation response carries an `unsubscribeUrl`. Opening it deletes the subscription.

## Delivery

| Protocol | Delivery |
|---|---|
| `CUSTOM_HTTPS`, `HTTPS` | A POST to the endpoint, with Basic authentication taken from credentials in the URL |
| `ORACLE_FUNCTIONS` | The function is invoked with the message as its body (a synthetic result in Functions mock mode); skipped while Functions is disabled |
| `EMAIL`, `SLACK`, `PAGERDUTY`, `SMS` | Recorded only; nothing leaves the emulator |

HTTPS deliveries carry `Content-Type`, `X-OCI-NS-MessageId`, `X-OCI-NS-TopicOcid`,
`X-OCI-NS-TopicName`, `X-OCI-NS-MessageType` and `X-OCI-NS-UnsubscribeURL`. A published
message is sent as `text/plain; charset=UTF-8`, and an Events envelope as
`application/json`. Connect and read timeouts are both 5 seconds.

A delivery that fails (an error, a timeout, or a status outside 2xx) is retried at once,
then after 1, 2, 4, 8, 16 and 32 minutes, repeating every 32 minutes, until the
subscription's `maxRetryDuration` runs out. The message is then discarded.

## Inspection endpoint

`GET /_floci-oci/notifications/messages` (also under `/_floci`) lists every message sent to
a subscription endpoint, oldest first, and keeps the last 1000. It filters on `topicId`,
`subscriptionId` and `protocol`. `DELETE` clears the list. This is a floci-oci endpoint, not an
OCI API.

```json
{
  "messages": [
    {
      "messageId": "...", "messageType": "SubscriptionConfirmation",
      "topicId": "ocid1.onstopic...", "topicName": "alerts",
      "subscriptionId": "ocid1.onssubscription...", "protocol": "EMAIL",
      "endpoint": "ops@example.com", "title": null, "body": "{\"ConfirmationURL\":\"...\"}",
      "contentType": "application/json", "confirmationUrl": "http://localhost:4599/20181201/...",
      "unsubscribeUrl": "...", "timestamp": "2026-11-19T10:00:00Z",
      "status": "CAPTURED", "attempts": 1, "lastError": null
    }
  ]
}
```

`status` is `CAPTURED` for recorded protocols, and `DELIVERED`, `RETRYING`, `FAILED` or
`SKIPPED` for the others.

## Events integration

An Events rule action with `actionType: ONS` publishes the matching event's CloudEvents
envelope to `topicId`. An unknown topic is `400 RelatedResourceNotAuthorizedOrNotFound`
while Notifications is enabled. While it is disabled, ONS actions are stored but not
delivered.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

TOPIC=$(oci ons topic create $E --compartment-id "$TENANCY" --name alerts --query data.\"topic-id\" --raw-output)
oci ons subscription create $E --compartment-id "$TENANCY" --topic-id "$TOPIC" \
  --protocol EMAIL --subscription-endpoint ops@example.com
curl -s http://localhost:4599/_floci-oci/notifications/messages   # open the confirmationUrl
oci ons message publish $E --topic-id "$TOPIC" --body "disk full" --title Alert
```

## Notes and limitations

These are floci-oci's choices where Oracle's documentation is silent:

- `X-OCI-NS-MessageType` is `Notification` on published messages.
- The confirmation and unsubscribe URLs point at GetConfirmSubscription and
  GetUnsubscription.
- The confirmation `message` text.
- Topic names are unique within a tenancy and region.

Messages are not signed (no `X-OCI-NS-Signature` headers), confirmation URLs do not expire,
the per-topic publish rate limit is not enforced, and Notifications emits no Events of its own.
