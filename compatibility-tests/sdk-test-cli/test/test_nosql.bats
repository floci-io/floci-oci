#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    echo "cli_nosql_$(date +%s)_$RANDOM" > "$BATS_FILE_TMPDIR/table_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    TABLE_NAME="$(cat "$BATS_FILE_TMPDIR/table_name")"
    if [ -f "$BATS_FILE_TMPDIR/table_id" ]; then TABLE_ID="$(cat "$BATS_FILE_TMPDIR/table_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/table_id" ]; then
        oci_json nosql table delete --table-name-or-id "$(cat "$BATS_FILE_TMPDIR/table_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "nosql: table create waits for its work request" {
    local table_id
    table_id="$(oci_json nosql table create --compartment-id "$TENANCY" --name "$TABLE_NAME" \
        --ddl-statement "CREATE TABLE $TABLE_NAME (id INTEGER, name STRING, info JSON, PRIMARY KEY (id))" \
        --wait-for-state SUCCEEDED --wait-interval-seconds 1 | jq -r '.data.resources[0].identifier')"
    [[ "$table_id" == ocid1.nosqltable.* ]]
    echo "$table_id" > "$BATS_FILE_TMPDIR/table_id"
}

@test "nosql: table get echoes the DDL" {
    run oci_cmd nosql table get --table-name-or-id "$TABLE_NAME" --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."lifecycle-state"')" = "ACTIVE" ]
    [ "$(json_get '.data."ddl-statement"')" = "CREATE TABLE $TABLE_NAME (id INTEGER, name STRING, info JSON, PRIMARY KEY (id))" ]
    [ "$(json_get '.data.schema."primary-key"[0]')" = "id" ]
}

@test "nosql: row update and get" {
    run oci_cmd nosql row update --table-name-or-id "$TABLE_ID" --force \
        --value '{"id": 1, "name": "ana", "info": {"city": "Lima"}}'
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.version')" != "null" ]

    run oci_cmd nosql row update --table-name-or-id "$TABLE_ID" --force \
        --value '{"id": 2, "name": "bo", "info": {"city": "Oslo"}}'
    [ "$status" -eq 0 ]

    run oci_cmd nosql row get --table-name-or-id "$TABLE_ID" --key id:1
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.value.name')" = "ana" ]
}

@test "nosql: query execute" {
    run oci_cmd nosql query execute --compartment-id "$TENANCY" \
        --statement "SELECT id, name FROM $TABLE_NAME t WHERE t.info.city = 'Oslo'"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items | length')" -eq 1 ]
    [ "$(json_get '.data.items[0].name')" = "bo" ]
}

@test "nosql: missing table is NotAuthorizedOrNotFound" {
    run oci_cmd nosql table get --table-name-or-id "no_such_table" --compartment-id "$TENANCY"
    [ "$status" -ne 0 ]
    [[ "$output" == *"NotAuthorizedOrNotFound"* ]]
}

@test "nosql: table delete" {
    run oci_cmd nosql table delete --table-name-or-id "$TABLE_ID" --force
    [ "$status" -eq 0 ]
    rm -f "$BATS_FILE_TMPDIR/table_id"
}
