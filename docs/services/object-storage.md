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
- Any namespace in the path is accepted by default. Set
  `FLOCI_OCI_SERVICES_OBJECTSTORAGE_STRICT_NAMESPACE=true` to answer a namespace that is not
  the caller's with `404 NamespaceNotFound`, as real OCI does.
- `Content-MD5` is verified on upload; `opc-content-md5` is returned.
- Object versioning, retention rules, lifecycle policies, replication and the
  Amazon S3 Compatibility API are not implemented yet.
