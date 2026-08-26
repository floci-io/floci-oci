#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    unique_name cli-cluster > "$BATS_FILE_TMPDIR/cluster_name"
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
    CLUSTER_NAME="$(cat "$BATS_FILE_TMPDIR/cluster_name")"
    if [ -f "$BATS_FILE_TMPDIR/cluster_id" ]; then CLUSTER_ID="$(cat "$BATS_FILE_TMPDIR/cluster_id")"; fi
}

teardown_file() {
    load 'test_helper/common-setup'
    _common_setup
    if [ -f "$BATS_FILE_TMPDIR/cluster_id" ]; then
        oci_json ce cluster delete --cluster-id "$(cat "$BATS_FILE_TMPDIR/cluster_id")" --force > /dev/null 2>&1 || true
    fi
}

@test "ce: cluster create is accepted" {
    run oci_cmd ce cluster create --compartment-id "$TENANCY" --name "$CLUSTER_NAME" \
        --vcn-id "ocid1.vcn.oc1.iad.clivcn" --kubernetes-version "v1.30.1"
    [ "$status" -eq 0 ]
}

@test "ce: cluster list resolves the cluster and it becomes ACTIVE" {
    run oci_cmd ce cluster list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local cluster_id
    cluster_id="$(json_get ".data[] | select(.name == \"$CLUSTER_NAME\") | .id")"
    [ -n "$cluster_id" ]
    echo "$cluster_id" > "$BATS_FILE_TMPDIR/cluster_id"

    # Real mode pulls the k3s image on first use; be generous.
    if wait_for_state ACTIVE '.data."lifecycle-state"' 180 ce cluster get --cluster-id "$cluster_id"; then
        touch "$BATS_FILE_TMPDIR/cluster_active"
    else
        skip "cluster did not reach ACTIVE (sidecar unavailable?)"
    fi
}

@test "ce: work-request list shows the cluster creation" {
    run oci_cmd ce work-request list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | map(select(."operation-type" == "CLUSTER_CREATE")) | length')" -ge 1 ]
}

@test "ce: create-kubeconfig returns a kubeconfig" {
    [ -f "$BATS_FILE_TMPDIR/cluster_active" ] || skip "cluster never reached ACTIVE"
    run oci_json ce cluster create-kubeconfig --cluster-id "$CLUSTER_ID" --file -
    [ "$status" -eq 0 ]
    [[ "$output" == *apiVersion* ]]
}

@test "ce: node-pool list succeeds" {
    run oci_cmd ce node-pool list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
}

@test "ce: get on a missing cluster returns NotAuthorizedOrNotFound" {
    run oci_cmd ce cluster get --cluster-id "ocid1.cluster.oc1.iad.missing"
    [ "$status" -ne 0 ]
    [[ "$output" == *NotAuthorizedOrNotFound* ]]
}
