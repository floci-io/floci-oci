#!/usr/bin/env bash

# Shared setup for the OCI CLI compatibility suite.

_common_setup() {
    ENDPOINT="${FLOCI_OCI_ENDPOINT:-http://localhost:4599}"
    SUITE_DIR="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
    export TENANCY="ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
    export NAMESPACE="floci-local"
    export OCI_CLI_CONFIG_FILE="${OCI_CLI_CONFIG_FILE:-$SUITE_DIR/.oci/config}"
    export OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=True

    # The Docker image bakes the config at build time; host-local runs
    # (make test-cli-compat) generate it lazily on first use.
    if [ ! -f "$OCI_CLI_CONFIG_FILE" ]; then
        sh "$SUITE_DIR/gen-oci-cli-config.sh" "$(dirname "$OCI_CLI_CONFIG_FILE")" > /dev/null
    fi
}

# For bats `run`: folds stderr into stdout so error-path tests can match the
# service error code in $output. --config-file isolates from ~/.oci/config;
# --endpoint routes every client (and satisfies the CLI's endpoint-required
# gate for kms, streaming and functions-invoke); --no-retry fails fast.
oci_cmd() {
    oci --endpoint "$ENDPOINT" --config-file "$OCI_CLI_CONFIG_FILE" \
        --output json --no-retry --connection-timeout 5 --read-timeout 90 "$@" 2>&1
}

# For command substitution: stderr discarded so stdout stays pure JSON for jq.
oci_json() {
    oci --endpoint "$ENDPOINT" --config-file "$OCI_CLI_CONFIG_FILE" \
        --output json --no-retry --connection-timeout 5 --read-timeout 90 "$@" 2>/dev/null
}

# jq over the last `run` output.
json_get() {
    jq -r "$1" <<< "$output"
}

unique_name() {
    echo "$1-$(date +%s)-$RANDOM"
}

# wait_for_state <expected> <jq-filter> <timeout-seconds> <oci args...>
# Polls `oci_json <args>` until the filtered value equals <expected>.
wait_for_state() {
    local expected="$1" filter="$2" timeout="$3"
    shift 3
    local elapsed=0 state=""
    while [ "$elapsed" -lt "$timeout" ]; do
        state="$(oci_json "$@" | jq -r "$filter" 2>/dev/null || true)"
        [ "$state" = "$expected" ] && return 0
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo "timed out after ${timeout}s waiting for $expected (last state: $state)" >&2
    return 1
}
