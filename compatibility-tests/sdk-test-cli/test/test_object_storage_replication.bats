#!/usr/bin/env bats

# The command sequence of Oracle's own CLI replication test (oci-cli
# test_object_storage_replication.py), within one region as its recording does.

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-repl-src > "$BATS_FILE_TMPDIR/source"
    unique_name cli-repl-dst > "$BATS_FILE_TMPDIR/destination"
    oci_json os bucket create -ns "$NAMESPACE" --name "$(cat "$BATS_FILE_TMPDIR/source")" \
        --compartment-id "$TENANCY" > /dev/null
    oci_json os bucket create -ns "$NAMESPACE" --name "$(cat "$BATS_FILE_TMPDIR/destination")" \
        --compartment-id "$TENANCY" > /dev/null
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    SOURCE="$(cat "$BATS_FILE_TMPDIR/source")"
    DESTINATION="$(cat "$BATS_FILE_TMPDIR/destination")"
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    local bucket
    for bucket in "$(cat "$BATS_FILE_TMPDIR/source")" "$(cat "$BATS_FILE_TMPDIR/destination")"; do
        oci_json os bucket delete -ns "$NAMESPACE" -bn "$bucket" --empty --force > /dev/null 2>&1 || true
    done
}

@test "os replication: create-replication-policy answers an ACTIVE policy" {
    run oci_cmd os replication create-replication-policy --bucket-name "$SOURCE" \
        --destination-bucket "$DESTINATION" --destination-region us-ashburn-1 --name replication-policy-cli
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.name')" = "replication-policy-cli" ]
    [ "$(json_get '.data.status')" = "ACTIVE" ]
    [ "$(json_get '.data."time-last-sync"')" = "null" ]
}

@test "os replication: list-replication-policies names the policy" {
    run oci_cmd os replication list-replication-policies --bucket-name "$SOURCE"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data[0].name')" = "replication-policy-cli" ]
}

@test "os replication: list-replication-sources on the destination names the source" {
    run oci_cmd os replication list-replication-sources --bucket-name "$DESTINATION"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data[0]."source-bucket-name"')" = "$SOURCE" ]
}

@test "os replication: an object put to the source reaches the destination" {
    printf 'Sample Content' > "$BATS_TEST_TMPDIR/object"
    run oci_cmd os object put -ns "$NAMESPACE" -bn "$SOURCE" --name object_0 --file "$BATS_TEST_TMPDIR/object"
    [ "$status" -eq 0 ]

    run oci_json os object get -ns "$NAMESPACE" -bn "$DESTINATION" --name object_0 --file -
    [ "$status" -eq 0 ]
    [ "$output" = "Sample Content" ]
}

@test "os replication: the source bucket cannot be deleted while it replicates" {
    run oci_cmd os bucket delete -ns "$NAMESPACE" --bucket-name "$SOURCE" --force
    [ "$status" -ne 0 ]
    [[ "$output" == *ServiceError* ]]
    [[ "$output" == *BucketReplicationEnabled* ]]
}

@test "os replication: make-bucket-writable leaves the policy in CLIENT_ERROR" {
    run oci_cmd os replication make-bucket-writable --bucket-name "$DESTINATION"
    [ "$status" -eq 0 ]

    run oci_cmd os replication list-replication-policies --bucket-name "$SOURCE"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data[0].status')" = "CLIENT_ERROR" ]
}

@test "os replication: delete-replication-policy lets the source be deleted" {
    local id
    id="$(oci_json os replication list-replication-policies --bucket-name "$SOURCE" | jq -r '.data[0].id')"
    run oci_cmd os replication delete-replication-policy --bucket-name "$SOURCE" --replication-id "$id" --force
    [ "$status" -eq 0 ]

    run oci_cmd os replication list-replication-policies --bucket-name "$SOURCE"
    [ "$status" -eq 0 ]
    # The CLI prints nothing at all for an empty list.
    [ -z "$output" ] || [ "$(json_get '.data | length')" -eq 0 ]
}
