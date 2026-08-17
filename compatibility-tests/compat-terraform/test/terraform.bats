#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
}

@test "init downloads the oracle/oci provider" {
    run "$TF_BIN" init -input=false -no-color
    [ "$status" -eq 0 ]
}

@test "apply creates identity and object storage resources" {
    run "$TF_BIN" apply -auto-approve -input=false -no-color
    [ "$status" -eq 0 ]
    [[ "$output" == *"Apply complete! Resources: 13 added"* ]]
}

@test "outputs reflect emulator state" {
    run "$TF_BIN" output -raw namespace
    [ "$status" -eq 0 ]
    [ "$output" = "floci-local" ]

    run "$TF_BIN" output -raw user_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw user_id
    [[ "$output" == ocid1.user.oc1..* ]]

    run "$TF_BIN" output -raw object_md5
    [ -n "$output" ]

    run "$TF_BIN" output -raw queue_messages_endpoint
    [ -n "$output" ]

    run "$TF_BIN" output -raw vault_management_endpoint
    # Host-only by design: the OCI SDKs reject endpoints containing a path, so every
    # vault is served from the single emulator host (see docs/services/kms-vault.md).
    [[ "$output" == http*://* ]]

    run "$TF_BIN" output -raw key_state
    [ "$output" = "ENABLED" ]

    run "$TF_BIN" output -raw stream_messages_endpoint
    [ -n "$output" ]
}

@test "object content is readable through the plain API" {
    run curl -sf "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}/n/floci-local/b/tf-compat-bucket/o/terraform%2Fhello.txt"
    [ "$status" -eq 0 ]
    [ "$output" = "hello from terraform" ]
}

@test "plan after apply shows no drift" {
    run "$TF_BIN" plan -detailed-exitcode -input=false -no-color
    [ "$status" -eq 0 ]
}

@test "destroy removes every resource" {
    run "$TF_BIN" destroy -auto-approve -input=false -no-color
    [ "$status" -eq 0 ]
    [[ "$output" == *"Destroy complete! Resources: 13 destroyed"* ]]

    run curl -s -o /dev/null -w '%{http_code}' \
        "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}/n/floci-local/b/tf-compat-bucket"
    [ "$output" = "404" ]
}
