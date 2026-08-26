#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    _common_setup
}

@test "iam: region list returns regions" {
    run oci_cmd iam region list
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -gt 0 ]
}

@test "iam: compartment create returns a compartment OCID" {
    run oci_cmd iam compartment create --compartment-id "$TENANCY" --name "$(unique_name cli-cmp)" --description "cli compat"
    [ "$status" -eq 0 ]
    [[ "$(json_get '.data.id')" == ocid1.compartment.* ]]
}

@test "iam: user create/get roundtrip" {
    local name
    name="$(unique_name cli-user)"
    run oci_cmd iam user create --name "$name" --description "cli compat"
    [ "$status" -eq 0 ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/user_id"

    run oci_cmd iam user get --user-id "$(cat "$BATS_FILE_TMPDIR/user_id")"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.name')" = "$name" ]
    [ "$(json_get '.data."lifecycle-state"')" = "ACTIVE" ]
}

@test "iam: group create and add-user membership" {
    run oci_cmd iam group create --name "$(unique_name cli-grp)" --description "cli compat"
    [ "$status" -eq 0 ]
    local group_id
    group_id="$(json_get '.data.id')"

    run oci_cmd iam group add-user --user-id "$(cat "$BATS_FILE_TMPDIR/user_id")" --group-id "$group_id"
    [ "$status" -eq 0 ]
    [[ "$(json_get '.data.id')" == ocid1.* ]]
}

@test "iam: user list honors --limit" {
    run oci_cmd iam user list --compartment-id "$TENANCY" --limit 2
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -le 2 ]

    run oci_cmd iam user list --compartment-id "$TENANCY" --all
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -ge 1 ]
}

@test "iam: policy create/get roundtrip" {
    local name
    name="$(unique_name cli-pol)"
    run oci_cmd iam policy create --compartment-id "$TENANCY" --name "$name" \
        --description "cli compat" \
        --statements '["Allow group Administrators to manage all-resources in tenancy"]'
    [ "$status" -eq 0 ]
    local policy_id
    policy_id="$(json_get '.data.id')"

    run oci_cmd iam policy get --policy-id "$policy_id"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.statements | length')" -eq 1 ]
}

@test "iam: get on a missing user returns NotAuthorizedOrNotFound" {
    run oci_cmd iam user get --user-id "ocid1.user.oc1..missing0000000000000000000000000000000000000000000000000000"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
