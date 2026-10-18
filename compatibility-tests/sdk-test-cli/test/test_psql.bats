#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-psql > "$BATS_FILE_TMPDIR/db_name"
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
        oci_json psql db-system delete --db-system-id "$(cat "$BATS_FILE_TMPDIR/db_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "psql: shapes and default configurations are listed" {
    run oci_cmd psql shape-summary list-shapes --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '[.data.items[].shape] | index("VM.Standard.E4.Flex") != null')" = "true" ]

    run oci_cmd psql default-configuration-collection list-default-configurations --db-version 15
    [ "$status" -eq 0 ]
    [[ "$(json_get '.data.items[0].id')" == ocid1.postgresqldefaultconfiguration.* ]]
}

@test "psql: create returns the db system" {
    run oci_cmd psql db-system create --compartment-id "$TENANCY" --display-name "$DB_NAME" \
        --db-version 15 --shape PostgreSQL.VM.Standard.E4.Flex.2.32GB \
        --network-details '{"subnetId":"ocid1.subnet.oc1.iad.clipsql"}' \
        --storage-details '{"systemType":"OCI_OPTIMIZED_STORAGE","isRegionallyDurable":true}' \
        --credentials '{"username":"admin_user","passwordDetails":{"passwordType":"PLAIN_TEXT","password":"BEstrO0ng_#11"}}'
    [ "$status" -eq 0 ]
    # Saved before any check, so teardown_file deletes the database even when one fails.
    json_get '.data.id' > "$BATS_FILE_TMPDIR/db_id"
    [[ "$(json_get '.data.id')" == ocid1.postgresqldbsystem.* ]]
    [ "$(json_get '.data.shape')" = "VM.Standard.E4.Flex" ]
    [ "$(json_get '.data."admin-username"')" = "admin_user" ]
    [ -n "$(json_get '."opc-work-request-id"')" ]
}

@test "psql: the db system becomes ACTIVE" {
    wait_for_state ACTIVE '.data."lifecycle-state"' 300 psql db-system get --db-system-id "$DB_ID"

    run oci_cmd psql db-system get --db-system-id "$DB_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."display-name"')" = "$DB_NAME" ]
    [[ "$(json_get '.data."db-version"')" == 15* ]]
    [ "$(json_get '.data."instance-ocpu-count"')" = "2" ]
}

@test "psql: list resolves the db system" {
    run oci_cmd psql db-system-collection list-db-systems --compartment-id "$TENANCY" --display-name "$DB_NAME"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items[0].id')" = "$DB_ID" ]
}

@test "psql: connection details carry an endpoint and a certificate" {
    run oci_cmd psql connection-details get --db-system-id "$DB_ID"
    [ "$status" -eq 0 ]
    [[ "$(json_get '.data."ca-certificate"')" == "-----BEGIN CERTIFICATE-----"* ]]
    [ -n "$(json_get '.data."primary-db-endpoint".fqdn')" ]
    [ "$(json_get '.data."primary-db-endpoint".port > 0')" = "true" ]
}

@test "psql: update renames the db system" {
    run oci_cmd psql db-system update --db-system-id "$DB_ID" --display-name "$DB_NAME-renamed" --force
    [ "$status" -eq 0 ]

    run oci_cmd psql db-system get --db-system-id "$DB_ID"
    [ "$(json_get '.data."display-name"')" = "$DB_NAME-renamed" ]
}

@test "psql: delete leaves the db system DELETED" {
    run oci_cmd psql db-system delete --db-system-id "$DB_ID" --force
    [ "$status" -eq 0 ]
    wait_for_state DELETED '.data."lifecycle-state"' 120 psql db-system get --db-system-id "$DB_ID"
    rm -f "$BATS_FILE_TMPDIR/db_id"
}

@test "psql: an unknown db system is NotAuthorizedOrNotFound" {
    run oci_cmd psql db-system get --db-system-id ocid1.postgresqldbsystem.oc1.iad.missing
    [ "$status" -ne 0 ]
    [[ "$output" == *"NotAuthorizedOrNotFound"* ]]
}
