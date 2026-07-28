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
| Keys | Create, Get, List, Update, Enable, Disable, ScheduleDeletion, CancelDeletion |
| Key versions | Create (rotate), Get, List |
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

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

oci kms management vault create $E --compartment-id "$TENANCY" \
  --display-name demo-vault --vault-type DEFAULT
```

## Notes & limitations

Key import/backup/restore, vault replication, external key managers, secret rotation
configs and secret rules are not implemented.
