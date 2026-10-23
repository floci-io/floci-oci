# Vault, KMS and Secrets

Three OCI services that work together:

| Service | API | SDK client |
|---|---|---|
| KMS vaults | `/20180608/vaults` | `KmsVaultClient` |
| KMS keys | `/20180608/keys` | `KmsManagementClient` |
| KMS crypto | `/20180608/{encrypt,decrypt,…}` | `KmsCryptoClient` |
| Secrets (management) | `/20180608/secrets` | `VaultsClient` |
| Secrets (retrieval) | `/20190301/secretbundles` | `SecretsClient` |

## Supported operations

| Area | Operations |
|---|---|
| Vaults | Create, Get, List, Update, ScheduleDeletion, CancelDeletion, ChangeCompartment |
| Keys | Create, Get, List, Update, Enable, Disable, ScheduleDeletion, CancelDeletion, Import (AES, RSA) |
| Wrapping key | GetWrappingKey |
| Key versions | Create (rotate), Import, Get, List |
| Crypto | Encrypt, Decrypt, GenerateDataEncryptionKey, Sign, Verify |
| Secrets | CreateSecret, GetSecret, ListSecrets, UpdateSecret, Schedule/CancelDeletion, versions |
| Bundles | GetSecretBundle, GetSecretBundleByName, ListSecretBundleVersions |

## Real cryptography

Crypto is not stubbed. Keys hold real material:

- **AES** — AES-GCM encrypt/decrypt. The ciphertext envelope carries the key-version id,
  so ciphertext produced before a rotation still decrypts afterwards.
- **RSA / ECDSA** — real JCA sign/verify (`SHA_*_RSA_PKCS1_V1_5`, `ECDSA_SHA_*`).
- `plaintextChecksum` (CRC32) is returned on Decrypt and GenerateDataEncryptionKey, as
  the wire contract requires.

## Wire notes

- **There is no DELETE verb** for vaults, keys or secrets — deletion is scheduled via
  `actions/scheduleDeletion` and reflected in `lifecycleState` (`PENDING_DELETION`),
  reversible with `actions/cancelDeletion`.
- **Keys reach `ENABLED`, not `ACTIVE`.**
- `KeySummary` has no `keyShape`/`currentKeyVersion` but carries a flat `algorithm`.
- The `Secret` shape **never echoes content** — content is only readable through a
  secret bundle.
- `GetSecretBundleByName` is a **POST with query parameters, no body, and no etag**.
- Single secret versions live at `/version/{n}` (singular); the list is `/versions`.

## Endpoint indirection (deviation)

Real OCI gives each vault its own management/crypto hostname, returned as
`managementEndpoint` / `cryptoEndpoint`. The OCI SDKs **reject endpoints containing a
path** (`endpoint must not contain user info, path, query, or fragment`), so the emulator
cannot encode the vault in a path suffix and returns its own host for both.

Consequence: `CreateKey` carries no `vaultId`, so a new key is attached to the caller's
compartment vault — the most recently created `ACTIVE` one. With the usual one-vault
setup this is exact; with several vaults in one compartment, keys land in the newest.

## Key import

`GetWrappingKey` returns a real RSA-4096 public key in PEM form. `ImportKey` unwraps the
supplied `wrappedImportKey.keyMaterial` with its private half and stores the material as
the key's first version, with `origin` `EXTERNAL`. `ImportKeyVersion` does the same for an
existing key and makes the new version current, as `CreateKeyVersion` does. Imported keys
then work with every crypto operation.

- `RSA_OAEP_SHA256`: the material encrypted directly with RSA-OAEP (SHA-256 digest and MGF1).
- `RSA_OAEP_AES_SHA256`: a temporary AES key encrypted with RSA-OAEP, followed by the
  material wrapped with that AES key (AES key wrap with padding, RFC 5649).

| Key | Lengths (`keyShape.length`, bytes) | Material | Wrapping |
|---|---|---|---|
| AES | 16, 24, 32 | the raw key, exactly that many bytes | `RSA_OAEP_SHA256` or `RSA_OAEP_AES_SHA256` |
| RSA | 256, 384, 512 | the DER PKCS8 private key; the public key is derived from it | `RSA_OAEP_AES_SHA256` only |

As in OCI, ECDSA keys can't be imported: `ImportKey` and `ImportKeyVersion` reject them
with `400 InvalidParameter`.

**One wrapping key pair for every vault (deviation).** Real OCI keeps a wrapping key per
vault and picks the vault from the request hostname. `GetWrappingKey` carries no vault id
and every vault shares the emulator host, so every vault keeps its own `wrappingkeyId` but
they all share one RSA key pair. `GetWrappingKey` reports the most recently created
`ACTIVE` vault, with that vault's `wrappingkeyId` as its `id`. The key pair is generated on
first use and kept in the `kms` storage, so it survives restarts in persistent modes.

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci kms management vault create $E --compartment-id "$TENANCY" \
  --display-name demo-vault --vault-type DEFAULT
```

## Notes & limitations

Key backup/restore, vault replication, external key managers, secret rotation
configs and secret rules are not implemented.
