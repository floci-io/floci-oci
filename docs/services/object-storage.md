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
- Object versioning, retention rules, lifecycle policies, replication and the
  Amazon S3 Compatibility API are not implemented yet.
