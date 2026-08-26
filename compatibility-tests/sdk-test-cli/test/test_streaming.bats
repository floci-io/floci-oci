#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-stream > "$BATS_FILE_TMPDIR/stream_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    STREAM_NAME="$(cat "$BATS_FILE_TMPDIR/stream_name")"
    if [ -f "$BATS_FILE_TMPDIR/stream_id" ]; then STREAM_ID="$(cat "$BATS_FILE_TMPDIR/stream_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/stream_id" ]; then
        oci_json streaming admin stream delete \
            --stream-id "$(cat "$BATS_FILE_TMPDIR/stream_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "streaming: stream create becomes ACTIVE" {
    run oci_cmd streaming admin stream create --name "$STREAM_NAME" \
        --partitions 1 --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local stream_id
    stream_id="$(json_get '.data.id')"
    echo "$stream_id" > "$BATS_FILE_TMPDIR/stream_id"

    wait_for_state ACTIVE '.data."lifecycle-state"' 30 streaming admin stream get --stream-id "$stream_id"
}

@test "streaming: stream list contains the stream" {
    run oci_cmd streaming admin stream list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get ".data | map(select(.id == \"$STREAM_ID\")) | length")" -eq 1 ]
}

@test "streaming: message put" {
    run oci_cmd streaming stream message put --stream-id "$STREAM_ID" \
        --messages "[{\"key\":null,\"value\":\"$(printf 'hello-stream' | base64)\"}]"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.failures')" -eq 0 ]
}

@test "streaming: cursor create-cursor from TRIM_HORIZON" {
    run oci_cmd streaming stream cursor create-cursor --stream-id "$STREAM_ID" \
        --partition 0 --type TRIM_HORIZON
    [ "$status" -eq 0 ]
    json_get '.data.value' > "$BATS_FILE_TMPDIR/cursor"
    [ -s "$BATS_FILE_TMPDIR/cursor" ]
}

@test "streaming: message get returns the published value" {
    run oci_cmd streaming stream message get --stream-id "$STREAM_ID" \
        --cursor "$(cat "$BATS_FILE_TMPDIR/cursor")"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data[0].value' | base64 -d)" = "hello-stream" ]
}

@test "streaming: get on a missing stream returns NotAuthorizedOrNotFound" {
    run oci_cmd streaming admin stream get --stream-id "ocid1.stream.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
