#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    create_test_network "$BATS_FILE_TMPDIR"
    unique_name cli-ci > "$BATS_FILE_TMPDIR/ci_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    CI_NAME="$(cat "$BATS_FILE_TMPDIR/ci_name")"
    if [ -f "$BATS_FILE_TMPDIR/ci_id" ]; then CI_ID="$(cat "$BATS_FILE_TMPDIR/ci_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/ci_id" ]; then
        oci_json container-instances container-instance delete \
            --container-instance-id "$(cat "$BATS_FILE_TMPDIR/ci_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "container-instances: shapes are listed" {
    run oci_cmd container-instances container-instance list-shapes --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '[.data.items[].name] | index("CI.Standard.E4.Flex") != null')" = "true" ]
}

@test "container-instances: create returns the instance" {
    local ad
    ad="$(oci_json iam availability-domain list --compartment-id "$TENANCY" | jq -r '.data[0].name')"
    run oci_cmd container-instances container-instance create --compartment-id "$TENANCY" \
        --display-name "$CI_NAME" --availability-domain "$ad" --shape CI.Standard.E4.Flex \
        --shape-config '{"ocpus":1,"memoryInGBs":2}' \
        --containers '[{"imageUrl":"busybox:1.36","command":["sh","-c"],"arguments":["echo hello-from-cli; exec httpd -f -p 8080"]}]' \
        --vnics "[{\"subnetId\":\"$(cat "$BATS_FILE_TMPDIR/subnet_id")\"}]"
    [ "$status" -eq 0 ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/ci_id"
    [[ "$(cat "$BATS_FILE_TMPDIR/ci_id")" == ocid1.computecontainerinstance.* ]]
}

@test "container-instances: the instance becomes ACTIVE and its logs are readable" {
    wait_for_state ACTIVE '.data."lifecycle-state"' 180 \
        container-instances container-instance get --container-instance-id "$CI_ID"
    local container_id
    container_id="$(oci_json container-instances container-instance get --container-instance-id "$CI_ID" \
        | jq -r '.data.containers[0]."container-id"')"
    [ -n "$container_id" ]

    run oci_cmd container-instances container list --compartment-id "$TENANCY" --container-instance-id "$CI_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data.items[0].id')" = "$container_id" ]

    local logs="" elapsed=0
    while [[ "$logs" != *hello-from-cli* ]] && [ "$elapsed" -lt 30 ]; do
        oci_cmd container-instances container retrieve-logs --container-id "$container_id" \
            --file "$BATS_TEST_TMPDIR/logs.txt" > /dev/null
        logs="$(cat "$BATS_TEST_TMPDIR/logs.txt" 2>/dev/null || true)"
        sleep 1
        elapsed=$((elapsed + 1))
    done
    [[ "$logs" == *hello-from-cli* ]]
}

@test "container-instances: delete removes the instance" {
    run oci_cmd container-instances container-instance delete --container-instance-id "$CI_ID" --force
    [ "$status" -eq 0 ]
    wait_for_state DELETED '.data."lifecycle-state"' 120 \
        container-instances container-instance get --container-instance-id "$CI_ID"
}
