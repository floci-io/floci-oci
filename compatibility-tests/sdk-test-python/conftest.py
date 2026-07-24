"""Shared fixtures for the oci Python SDK compatibility suite.

Builds SDK clients against the floci-oci emulator with a locally generated RSA key —
the emulator parses but never verifies the request signature.
"""

import os
import tempfile

import oci
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ENDPOINT = os.environ.get("FLOCI_OCI_ENDPOINT", "http://localhost:4599")
TENANCY = os.environ.get(
    "FLOCI_OCI_TENANCY",
    "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000",
)
NAMESPACE = os.environ.get("FLOCI_OCI_NAMESPACE", "floci-local")
USER = "ocid1.user.oc1..pythoncompatuser0000000000000000000000000000000000000000000"
FINGERPRINT = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"


@pytest.fixture(scope="session")
def sdk_config():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    key_file = tempfile.NamedTemporaryFile(suffix=".pem", delete=False)
    key_file.write(pem)
    key_file.close()
    return {
        "user": USER,
        "fingerprint": FINGERPRINT,
        "tenancy": TENANCY,
        "region": "us-ashburn-1",
        "key_file": key_file.name,
    }


@pytest.fixture(scope="session")
def identity(sdk_config):
    return oci.identity.IdentityClient(sdk_config, service_endpoint=ENDPOINT)


@pytest.fixture(scope="session")
def object_storage(sdk_config):
    return oci.object_storage.ObjectStorageClient(sdk_config, service_endpoint=ENDPOINT)
