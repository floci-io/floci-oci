#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
    create_test_network "$BATS_FILE_TMPDIR"
    unique_name cli-cluster > "$BATS_FILE_TMPDIR/cluster_name"
    unique_name cli-ocir > "$BATS_FILE_TMPDIR/ocir_repository"
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
    if [ -f "$BATS_FILE_TMPDIR/ocir_repository_id" ]; then
        oci_json artifacts container repository delete \
            --repository-id "$(cat "$BATS_FILE_TMPDIR/ocir_repository_id")" --force > /dev/null 2>&1 || true
    fi
    delete_test_network "$BATS_FILE_TMPDIR"
}

# The OCIR host for the cluster's region (us-ashburn-1, from gen-oci-cli-config.sh) with
# floci-oci's port: the name a user tags images with, and one the k3s registries.yaml mirrors.
OCIR_HOST="iad.ocir.localhost.floci.io:4599"
SOURCE_IMAGE_TAG="1.36"
# Every curl in this file fails within a deadline, so a registry that accepts a connection
# and then stalls cannot hold the suite until the CI job's own timeout.
CURL_LIMITS=(--connect-timeout 15 --max-time 120)

# ocir_curl <curl args...>: curl against the OCIR data plane. *.localhost.floci.io names
# 127.0.0.1, which in this container is this container, so the connection goes to floci-oci
# while the URL, and so the Host header the data plane routes on, keeps the OCIR host.
ocir_curl() {
    local emulator="${ENDPOINT#*://}"
    curl -fsS "${CURL_LIMITS[@]}" --connect-to "$OCIR_HOST:$emulator" -H "Authorization: Bearer compat" "$@"
}

# copy_busybox_to_ocir <namespace/repository> <tag> <architecture>: copies busybox for one
# architecture from Docker Hub into the local OCIR over the Registry HTTP API V2 (blob uploads,
# then the manifest), the calls docker push makes. The suite image has no Docker engine.
copy_busybox_to_ocir() {
    local target="$1" tag="$2" arch="$3" work="$BATS_FILE_TMPDIR/busybox" hub token digest media_type
    mkdir -p "$work"
    hub="https://registry-1.docker.io/v2/library/busybox"
    token="$(curl -fsS "${CURL_LIMITS[@]}" "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/busybox:pull" \
        | jq -r '.token')" || return 1
    curl -fsSL "${CURL_LIMITS[@]}" -H "Authorization: Bearer $token" \
        -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json" \
        "$hub/manifests/$SOURCE_IMAGE_TAG" -o "$work/index.json" || return 1
    digest="$(jq -r --arg arch "$arch" \
        '[.manifests[] | select(.platform.os == "linux" and .platform.architecture == $arch)][0].digest' \
        "$work/index.json")"
    [ -n "$digest" ] && [ "$digest" != "null" ] || { echo "busybox has no linux/$arch image" >&2; return 1; }
    curl -fsSL "${CURL_LIMITS[@]}" -H "Authorization: Bearer $token" \
        -H "Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json" \
        "$hub/manifests/$digest" -o "$work/manifest.json" || return 1
    media_type="$(jq -r '.mediaType' "$work/manifest.json")"

    local blob location
    for blob in $(jq -r '.config.digest, .layers[].digest' "$work/manifest.json"); do
        curl -fsSL "${CURL_LIMITS[@]}" -H "Authorization: Bearer $token" "$hub/blobs/$blob" -o "$work/blob" || return 1
        location="$(ocir_curl -X POST -o /dev/null -D - "http://$OCIR_HOST/v2/$target/blobs/uploads/" \
            | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')"
        [ -n "$location" ] || { echo "no upload session for $blob" >&2; return 1; }
        case "$location" in
            http*) ;;
            *) location="http://$OCIR_HOST$location" ;;
        esac
        case "$location" in
            *\?*) location="$location&digest=$blob" ;;
            *) location="$location?digest=$blob" ;;
        esac
        ocir_curl -X PUT -H "Content-Type: application/octet-stream" --data-binary "@$work/blob" \
            -o /dev/null "$location" || return 1
    done
    ocir_curl -X PUT -H "Content-Type: $media_type" --data-binary "@$work/manifest.json" -o /dev/null \
        "http://$OCIR_HOST/v2/$target/manifests/$tag"
}

@test "ce: cluster create is accepted" {
    run oci_cmd ce cluster create --compartment-id "$TENANCY" --name "$CLUSTER_NAME" \
        --vcn-id "$(cat "$BATS_FILE_TMPDIR/vcn_id")" --kubernetes-version "v1.30.1"
    [ "$status" -eq 0 ]
}

@test "ce: cluster list resolves the cluster and it becomes ACTIVE" {
    run oci_cmd ce cluster list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local cluster_id
    cluster_id="$(json_get ".data[] | select(.name == \"$CLUSTER_NAME\") | .id")"
    [ -n "$cluster_id" ]
    echo "$cluster_id" > "$BATS_FILE_TMPDIR/cluster_id"

    # Real mode pulls the k3s image on first use; be generous. A cluster that
    # never activates is a failure, not a skip: mock mode activates instantly
    # and in real mode CI mounts docker.sock, so there is no legitimate skip case.
    wait_for_state ACTIVE '.data."lifecycle-state"' 180 ce cluster get --cluster-id "$cluster_id"
}

@test "ce: work-request list shows the cluster creation" {
    run oci_cmd ce work-request list --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    [ "$(json_get '.data | map(select(."operation-type" == "CLUSTER_CREATE")) | length')" -ge 1 ]
}

@test "ce: create-kubeconfig returns the token version 2.0.0 exec kubeconfig" {
    run oci_json ce cluster create-kubeconfig --cluster-id "$CLUSTER_ID" --file -
    [ "$status" -eq 0 ]
    [[ "$output" == *apiVersion* ]]
    [[ "$output" == *"command: oci"* ]]
    [[ "$output" == *generate-token* ]]
    [[ "$output" == *"$CLUSTER_ID"* ]]
    [[ "$output" == *certificate-authority-data* ]]
}

@test "ce: generate-token mints the ExecCredential the kubeconfig runs" {
    # Signs locally against the real containerengine host; no request reaches the emulator.
    run oci_json ce cluster generate-token --cluster-id "$CLUSTER_ID"
    [ "$status" -eq 0 ]
    [ "$(json_get '.kind')" = "ExecCredential" ]
    [ -n "$(json_get '.status.token')" ]
}

@test "ce: kubectl authenticates through the exec kubeconfig" {
    # The suite image installs kubectl; only a host-local run (make test-cli-compat) can lack it.
    command -v kubectl > /dev/null || skip "kubectl is not installed"
    local kubeconfig="$BATS_FILE_TMPDIR/kubeconfig"
    run oci_cmd ce cluster create-kubeconfig --cluster-id "$CLUSTER_ID" --file "$kubeconfig"
    [ "$status" -eq 0 ]

    # kubectl runs `oci ce cluster generate-token` from the kubeconfig, and k3s sends that
    # token to floci-oci's webhook. The server is reachable here because the compat stack
    # runs floci-oci with endpoint-mode=network.
    run env KUBECONFIG="$kubeconfig" kubectl get namespaces -o name --request-timeout=60s
    [ "$status" -eq 0 ]
    [[ "$output" == *namespace/default* ]]
}

@test "ce: a pod runs an image pushed to the local OCIR" {
    command -v kubectl > /dev/null || skip "kubectl is not installed"
    # Artifacts in mock mode (no Docker for its registry sidecar) answers the data plane with 503.
    local probe
    probe="$(curl -s "${CURL_LIMITS[@]}" -o /dev/null -w '%{http_code}' --connect-to "$OCIR_HOST:${ENDPOINT#*://}" \
        -H "Authorization: Bearer compat" "http://$OCIR_HOST/v2/")"
    [ "$probe" != "503" ] || skip "the OCIR data plane needs the registry sidecar (artifacts mock mode)"
    local kubeconfig="$BATS_FILE_TMPDIR/kubeconfig"
    [ -f "$kubeconfig" ]

    run oci_cmd artifacts container configuration get --compartment-id "$TENANCY"
    [ "$status" -eq 0 ]
    local namespace repository
    namespace="$(json_get '.data.namespace')"
    repository="$(cat "$BATS_FILE_TMPDIR/ocir_repository")/busybox"
    run oci_cmd artifacts container repository create --compartment-id "$TENANCY" --display-name "$repository"
    [ "$status" -eq 0 ]
    json_get '.data.id' > "$BATS_FILE_TMPDIR/ocir_repository_id"

    # Pods run on the k3s node, so the image is the node's architecture, not this container's.
    # The cluster is ACTIVE once its API answers, which can be before the node has registered.
    local arch="" attempt=0
    until [ -n "$arch" ]; do
        arch="$(env KUBECONFIG="$kubeconfig" kubectl get nodes --request-timeout=30s \
            -o jsonpath='{.items[0].status.nodeInfo.architecture}' 2>/dev/null || true)"
        [ -n "$arch" ] && break
        attempt=$((attempt + 1))
        [ "$attempt" -le 60 ] || { echo "no k3s node registered" >&2; return 1; }
        sleep 2
    done
    copy_busybox_to_ocir "$namespace/$repository" 1 "$arch"

    # The controllers create the default service account a pod needs a little after the API answers.
    local i=0
    until env KUBECONFIG="$kubeconfig" kubectl get serviceaccount default --request-timeout=30s > /dev/null 2>&1; do
        i=$((i + 1))
        [ "$i" -le 60 ] || { echo "no default service account" >&2; return 1; }
        sleep 2
    done
    # k3s's containerd pulls the OCIR reference through the registries.yaml mirror floci-oci
    # wrote: with floci-oci in a container, that is the registry sidecar's IP on the shared network.
    run env KUBECONFIG="$kubeconfig" kubectl run ocir-pod --image="$OCIR_HOST/$namespace/$repository:1" \
        --restart=Never --request-timeout=60s --command -- sh -c "sleep 3600"
    [ "$status" -eq 0 ]
    run env KUBECONFIG="$kubeconfig" kubectl wait pod/ocir-pod --for=jsonpath='{.status.phase}'=Running \
        --timeout=180s --request-timeout=60s
    if [ "$status" -ne 0 ]; then
        env KUBECONFIG="$kubeconfig" kubectl describe pod ocir-pod --request-timeout=30s >&2 || true
    fi
    [ "$status" -eq 0 ]
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
