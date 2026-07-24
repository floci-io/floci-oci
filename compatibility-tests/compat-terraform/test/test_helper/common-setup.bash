#!/usr/bin/env bash

# Shared setup for the IaC compatibility suites. TF_BIN is "terraform" or "tofu".

_common_setup() {
    TF_BIN="${TF_BIN:-terraform}"
    ENDPOINT="${FLOCI_OCI_ENDPOINT:-http://localhost:4599}"
    export TENANCY="ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"

    # Route every SDK client the suite touches at the emulator.
    export TF_VAR_CLIENT_HOST_OVERRIDES="oci_identity.IdentityClient=${ENDPOINT};oci_object_storage.ObjectStorageClient=${ENDPOINT}"

    # The provider needs a parseable API key; the emulator never verifies the signature.
    if [ ! -f "$BATS_TEST_DIRNAME/../test_key.pem" ]; then
        openssl genrsa -out "$BATS_TEST_DIRNAME/../test_key.pem" 2048 2>/dev/null
    fi

    cd "$BATS_TEST_DIRNAME/.."
}
