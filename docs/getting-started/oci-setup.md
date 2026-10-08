# OCI CLI & SDK Setup

floci-oci parses the OCI request signature for tenancy/user context but **never verifies the
RSA signature** — any locally generated API key works. No account, no real credentials.

## OCI CLI

Create a throwaway key and config once:

```bash
mkdir -p ~/.oci
openssl genrsa -out ~/.oci/floci_key.pem 2048

cat > ~/.oci/config <<'EOF'
[FLOCI]
user=ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000000000
fingerprint=aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99
tenancy=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000
region=us-ashburn-1
key_file=~/.oci/floci_key.pem
EOF
```

Then point the CLI at the emulator:

```bash
oci --profile FLOCI os ns get --endpoint http://localhost:4599
```

## Java SDK

```java
KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
var auth = SimpleAuthenticationDetailsProvider.builder()
        .tenantId("ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000")
        .userId("ocid1.user.oc1..anyuser")
        .fingerprint("aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99")
        .privateKeySupplier(() -> new ByteArrayInputStream(pemOf(pair)))
        .region(Region.US_ASHBURN_1)
        .build();

ObjectStorageClient client = ObjectStorageClient.builder().build(auth);
client.setEndpoint("http://localhost:4599");
```

## Python SDK

```python
import oci

config = {
    "user": "ocid1.user.oc1..anyuser",
    "fingerprint": "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99",
    "tenancy": "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000",
    "region": "us-ashburn-1",
    "key_file": "~/.oci/floci_key.pem",
}
client = oci.object_storage.ObjectStorageClient(
    config, service_endpoint="http://localhost:4599")
print(client.get_namespace().data)
```

## Multi-tenancy

The tenancy OCID in your signing key's `keyId` is the storage partition — requests signed
with different tenancy OCIDs see isolated resources. Unsigned requests fall back to the
configured `floci-oci.default-tenancy-id`.

## Regions

Regional resources (buckets, queues, streams, vaults, keys, secrets, functions, clusters) are
partitioned by region as well as tenancy. The region comes from the request `Host` header:
the first dot-separated label that is a known OCI region name, so a client that reaches the
emulator as `queue.us-phoenix-1.oci.oraclecloud.com` (through the embedded DNS or TLS setup)
works in `us-phoenix-1`. Any other host, such as `localhost:4599`, uses
`floci-oci.default-region`.

Identity is global, as on real OCI: compartments, users, groups and policies are shared by
every region of the tenancy. The tenancy's home region is always `default-region`.

Data persisted by an older floci-oci, before region partitioning, is moved into
`default-region` on startup.
