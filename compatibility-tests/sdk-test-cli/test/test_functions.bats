#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-app > "$BATS_FILE_TMPDIR/app_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    APP_NAME="$(cat "$BATS_FILE_TMPDIR/app_name")"
    if [ -f "$BATS_FILE_TMPDIR/app_id" ]; then APP_ID="$(cat "$BATS_FILE_TMPDIR/app_id")"; fi
    if [ -f "$BATS_FILE_TMPDIR/fn_id" ]; then FN_ID="$(cat "$BATS_FILE_TMPDIR/fn_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/fn_id" ]; then
        oci_json fn function delete --function-id "$(cat "$BATS_FILE_TMPDIR/fn_id")" --force > /dev/null 2>&1 || true
    fi
    if [ -f "$BATS_FILE_TMPDIR/app_id" ]; then
        oci_json fn application delete --application-id "$(cat "$BATS_FILE_TMPDIR/app_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "fn: application create" {
    run oci_cmd fn application create --compartment-id "$TENANCY" \
        --display-name "$APP_NAME" --subnet-ids '["ocid1.subnet.oc1.iad.clisubnet"]'
    [ "$status" -eq 0 ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/app_id"
    [ -s "$BATS_FILE_TMPDIR/app_id" ]
}

@test "fn: application list contains the application" {
    run oci_cmd fn application list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get ".data | map(select(.\"display-name\" == \"$APP_NAME\")) | length")" -eq 1 ]
}

@test "fn: function create" {
    run oci_cmd fn function create --application-id "$APP_ID" --display-name cli-fn \
        --image "phx.ocir.io/floci/hello:0.0.1" --memory-in-mbs 128
    [ "$status" -eq 0 ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/fn_id"
    [ -s "$BATS_FILE_TMPDIR/fn_id" ]
}

@test "fn: function get and list" {
    run oci_cmd fn function get --function-id "$FN_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."display-name"')" = "cli-fn" ]

    run oci_cmd fn function list --application-id "$APP_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -eq 1 ]
}

@test "fn: function update changes memory" {
    run oci_cmd fn function update --function-id "$FN_ID" --memory-in-mbs 256
    [ "$status" -eq 0 ]

    run oci_cmd fn function get --function-id "$FN_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."memory-in-mbs"')" -eq 256 ]
}

@test "fn: function invoke (needs the fnserver sidecar)" {
    if [ "${FLOCI_COMPAT_INVOKE:-0}" != "1" ]; then
        skip "requires the fnserver sidecar and a runnable image (set FLOCI_COMPAT_INVOKE=1)"
    fi
    run oci_cmd fn function invoke --function-id "$FN_ID" --body '{}' --file -
    [ "$status" -eq 0 ]
}

@test "fn: get on a missing application returns NotAuthorizedOrNotFound" {
    run oci_cmd fn application get --application-id "ocid1.fnapp.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
