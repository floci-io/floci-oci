#!/usr/bin/env bats

# Autonomous Database, management plane only: compose and CI run floci-oci with
# FLOCI_OCI_SERVICES_DATABASE_MOCK=true.

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    echo "cliadb$RANDOM" > "$BATS_FILE_TMPDIR/db_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    DB_NAME="$(cat "$BATS_FILE_TMPDIR/db_name")"
    if [ -f "$BATS_FILE_TMPDIR/db_id" ]; then DB_ID="$(cat "$BATS_FILE_TMPDIR/db_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/db_id" ]; then
        oci_json db autonomous-database delete --autonomous-database-id "$(cat "$BATS_FILE_TMPDIR/db_id")" \
            --force > /dev/null 2>&1 || true
    fi
}

@test "autonomous database: create waits for AVAILABLE" {
    # oci_json: --wait-for-state prints its progress on stderr, which oci_cmd folds into the JSON.
    run oci_json db autonomous-database create --compartment-id "$TENANCY" --db-name "$DB_NAME" \
        --display-name "cli-compat-adb" --admin-password 'Welcome12345#' \
        --compute-model ECPU --compute-count 2 --data-storage-size-in-tbs 1 --db-workload OLTP \
        --wait-for-state AVAILABLE
    [ "$status" -eq 0 ]
    # Saved before any check, so teardown_file deletes the database even when one fails.
    json_get '.data.id' > "$BATS_FILE_TMPDIR/db_id"
    [[ "$(json_get '.data.id')" == ocid1.autonomousdatabase.* ]]
    [ "$(json_get '.data."lifecycle-state"')" = "AVAILABLE" ]
    [ "$(json_get '.data."db-name"')" = "$DB_NAME" ]
    [ "$(json_get '.data."admin-password"')" = "null" ]
}

@test "autonomous database: get, list and the create work request" {
    run oci_cmd db autonomous-database get --autonomous-database-id "$DB_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."compute-count"')" = "2.0" ] || [ "$(json_get '.data."compute-count"')" = "2" ]
    [[ "$(json_get '.data."connection-strings".high')" == *"_high.adb.oraclecloud.com" ]]

    run oci_cmd db autonomous-database list --compartment-id "$TENANCY" --display-name "cli-compat-adb"
    [ "$status" -eq 0 ]
    [ "$(json_get "[.data[].id] | index(\"$DB_ID\") != null")" = "true" ]

    run oci_cmd db autonomous-database update --autonomous-database-id "$DB_ID" --compute-count 4 --force
    [ "$status" -eq 0 ]
    work_request="$(json_get '."opc-work-request-id"')"
    [ -n "$work_request" ] && [ "$work_request" != "null" ]

    run oci_cmd work-requests work-request get --work-request-id "$work_request"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.status')" = "SUCCEEDED" ]
    [ "$(json_get '.data.resources[0]."entity-type"')" = "autonomousDatabase" ]
    [ "$(json_get '.data.resources[0].identifier')" = "$DB_ID" ]
}

@test "autonomous database: stop and start" {
    run oci_json db autonomous-database stop --autonomous-database-id "$DB_ID" --wait-for-state STOPPED
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."lifecycle-state"')" = "STOPPED" ]

    run oci_json db autonomous-database start --autonomous-database-id "$DB_ID" --wait-for-state AVAILABLE
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."lifecycle-state"')" = "AVAILABLE" ]
}

@test "autonomous database: generate-wallet writes a zip with the aliases" {
    wallet="$BATS_TEST_TMPDIR/wallet.zip"
    run oci_cmd db autonomous-database generate-wallet --autonomous-database-id "$DB_ID" \
        --password 'WalletPass1' --file "$wallet"
    [ "$status" -eq 0 ]
    run python3 -c 'import sys, zipfile; print(zipfile.ZipFile(sys.argv[1]).read("tnsnames.ora").decode())' "$wallet"
    [ "$status" -eq 0 ]
    [[ "$output" == *"${DB_NAME}_high = "* ]]

    run oci_cmd db autonomous-database generate-wallet --autonomous-database-id "$DB_ID" \
        --password 'short' --file "$wallet"
    [ "$status" -ne 0 ]
    [[ "$output" == *"InvalidParameter"* ]]
}

@test "autonomous database: delete leaves it TERMINATED" {
    run oci_cmd db autonomous-database delete --autonomous-database-id "$DB_ID" --force
    [ "$status" -eq 0 ]
    wait_for_state TERMINATED '.data."lifecycle-state"' 30 db autonomous-database get --autonomous-database-id "$DB_ID"
    rm -f "$BATS_FILE_TMPDIR/db_id"
}
