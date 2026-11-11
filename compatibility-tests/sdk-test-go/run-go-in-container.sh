#!/bin/sh
# Container entrypoint: waits for the emulator, runs the Go suite, and writes JUnit XML to
# /results (the CI contract).
set -eu

ENDPOINT="${FLOCI_OCI_ENDPOINT:-http://floci-oci:4599}"
export FLOCI_OCI_ENDPOINT="$ENDPOINT"

echo "Waiting for emulator at $ENDPOINT ..."
i=0
until curl -sf "$ENDPOINT/health" > /dev/null 2>&1; do
    i=$((i + 1))
    [ "$i" -gt 60 ] && echo "emulator did not become ready" && exit 1
    sleep 1
done

mkdir -p /results
exec gotestsum --junitfile /results/junit.xml --format standard-verbose -- -count=1 -timeout 15m ./...
