#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup

    # Secrets need a KMS vault + key to attach to.
    oci_json kms management vault create --compartment-id "$TENANCY" \
        --display-name "$(unique_name cli-sec-vault)" --vault-type DEFAULT \
        | jq -r '.data.id' > "$BATS_FILE_TMPDIR/vault_id"
    wait_for_state ACTIVE '.data."lifecycle-state"' 30 \
        kms management vault get --vault-id "$(cat "$BATS_FILE_TMPDIR/vault_id")"

    oci_json kms management key create --compartment-id "$TENANCY" \
        --display-name "$(unique_name cli-sec-key)" --key-shape '{"algorithm":"AES","length":32}' \
        | jq -r '.data.id' > "$BATS_FILE_TMPDIR/key_id"

    unique_name cli-secret > "$BATS_FILE_TMPDIR/secret_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    VAULT_ID="$(cat "$BATS_FILE_TMPDIR/vault_id")"
    KEY_ID="$(cat "$BATS_FILE_TMPDIR/key_id")"
    SECRET_NAME="$(cat "$BATS_FILE_TMPDIR/secret_name")"
    if [ -f "$BATS_FILE_TMPDIR/secret_id" ]; then SECRET_ID="$(cat "$BATS_FILE_TMPDIR/secret_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/secret_id" ]; then
        oci_json vault secret schedule-secret-deletion \
            --secret-id "$(cat "$BATS_FILE_TMPDIR/secret_id")" > /dev/null 2>&1 || true
    fi
}

@test "vault: secret create-base64 becomes ACTIVE" {
    run oci_cmd vault secret create-base64 --compartment-id "$TENANCY" \
        --secret-name "$SECRET_NAME" --vault-id "$VAULT_ID" --key-id "$KEY_ID" \
        --secret-content-content "$(printf 'v1' | base64)"
    [ "$status" -eq 0 ]
    local secret_id
    secret_id="$(json_get '.data.id')"
    echo "$secret_id" > "$BATS_FILE_TMPDIR/secret_id"

    wait_for_state ACTIVE '.data."lifecycle-state"' 30 vault secret get --secret-id "$secret_id"
}

@test "vault: secret list contains the secret" {
    run oci_cmd vault secret list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get ".data | map(select(.\"secret-name\" == \"$SECRET_NAME\")) | length")" -eq 1 ]
}

@test "secrets: secret-bundle get decodes the content" {
    run oci_cmd secrets secret-bundle get --secret-id "$SECRET_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."secret-bundle-content".content' | base64 -d)" = "v1" ]
}

@test "secrets: secret-bundle get-secret-bundle-by-name" {
    run oci_cmd secrets secret-bundle get-secret-bundle-by-name \
        --secret-name "$SECRET_NAME" --vault-id "$VAULT_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."secret-bundle-content".content' | base64 -d)" = "v1" ]
}

@test "vault: update-base64 rolls the CURRENT version" {
    run oci_cmd vault secret update-base64 --secret-id "$SECRET_ID" \
        --secret-content-content "$(printf 'v2' | base64)"
    [ "$status" -eq 0 ]

    run oci_cmd secrets secret-bundle get --secret-id "$SECRET_ID" --stage CURRENT
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."secret-bundle-content".content' | base64 -d)" = "v2" ]
}

@test "secrets: get on a missing secret returns NotAuthorizedOrNotFound" {
    run oci_cmd secrets secret-bundle get \
        --secret-id "ocid1.vaultsecret.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
