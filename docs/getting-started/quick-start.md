# Quick Start

## Run the emulator

=== "Docker Compose"

    ```bash
    docker compose up -d
    ```

=== "Maven (dev mode)"

    ```bash
    ./mvnw quarkus:dev
    ```

The emulator listens on **port 4599**. Verify it is up:

```bash
curl http://localhost:4599/_floci/health
```

## First requests

```bash
# Object Storage namespace
curl http://localhost:4599/n
# → "floci-local"

# Create a bucket
curl -X POST http://localhost:4599/n/floci-local/b \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","compartmentId":"ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"}'

# Upload and fetch an object
curl -X PUT http://localhost:4599/n/floci-local/b/demo/o/hello.txt -d 'hello oci'
curl http://localhost:4599/n/floci-local/b/demo/o/hello.txt
```

## With the OCI CLI

```bash
oci os ns get --endpoint http://localhost:4599
oci os bucket list --endpoint http://localhost:4599 \
  --compartment-id ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000 \
  --namespace floci-local
```

Or use the bundled wrapper that injects the endpoint automatically:

```bash
bin/ocilocal os ns get
```

By default authentication is parsed but never verified, so any locally generated API key works.
To verify signatures against uploaded API keys, see [Request signing](../services/identity.md#request-signing).
See [OCI CLI & SDK Setup](oci-setup.md).
