#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-queue > "$BATS_FILE_TMPDIR/queue_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    QUEUE_NAME="$(cat "$BATS_FILE_TMPDIR/queue_name")"
    if [ -f "$BATS_FILE_TMPDIR/queue_id" ]; then QUEUE_ID="$(cat "$BATS_FILE_TMPDIR/queue_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/queue_id" ]; then
        oci_json queue queue-admin queue delete --queue-id "$(cat "$BATS_FILE_TMPDIR/queue_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "queue: create is accepted" {
    run oci_cmd queue queue-admin queue create --compartment-id "$TENANCY" --display-name "$QUEUE_NAME"
    [ "$status" -eq 0 ]
}

@test "queue: list resolves the queue and it becomes ACTIVE" {
    run oci_cmd queue queue-admin queue list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local queue_id
    queue_id="$(json_get ".data.items[] | select(.\"display-name\" == \"$QUEUE_NAME\") | .id")"
    [ -n "$queue_id" ]
    echo "$queue_id" > "$BATS_FILE_TMPDIR/queue_id"

    wait_for_state ACTIVE '.data."lifecycle-state"' 30 queue queue-admin queue get --queue-id "$queue_id"

    run oci_cmd queue queue-admin queue get --queue-id "$queue_id"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."messages-endpoint"')" != "null" ]
}

@test "queue: put-messages" {
    run oci_cmd queue messages put-messages --queue-id "$QUEUE_ID" \
        --messages '[{"content":"m1"},{"content":"m2"}]'
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.messages | length')" -eq 2 ]
}

@test "queue: get-messages returns content and a receipt" {
    run oci_cmd queue messages get-messages --queue-id "$QUEUE_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.messages | length')" -ge 1 ]
    [ "$(json_get '.data.messages[0].content')" = "m1" ]
    json_get '.data.messages[0].receipt' > "$BATS_FILE_TMPDIR/receipt"
}

@test "queue: delete-message by receipt" {
    run oci_cmd queue messages delete-message --queue-id "$QUEUE_ID" \
        --message-receipt "$(cat "$BATS_FILE_TMPDIR/receipt")" --force
    [ "$status" -eq 0 ]
}

@test "queue: purge empties the queue" {
    run oci_cmd queue queue-admin queue purge --queue-id "$QUEUE_ID" --purge-type BOTH
    [ "$status" -eq 0 ]

    wait_for_state 0 '.data.queue."visible-messages"' 30 queue messages get-stats --queue-id "$QUEUE_ID"
}

@test "queue: get on a missing queue returns NotAuthorizedOrNotFound" {
    run oci_cmd queue queue-admin queue get --queue-id "ocid1.queue.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
