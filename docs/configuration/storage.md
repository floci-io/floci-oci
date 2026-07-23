# Storage Modes

floci-oci supports four storage modes, selected globally with
`floci-oci.storage.mode` (`FLOCI_OCI_STORAGE_MODE`) and overridable per service.

| Mode | Behaviour | Use case |
|---|---|---|
| `memory` (default) | Everything in memory, lost on restart | Fast tests, CI |
| `persistent` | Write-through JSON files on every change | Durability over speed |
| `hybrid` | In-memory with periodic flush to disk | Balanced local development |
| `wal` | Write-ahead log + periodic snapshot compaction | Durability with fast writes |

Persistent state is written under `floci-oci.storage.persistent-path` (default `./data`,
`/app/data` in the Docker image — mount a volume to keep it).

## Per-service overrides

```yaml
floci-oci:
  storage:
    mode: memory
    services:
      objectstorage:
        mode: wal
        flush-interval-ms: 5000
```

## Multi-tenancy

Every storage key is transparently prefixed with the calling tenancy's OCID, so requests
signed with different tenancy OCIDs see fully isolated resources.
