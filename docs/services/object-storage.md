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
- `CommitMultipartUpload` returns `opc-multipart-md5`: the Base64 MD5 of the committed parts' binary
  MD5 digests in part-number order, followed by `-` and the part count. `GetObject` and `HeadObject`
  return the same value for multipart objects, in place of `content-md5`.
- Object versioning, retention rules, lifecycle policies and replication are not implemented
  yet.

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
- SigV4 credentials only count on the S3 host. Deleting an Identity user deletes its Customer
  Secret Keys, and turning Object Storage off turns this API off too.
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
