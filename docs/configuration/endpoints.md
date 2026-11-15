# Emulator Endpoints

Besides the OCI APIs, floci-oci serves a few endpoints of its own on the main port (`4599`). They are not
part of any OCI contract: use them for health probes, init-hook waits, test isolation and TLS setup. Every
Floci emulator (AWS, Azure, GCP, OCI) serves the same endpoints under the same `/_floci` prefix.

They never need a request signature, even with `FLOCI_OCI_AUTH_REQUIRE_SIGNATURE=true`, and are never
refused for a region the tenancy has not subscribed to.

| Method | Path | Response |
|---|---|---|
| `GET` | `/_floci/health` | `200` with the [health body](#health) |
| `GET` | `/_floci/info` | `200` with the [emulator manifest](#emulator-manifest) |
| `GET` | `/_floci/init` | `200` with the [init hook state](#init-hooks) |
| `POST` | `/_floci/state/reset` | `200` `{"status": "OK"}`, after clearing the state of every service |
| `POST` | `/_floci/state/nuke` | Same as `/_floci/state/reset` |
| `GET` | `/_floci/tls-cert` | `200` with the TLS certificate (`application/x-pem-file`), or `404` with a [JSON explanation](#tls-certificate) |
| any | `/_floci/<anything else>` | `404` `{"error": "NotFound", "path": "<path>"}` |

## Legacy paths

These stay served, with the same bodies, and are never removed:

- `/_floci-oci/*`: every endpoint above, plus `/_floci-oci/diagnose` and `/_floci-oci/config` (both `{}`).
- `/health`: the same body as `/_floci/health`.

The OKE token webhook stays at `/_floci-oci/oke/token-webhook/{tenancyId}/{clusterId}`, because the
kubeconfigs floci-oci generates point to it.

## Health

```json
{
  "status": "UP",
  "cloud": "oci",
  "services": {"identity": "running", "objectstorage": "running", "queue": "available"},
  "edition": "community",
  "original_edition": "floci-always-free",
  "version": "0.6.0"
}
```

| Field | Meaning |
|---|---|
| `status` | Always `UP` with a `200`. Another Floci emulator may answer `DOWN` with a `503` and a `problems` map |
| `cloud` | Always `oci` |
| `services` | Every service, `running` when enabled and `available` when disabled |
| `version` | The floci-oci release the image was built from (`FLOCI_OCI_VERSION`), or `dev` for a local build |

The image's `HEALTHCHECK` probes `/_floci/health`.

## Emulator manifest

`GET /_floci/info` describes the running emulator, following the Floci Console Contract v2 manifest. A reader
must ignore keys it does not know.

```json
{
  "manifest_version": 1,
  "console_contract": 2,
  "cloud": "oci",
  "emulator": "floci-oci",
  "version": "0.6.0",
  "edition": "community",
  "original_edition": "floci-always-free",
  "endpoint": "http://localhost:4599",
  "health_url": "/_floci/health",
  "tls": {"enabled": false},
  "services": {"identity": "enabled", "queue": "disabled"},
  "defaults": {
    "tenancy_id": "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000",
    "compartment_id": "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000",
    "region": "us-ashburn-1",
    "namespace": "floci-local"
  },
  "ui": {"enabled": false}
}
```

`defaults` holds the tenancy used for unsigned requests, its root compartment (the tenancy itself), the
default region and the Object Storage namespace. `version`, `edition` and `original_edition` are the keys
`/_floci-oci/info` returned before the manifest; they stay.

## Init hooks

`GET /_floci/init` reports the [initialization hooks](initialization-hooks.md). `completed.ready` turns true
once the ready hooks have run, which makes it the signal to wait on when a test depends on them:

```json
{
  "completed": {"boot": true, "start": true, "ready": true, "shutdown": false},
  "scripts": {"boot": [], "start": [], "ready": [{"script": "/etc/floci-oci/init/ready.d/seed.sh", "state": "successful", "return_code": 0}], "shutdown": []}
}
```

## TLS certificate

With `FLOCI_OCI_TLS_ENABLED=true`, `GET /_floci/tls-cert` returns the certificate the HTTPS endpoint serves:
the generated self-signed certificate, or the certificates in `FLOCI_OCI_TLS_CERT_PATH`. A private key in that
file is never returned. Trust it once and every client validates:

```bash
curl -s http://localhost:4599/_floci/tls-cert -o floci-oci.pem
```

When no certificate is available it answers `404`:

```json
{"tlsEnabled": false, "error": "TLS is not enabled", "message": "floci-oci is serving plain HTTP only. ..."}
```
