#!/usr/bin/env bats

ENDPOINT="${FLOCI_OCI_ENDPOINT:-http://localhost:4599}"
COMPARTMENT="ocid1.compartment.oc1..testcli"

@test "OCI CLI - Create Cluster" {
  run oci ce cluster create --compartment-id "$COMPARTMENT" --name "cli-cluster" --vcn-id "ocid1.vcn.oc1.iad.clivcn" --kubernetes-version "v1.30.1" --endpoint "$ENDPOINT"
  [ "$status" -eq 0 ]
}

@test "OCI CLI - List Clusters" {
  run oci ce cluster list --compartment-id "$COMPARTMENT" --endpoint "$ENDPOINT"
  [ "$status" -eq 0 ]
}
