# Object Storage

OCI Object Storage — unversioned paths under `/n/{namespace}/b/{bucket}/o/{object}`.

## Supported operations

| Area | Operations |
|---|---|
| Namespace | GetNamespace (`/n`), GetNamespaceMetadata |
| Buckets | Create, Get, Head, List, Update, Delete (must be empty) |
| Objects | Put (Content-MD5 check, `opc-meta-*`, conditional headers), Get (Range → 206), Head, Delete |
| Listing | ListObjects with `prefix`/`start`/`end`/`delimiter`/`limit`/`fields`, `nextStartWith` truncation |
| Actions | RenameObject; CopyObject (async, work request, terminal status `COMPLETED`); BatchDeleteObjects (per-object success/failure results, optional per-entry `ifMatch`, `isSkipDeletedResult`) |
| Multipart | CreateMultipartUpload, UploadPart, Commit, Abort, List |
| Pre-authenticated requests | Create, Get, List, Delete; anonymous data path via `/p/{token}/…` |
| Replication | CreateReplicationPolicy, GetReplicationPolicy, ListReplicationPolicies, DeleteReplicationPolicy, ListReplicationSources, MakeBucketWritable |
| Work requests | Get, List, errors, logs (unversioned at `/workRequests`) |

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
- `CommitMultipartUpload` returns `opc-multipart-md5`: the Base64 MD5 of the committed parts' binary
  MD5 digests in part-number order, followed by `-` and the part count. `GetObject` and `HeadObject`
  return the same value for multipart objects, in place of `content-md5`.
- Object versioning, retention rules, lifecycle policies and the Amazon S3 Compatibility API are
  not implemented yet.

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
