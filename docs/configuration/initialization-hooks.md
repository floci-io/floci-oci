# Initialization Hooks

floci-oci runs shell scripts from lifecycle-phase directories, mirroring the other Floci emulators.

| Phase | Directory | When |
|---|---|---|
| `boot` | `/etc/floci-oci/init/boot.d` | Before services initialize — OCI APIs are not available yet |
| `start` | `/etc/floci-oci/init/start.d` | After the HTTP server is listening |
| `ready` | `/etc/floci-oci/init/ready.d` | After start hooks complete |
| `shutdown` | `/etc/floci-oci/init/stop.d` (or `shutdown.d`) | On graceful shutdown |

Scripts run in lexicographic order. A failing `start`/`ready` script aborts startup.

```yaml
# docker-compose.yml
services:
  floci-oci:
    volumes:
      - ./init-scripts:/etc/floci-oci/init/ready.d
```

```bash
#!/bin/sh
# init-scripts/10-create-bucket.sh
oci os bucket create --endpoint http://localhost:4599 \
  --compartment-id "$FLOCI_OCI_DEFAULT_TENANCY_ID" \
  --namespace floci-local --name seeded-bucket
```

Hook state is inspectable at `GET /_floci-oci/init`.

## Settings

| Property | Default | Description |
|---|---|---|
| `floci-oci.init-hooks.shell-executable` | `/bin/sh` | Interpreter for hook scripts |
| `floci-oci.init-hooks.timeout-seconds` | `30` | Per-script timeout |
| `floci-oci.init-hooks.shutdown-grace-period-seconds` | `2` | Grace period for shutdown hooks |
