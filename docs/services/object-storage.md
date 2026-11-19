# Object Storage

OCI Object Storage — unversioned paths under `/n/{namespace}/b/{bucket}/o/{object}`.

## Supported operations

| Area | Operations |
|---|---|
| Namespace | GetNamespace (`/n`), GetNamespaceMetadata |
| Buckets | Create, Get, Head, List, Update (including `versioning`), Delete (must hold no objects or object versions) |
| Objects | Put (Content-MD5 check, `opc-meta-*`, conditional headers), Get (Range → 206), Head, Delete; `versionId` on Get, Head and Delete |
| Listing | ListObjects with `prefix`/`start`/`end`/`delimiter`/`limit`/`fields`, `nextStartWith` truncation; ListObjectVersions (`/objectversions`), paged with `opc-next-page` |
| Actions | RenameObject; CopyObject (`sourceVersionId`, async, work request, terminal status `COMPLETED`); BatchDeleteObjects (per-object success/failure results, optional per-entry `ifMatch`, `isSkipDeletedResult`) |
| Multipart | CreateMultipartUpload, UploadPart, Commit, Abort, List |
| Pre-authenticated requests | Create, Get, List, Delete; anonymous data path via `/p/{token}/…` |
| Replication | CreateReplicationPolicy, GetReplicationPolicy, ListReplicationPolicies, DeleteReplicationPolicy, ListReplicationSources, MakeBucketWritable |
| Work requests | Get, List, errors, logs (unversioned at `/workRequests`) |
| Amazon S3 Compatibility API | See [below](#amazon-s3-compatibility-api) |

## Quickstart

```bash
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000
E="--endpoint http://localhost:4599"

oci os ns get $E
oci os bucket create $E --compartment-id "$TENANCY" --namespace floci-local --name demo
echo "hello" > /tmp/hello.txt
oci os object put $E --namespace floci-local --bucket-name demo \
  --file /tmp/hello.txt --name hello.txt
oci os object get $E --namespace floci-local --bucket-name demo \
  --name hello.txt --file -
```

## Notes & limitations

- Each tenancy has its own namespace, the same in every region, as on real OCI. The default
  tenancy (unsigned requests, or `default-tenancy-id`) uses `floci-oci.default-namespace`
  (default `floci-local`); any other signing tenancy gets a stable 12 character namespace
  derived from its OCID. `GetNamespace` returns the caller's, and with `?compartmentId=<tenancy
  OCID>` that tenancy's (a compartment OCID resolves to the caller's namespace).
- A namespace in the path that is not the caller's is answered with `404 NamespaceNotFound`,
  as on real OCI. Ask `GetNamespace` for the namespace instead of hard-coding one.
- Pre-authenticated request URLs work for every tenancy: the token is looked up across tenancies
  in the request's region, must match the namespace in the URL, and acts as the tenancy that
  created it.
- `CopyObject` honours `destinationRegion` (required, as on real OCI): the destination bucket
  must exist in that region and the copy lands there. The copy completes immediately; its work
  request (`COMPLETED`) is read from the source region.
- `Content-MD5` is verified on upload; `opc-content-md5` is returned.
- Buckets emit `createbucket`, `updatebucket` and `deletebucket` events, and objects emit
  `createobject`, `updateobject` and `deleteobject` on buckets created or updated with
  `objectEventsEnabled: true`. Rules in [Events](events.md) deliver them to Functions and Streaming.
- `CommitMultipartUpload` returns `opc-multipart-md5`: the Base64 MD5 of the committed parts' binary
  MD5 digests in part-number order, followed by `-` and the part count. `GetObject` and `HeadObject`
  return the same value for multipart objects, in place of `content-md5`.
- Deleting a bucket that still holds objects, previous versions or delete markers answers
  `409 BucketNotEmpty`.
- Retention rules and lifecycle policies are not implemented yet.

## Versioning

A bucket's `versioning` is `Disabled` by default. Create a bucket with `"versioning": "Enabled"`, or
update it to `Enabled` or `Suspended`. As on real OCI:

- Versioning can be suspended but never disabled again: `"versioning": "Disabled"` on an update is
  `400 InvalidParameter`.
- Every object has a version ID, on unversioned buckets too. PutObject, GetObject, HeadObject,
  CommitMultipartUpload and RenameObject return it in the `version-id` header.
- On an `Enabled` bucket an upload keeps the object it replaces as a previous version, and a delete
  without `versionId` keeps the object and adds a delete marker. That DeleteObject answers
  `version-id` (the marker's) and `is-delete-marker: true`.
- A delete with `versionId` removes that version, object or delete marker, for good, and answers
  `last-modified` only. Deleting the delete marker brings the object back.
- A `Suspended` bucket uploads and deletes like a `Disabled` one and keeps its earlier versions.
- `GetObject` with `versionId` reads a previous version. `CopyObject` with `sourceVersionId` copies
  one, and the copy becomes the latest version at the destination.
- `ListObjectVersions` returns `{"items": [...], "prefixes": [...]}`, by name and then newest first.
  Every item has `name`, `timeModified`, `versionId` and `isDeleteMarker`; `fields` adds `size`,
  `etag`, `md5`, `timeCreated` and `storageTier` to object versions.
- Only the latest version is replicated, and a replication destination cannot be versioned.

floci-oci's own choices, where no OCI source states the behaviour:

- When the latest version is deleted by `versionId`, or deleted on a `Suspended` bucket, the newest
  previous version becomes the latest unless it is a delete marker.
- GetObject and HeadObject on a delete marker's `versionId` answer `404 ObjectNotFound`, and a delete
  without `versionId` on an object whose latest version is a marker does too.
- RenameObject deletes the source the way DeleteObject does, so on an `Enabled` bucket the source
  keeps its versions and gets a delete marker.
- Enabling versioning on a replication destination answers `409 Conflict`, and creating a policy
  towards a versioned bucket answers `409 ReplicationPolicyConflict`.
- `opc-next-page` names the last entry of the page, not an offset, so deleting a page of versions
  before asking for the next one skips nothing.

## Replication

A replication policy on a source bucket copies its writes into a destination bucket, in another
region or the same one. As on real OCI:

- A source has at most one policy (a second one is `409 ReplicationPolicyConflict`), a destination
  has at most one source, and a destination cannot also be a source.
- The destination bucket must exist first; policy creation does not create it.
- Only changes made after the policy exists are replicated: puts, multipart commits, renames,
  copies into the source, deletes and batch deletes. A replica keeps the source's name, data,
  metadata, ETag and MD5.
- The source reads `replicationEnabled: true` and cannot be deleted while it has a policy
  (`403 BucketReplicationEnabled`). The destination reads `isReadOnly: true`.
- The policy id is a UUID, and `timeLastSync` is `null` until the first change is replicated.
- `MakeBucketWritable` on the destination stops replication: the bucket becomes writable, and the
  source's policy moves to `CLIENT_ERROR` until it is deleted.
- Deleting the policy makes both buckets ordinary again.

floci-oci's own choices, where no OCI source states the behaviour:

- Replication is synchronous: a change reaches the destination before the source request answers.
  Real OCI replicates asynchronously with an unstated lag; a zero lag is one valid case of it.
- A write or delete on a read-only destination (including through a pre-authenticated request),
  and deleting a read-only destination bucket, answer `409 Conflict`.
- A destination that already has a source, or is itself a source, answers
  `409 ReplicationPolicyConflict`.
- `MakeBucketWritable` on a bucket that is not a destination answers `409 Conflict`.

## Amazon S3 Compatibility API

S3 clients reach the same buckets and objects on OCI's S3 host,
`<namespace>.compat.objectstorage.<region>.<domain>`, path style (`/<bucket>/<object>`).
Locally that is `http://<namespace>.compat.objectstorage.<region>.localhost.floci.io:4599`,
which resolves to 127.0.0.1. A request is recognised by that Host alone; every other request
reaches the native API as before.

| Area | Operations |
|---|---|
| Service | ListBuckets (GetService) |
| Buckets | PutBucket, HeadBucket, DeleteBucket, GetBucketLocation, GetBucketVersioning (always unversioned), Get/Put/DeleteBucketTagging (the bucket's freeform tags) |
| Objects | PutObject, GetObject (Range), HeadObject, DeleteObject, DeleteObjects, CopyObject (`x-amz-copy-source`, `x-amz-metadata-directive`) |
| Listing | ListObjects (v1, `marker`) and ListObjectsV2 (`continuation-token`, `start-after`), with `prefix`, `delimiter`, `max-keys` |
| Multipart | CreateMultipartUpload, UploadPart, CompleteMultipartUpload, AbortMultipartUpload, ListParts, ListMultipartUploads |

- Requests are signed with SigV4 and a Customer Secret Key (Identity
  `/20160918/users/{userId}/customerSecretKeys`). The access key decides the tenancy and user;
  an access key no key owns reaches the default tenancy. Signatures are never checked.
- Buckets created over S3 land in the namespace's `defaultS3CompartmentId` (the root
  compartment), as on real OCI.
- ETags are the hex MD5 S3 clients expect; a multipart object's is the hex MD5 of the part
  MD5s followed by `-<parts>`. `x-amz-meta-*` maps to `opc-meta-*`.
- `aws-chunked` uploads (`STREAMING-*` payloads, with or without trailing checksums) are
  decoded; chunk signatures and trailing checksums are not checked.
- Errors are S3 XML (`NoSuchBucket`, `NoSuchKey`, `BucketNotEmpty`, `NoSuchUpload`, ...).
- PutObject honours `If-None-Match: *` and `If-Match`, compared with the hex MD5 ETag
  (`412 PreconditionFailed`). CompleteMultipartUpload needs every part's ETag and the parts in
  ascending order (`InvalidPart`, `InvalidPartOrder`).
- GetObject and HeadObject honour `If-Match` (`412 PreconditionFailed`) and `If-None-Match`
  (`304 Not Modified`). CopyObject honours `x-amz-copy-source-if-match` and
  `x-amz-copy-source-if-none-match` against the source's ETag.
- ListObjects and ListObjectsV2 honour `encoding-type=url`. In ListObjectsV2 with a delimiter, a
  `start-after` inside a folder skips that folder's common prefix.
- A bucket subresource floci-oci does not serve (`?policy`, `?acl`, `?cors`, ...) answers
  `501 NotImplemented`; it never runs the plain bucket operation.
- SigV4 credentials only count on the S3 host. Deleting an Identity user deletes its Customer
  Secret Keys, and turning Object Storage off turns this API off too: an S3 host then answers 503
  and never reaches a native route. Customer Secret Key writes are accepted only in the home region.
- Oracle's supported list does not name CopyObject or ListObjectsV2; floci-oci serves them
  because boto3, the AWS CLI and rclone use them. SSE-C and KMS headers, ACLs, policies and
  the virtual-host `vhcompat` endpoint are not implemented.

```python
import boto3
from botocore.config import Config

s3 = boto3.client("s3", region_name="us-ashburn-1",
                  endpoint_url="http://floci-local.compat.objectstorage.us-ashburn-1.localhost.floci.io:4599",
                  aws_access_key_id="<customer secret key id>", aws_secret_access_key="<key>",
                  config=Config(s3={"addressing_style": "path"}))
s3.put_object(Bucket="demo", Key="hello.txt", Body=b"hello")
```
