#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    create_test_network "$BATS_FILE_TMPDIR"
    unique_name cli-mysql > "$BATS_FILE_TMPDIR/db_name"
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
        oci_json mysql db-system delete --db-system-id "$(cat "$BATS_FILE_TMPDIR/db_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "mysql: shapes, versions and default configurations are listed" {
    run oci_cmd mysql shape list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '[.data[].name] | index("MySQL.2") != null')" = "true" ]

    run oci_cmd mysql version list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '[.data[].versions[].version] | index("8.4.4") != null')" = "true" ]

    run oci_cmd mysql configuration list --compartment-id "$TENANCY" --shape-name MySQL.2 --type DEFAULT
    [ "$status" -eq 0 ]
    [ "$(json_get '[.data[]."display-name"] | sort | join(",")')" = "MySQL.2.HA,MySQL.2.Standalone" ]
}

@test "mysql: create returns the db system" {
    run oci_cmd mysql db-system create --compartment-id "$TENANCY" --display-name "$DB_NAME" \
        --shape-name MySQL.2 --subnet-id "$(cat "$BATS_FILE_TMPDIR/subnet_id")" \
        --admin-username admin_user --admin-password 'BEstrO0ng_#11'
    [ "$status" -eq 0 ]
    # Saved before any check, so teardown_file deletes the DB system even when one fails.
    json_get '.data.id' > "$BATS_FILE_TMPDIR/db_id"
    [[ "$(json_get '.data.id')" == ocid1.mysqldbsystem.* ]]
    [ "$(json_get '.data."shape-name"')" = "MySQL.2" ]
    [ "$(json_get '.data."admin-username"')" = "null" ]
    [ -n "$(json_get '."opc-work-request-id"')" ]
}

@test "mysql: the db system becomes ACTIVE with an endpoint" {
    wait_for_state ACTIVE '.data."lifecycle-state"' 300 mysql db-system get --db-system-id "$DB_ID"

    run oci_cmd mysql db-system get --db-system-id "$DB_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."display-name"')" = "$DB_NAME" ]
    [ "$(json_get '.data."mysql-version"')" = "8.4.4" ]
    [ "$(json_get '.data.endpoints[0].port > 0')" = "true" ]
}

@test "mysql: list resolves the db system" {
    run oci_cmd mysql db-system list --compartment-id "$TENANCY" --display-name "$DB_NAME"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data[0].id')" = "$DB_ID" ]
}

@test "mysql: stop and start" {
    run oci_cmd mysql db-system stop --db-system-id "$DB_ID" --shutdown-type FAST
    [ "$status" -eq 0 ]
    wait_for_state INACTIVE '.data."lifecycle-state"' 120 mysql db-system get --db-system-id "$DB_ID"

    run oci_cmd mysql db-system start --db-system-id "$DB_ID"
    [ "$status" -eq 0 ]
    wait_for_state ACTIVE '.data."lifecycle-state"' 300 mysql db-system get --db-system-id "$DB_ID"
}

@test "mysql: delete leaves the db system DELETED" {
    run oci_cmd mysql db-system delete --db-system-id "$DB_ID" --force
    [ "$status" -eq 0 ]
    wait_for_state DELETED '.data."lifecycle-state"' 120 mysql db-system get --db-system-id "$DB_ID"
    rm -f "$BATS_FILE_TMPDIR/db_id"
}

@test "mysql: an unknown db system is NotAuthorizedOrNotFound" {
    run oci_cmd mysql db-system get --db-system-id ocid1.mysqldbsystem.oc1.iad.missing
    [ "$status" -ne 0 ]
    [[ "$output" == *"NotAuthorizedOrNotFound"* ]]
}
