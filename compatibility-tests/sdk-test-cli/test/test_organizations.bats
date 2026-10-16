#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-child > "$BATS_FILE_TMPDIR/child_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    CHILD_NAME="$(cat "$BATS_FILE_TMPDIR/child_name")"
    if [ -f "$BATS_FILE_TMPDIR/organization_id" ]; then ORGANIZATION_ID="$(cat "$BATS_FILE_TMPDIR/organization_id")"; fi
    if [ -f "$BATS_FILE_TMPDIR/child_id" ]; then CHILD_ID="$(cat "$BATS_FILE_TMPDIR/child_id")"; fi
}

@test "organizations: child-tenancy create is accepted" {
    run oci_cmd organizations child-tenancy create --compartment-id "$TENANCY" \
        --tenancy-name "$CHILD_NAME" --home-region us-ashburn-1 --admin-email admin@example.com
    [ "$status" -eq 0 ]
}

@test "organizations: organization list returns the parent's organization" {
    run oci_cmd organizations organization list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local organization_id
    organization_id="$(json_get '.data.items[0].id')"
    [ "$organization_id" != "null" ]
    [ "$(json_get '.data.items[0]."compartment-id"')" = "$TENANCY" ]
    echo "$organization_id" > "$BATS_FILE_TMPDIR/organization_id"
}

@test "organizations: the child joins the organization" {
    run oci_cmd organizations organization list-organization-tenancies --organization-id "$ORGANIZATION_ID"
    [ "$status" -eq 0 ]
    local child_id
    child_id="$(json_get ".data.items[] | select(.name == \"$CHILD_NAME\") | .\"tenancy-id\"")"
    [ -n "$child_id" ]
    [ "$(json_get ".data.items[] | select(.name == \"$CHILD_NAME\") | .role")" = "CHILD" ]
    echo "$child_id" > "$BATS_FILE_TMPDIR/child_id"
}

@test "organizations: link list shows the CORE link" {
    run oci_cmd organizations link list --child-tenancy-id "$CHILD_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items[0].feature')" = "CORE" ]
    [ "$(json_get '.data.items[0]."parent-tenancy-id"')" = "$TENANCY" ]
}

@test "organizations: an unsubscribed home region is rejected" {
    run oci_cmd organizations child-tenancy create --compartment-id "$TENANCY" \
        --tenancy-name "$CHILD_NAME-tokyo" --home-region ap-tokyo-1 --admin-email admin@example.com
    [ "$status" -ne 0 ]
    [[ "$output" == *"InvalidParameter"* ]]
}
