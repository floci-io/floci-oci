#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-bucket > "$BATS_FILE_TMPDIR/bucket"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    BUCKET="$(cat "$BATS_FILE_TMPDIR/bucket")"
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    local bucket
    bucket="$(cat "$BATS_FILE_TMPDIR/bucket")"
    oci_json os object bulk-delete -ns "$NAMESPACE" -bn "$bucket" --force > /dev/null 2>&1 || true
    oci_json os bucket delete -ns "$NAMESPACE" -bn "$bucket" --force > /dev/null 2>&1 || true
}

@test "os: namespace get returns the emulator namespace" {
    run oci_cmd os ns get
    [ "$status" -eq 0 ]
    [ "$(json_get '.data')" = "$NAMESPACE" ]
}

@test "os: bucket create, duplicate rejected with BucketAlreadyExists" {
    run oci_cmd os bucket create -ns "$NAMESPACE" --name "$BUCKET" --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.name')" = "$BUCKET" ]

    run oci_cmd os bucket create -ns "$NAMESPACE" --name "$BUCKET" --compartment-id "$TENANCY"
    [ "$status" -ne 0 ]
    [[ "$output" == *BucketAlreadyExists* ]]
}

@test "os: object put/get content roundtrip" {
    printf 'hello-from-oci-cli' > "$BATS_TEST_TMPDIR/payload"
    run oci_cmd os object put -ns "$NAMESPACE" -bn "$BUCKET" --name hello.txt --file "$BATS_TEST_TMPDIR/payload"
    [ "$status" -eq 0 ]

    run oci_json os object get -ns "$NAMESPACE" -bn "$BUCKET" --name hello.txt --file -
    [ "$status" -eq 0 ]
    [ "$output" = "hello-from-oci-cli" ]
}

@test "os: object list supports --prefix and --limit" {
    for i in 1 2 3; do
        printf 'x' > "$BATS_TEST_TMPDIR/obj"
        oci_json os object put -ns "$NAMESPACE" -bn "$BUCKET" --name "p/obj-$i" --file "$BATS_TEST_TMPDIR/obj" > /dev/null
    done

    run oci_cmd os object list -ns "$NAMESPACE" -bn "$BUCKET" --prefix p/ --limit 2
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | length')" -eq 2 ]
}

@test "os: object rename" {
    run oci_cmd os object rename -ns "$NAMESPACE" -bn "$BUCKET" --source-name hello.txt --new-name hello2.txt
    [ "$status" -eq 0 ]

    run oci_json os object get -ns "$NAMESPACE" -bn "$BUCKET" --name hello2.txt --file -
    [ "$status" -eq 0 ]
    [ "$output" = "hello-from-oci-cli" ]
}

@test "os: pre-authenticated request serves the object" {
    run oci_cmd os preauth-request create -ns "$NAMESPACE" -bn "$BUCKET" --name cli-par \
        --access-type ObjectRead -on hello2.txt --time-expires "2030-01-01T00:00:00+00:00"
    [ "$status" -eq 0 ]
    local uri
    uri="$(json_get '.data."access-uri"')"

    run curl -sf "$ENDPOINT$uri"
    [ "$status" -eq 0 ]
    [ "$output" = "hello-from-oci-cli" ]
}

# `os object get`/`head` HEAD first, and HEAD errors carry no body — rename is
# the cheapest object operation whose 404 surfaces the ObjectNotFound code.
@test "os: rename of a missing object returns ObjectNotFound" {
    run oci_cmd os object rename -ns "$NAMESPACE" -bn "$BUCKET" --source-name nope.txt --new-name nope2.txt
    [ "$status" -ne 0 ]
    [[ "$output" == *ObjectNotFound* ]]
}

@test "os: get on a missing bucket returns BucketNotFound" {
    run oci_cmd os bucket get -ns "$NAMESPACE" -bn "no-such-bucket-cli"
    [ "$status" -ne 0 ]
    [[ "$output" == *BucketNotFound* ]]
}
