#!/usr/bin/env python3
"""Generates the throwaway OCI CLI config baked into the compat image.

The OCI CLI signs every request and refuses to run without an API key, while
floci-oci parses the signature but never verifies it (see OciSignatureParser).
This key therefore exists only to get the CLI past its own validation. It is
public by construction — never point this config at real OCI.

The tenancy must match floci-oci.default-tenancy-id: tenancy is the storage
partition, so a mismatch would put CLI writes in a different partition than
unsigned requests.
"""

import hashlib
import pathlib

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

CONFIG_DIR = pathlib.Path("/etc/floci-oci/oci")
TENANCY = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
USER = "ocid1.user.oc1..flocilocaluser00000000000000000000000000000000000000000000"
REGION = "us-ashburn-1"

CONFIG_DIR.mkdir(parents=True, exist_ok=True)

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
key_file = CONFIG_DIR / "key.pem"
key_file.write_bytes(
    key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    )
    # The CLI warns on every invocation unless the key carries this trailing
    # label; appending it is the fix the warning itself recommends.
    + b"OCI_API_KEY\n"
)
key_file.chmod(0o600)

# OCI fingerprints are the colon-separated MD5 of the DER public key. Not a
# security boundary here, hence usedforsecurity=False so FIPS hosts can build.
digest = hashlib.md5(
    key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    ),
    usedforsecurity=False,
).hexdigest()
fingerprint = ":".join(digest[i:i + 2] for i in range(0, len(digest), 2))

config_file = CONFIG_DIR / "config"
config_file.write_text(
    "[DEFAULT]\n"
    f"user={USER}\n"
    f"fingerprint={fingerprint}\n"
    f"key_file={key_file}\n"
    f"tenancy={TENANCY}\n"
    f"region={REGION}\n"
)
config_file.chmod(0o600)

print(f"wrote {config_file} (fingerprint {fingerprint})")
