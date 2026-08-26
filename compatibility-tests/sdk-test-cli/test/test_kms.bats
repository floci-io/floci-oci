#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/vault_id" ]; then VAULT_ID="$(cat "$BATS_FILE_TMPDIR/vault_id")"; fi
    if [ -f "$BATS_FILE_TMPDIR/key_id" ]; then KEY_ID="$(cat "$BATS_FILE_TMPDIR/key_id")"; fi
}

@test "kms: vault create becomes ACTIVE" {
    run oci_cmd kms management vault create --compartment-id "$TENANCY" \
        --display-name "$(unique_name cli-vault)" --vault-type DEFAULT
    [ "$status" -eq 0 ]
    local vault_id
    vault_id="$(json_get '.data.id')"
    echo "$vault_id" > "$BATS_FILE_TMPDIR/vault_id"

    wait_for_state ACTIVE '.data."lifecycle-state"' 30 kms management vault get --vault-id "$vault_id"
}

@test "kms: vault list contains the vault" {
    run oci_cmd kms management vault list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get ".data | map(select(.id == \"$VAULT_ID\")) | length")" -eq 1 ]
}

@test "kms: key create becomes ENABLED" {
    run oci_cmd kms management key create --compartment-id "$TENANCY" \
        --display-name "$(unique_name cli-key)" --key-shape '{"algorithm":"AES","length":32}'
    [ "$status" -eq 0 ]
    local key_id
    key_id="$(json_get '.data.id')"
    echo "$key_id" > "$BATS_FILE_TMPDIR/key_id"

    wait_for_state ENABLED '.data."lifecycle-state"' 30 kms management key get --key-id "$key_id"
}

@test "kms: encrypt/decrypt roundtrip" {
    local plaintext
    plaintext="$(printf 'kms-secret' | base64)"
    run oci_cmd kms crypto encrypt --key-id "$KEY_ID" --plaintext "$plaintext"
    [ "$status" -eq 0 ]
    local ciphertext
    ciphertext="$(json_get '.data.ciphertext')"
    [ -n "$ciphertext" ]

    run oci_cmd kms crypto decrypt --key-id "$KEY_ID" --ciphertext "$ciphertext"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.plaintext' | base64 -d)" = "kms-secret" ]
}

@test "kms: key-version create and list" {
    run oci_cmd kms management key-version create --key-id "$KEY_ID"
    [ "$status" -eq 0 ]

    run oci_cmd kms management key-version list --key-id "$KEY_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -ge 2 ]
}

@test "kms: key disable then enable" {
    run oci_cmd kms management key disable --key-id "$KEY_ID"
    [ "$status" -eq 0 ]
    wait_for_state DISABLED '.data."lifecycle-state"' 30 kms management key get --key-id "$KEY_ID"

    run oci_cmd kms management key enable --key-id "$KEY_ID"
    [ "$status" -eq 0 ]
    wait_for_state ENABLED '.data."lifecycle-state"' 30 kms management key get --key-id "$KEY_ID"
}

@test "kms: get on a missing key returns NotAuthorizedOrNotFound" {
    run oci_cmd kms management key get --key-id "ocid1.key.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
