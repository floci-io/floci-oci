#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    echo "$(unique_name cli-dns).example" > "$BATS_FILE_TMPDIR/zone_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    ZONE_NAME="$(cat "$BATS_FILE_TMPDIR/zone_name")"
    if [ -f "$BATS_FILE_TMPDIR/zone_id" ]; then ZONE_ID="$(cat "$BATS_FILE_TMPDIR/zone_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/zone_id" ]; then
        oci_json dns zone delete --zone-name-or-id "$(cat "$BATS_FILE_TMPDIR/zone_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "dns: zone create returns an ACTIVE zone" {
    run oci_cmd dns zone create --name "$ZONE_NAME" --compartment-id "$TENANCY" --zone-type PRIMARY
    [ "$status" -eq 0 ]
    [ "$(json_get '.data."lifecycle-state"')" = "ACTIVE" ]
    [ "$(json_get '.data.scope')" = "GLOBAL" ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/zone_id"
}

@test "dns: zone get by name" {
    run oci_cmd dns zone get --zone-name-or-id "$ZONE_NAME"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.id')" = "$ZONE_ID" ]
}

@test "dns: record rrset update replaces the RRSet" {
    run oci_cmd dns record rrset update --zone-name-or-id "$ZONE_ID" --domain "www.$ZONE_NAME" --rtype A \
        --items "[{\"domain\":\"www.$ZONE_NAME\",\"rtype\":\"A\",\"rdata\":\"192.0.2.20\",\"ttl\":60}]" --force
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items | length')" -eq 1 ]
    [ "$(json_get '.data.items[0].rdata')" = "192.0.2.20" ]
}

@test "dns: record rrset get reads it back" {
    run oci_cmd dns record rrset get --zone-name-or-id "$ZONE_NAME" --domain "www.$ZONE_NAME" --rtype A
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items[0].ttl')" -eq 60 ]
    [ "$(json_get '.data.items[0]."is-protected"')" = "false" ]
}

@test "dns: zone delete" {
    run oci_cmd dns zone delete --zone-name-or-id "$ZONE_ID" --force
    [ "$status" -eq 0 ]
    rm -f "$BATS_FILE_TMPDIR/zone_id"
    run oci_cmd dns zone get --zone-name-or-id "$ZONE_ID"
    [ "$status" -ne 0 ]
}
