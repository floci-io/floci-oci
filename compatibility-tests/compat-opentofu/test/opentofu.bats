#!/usr/bin/env bats

setup_file() {
    load 'test_helper/common-setup'
    _common_setup
}

setup() {
    load 'test_helper/common-setup'
    _common_setup
}

@test "init downloads the oracle/oci provider (opentofu)" {
    run "$TF_BIN" init -input=false -no-color
    [ "$status" -eq 0 ]
}

@test "apply creates identity and object storage resources" {
    run "$TF_BIN" apply -auto-approve -input=false -no-color
    [ "$status" -eq 0 ]
    [[ "$output" == *"Apply complete! Resources: 39 added"* ]]
}

@test "outputs reflect emulator state" {
    run "$TF_BIN" output -raw namespace
    [ "$status" -eq 0 ]
    [ "$output" = "floci-local" ]

    run "$TF_BIN" output -raw user_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw user_id
    [[ "$output" == ocid1.user.oc1..* ]]

    run "$TF_BIN" output -raw api_key_fingerprint
    [ "$output" = "bf:44:8c:8d:6c:12:10:7b:75:d9:32:50:fc:0a:14:e5" ]

    run "$TF_BIN" output -raw api_keys_listed
    [ "$output" = "1" ]

    run "$TF_BIN" output -raw object_md5
    [ -n "$output" ]

    run "$TF_BIN" output -raw bucket_versioning
    [ "$output" = "Enabled" ]

    run "$TF_BIN" output -raw object_version_id
    [ -n "$output" ]

    run "$TF_BIN" output -raw queue_messages_endpoint
    [ -n "$output" ]

    run "$TF_BIN" output -raw vault_management_endpoint
    # Host-only by design: the OCI SDKs reject endpoints containing a path, so every
    # vault is served from the single emulator host (see docs/services/kms-vault.md).
    [[ "$output" == http*://* ]]

    run "$TF_BIN" output -raw key_state
    [ "$output" = "ENABLED" ]

    run "$TF_BIN" output -raw stream_messages_endpoint
    [ -n "$output" ]

    run "$TF_BIN" output -raw organizations_read
    [ "$output" = "1" ]

    run "$TF_BIN" output -raw db_system_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw db_system_port
    [ "$output" -gt 0 ]

    run "$TF_BIN" output -raw cluster_type
    [ "$output" = "ENHANCED_CLUSTER" ]

    run "$TF_BIN" output -raw vcn_domain_name
    [ "$output" = "tfcompat.oraclevcn.com" ]

    run "$TF_BIN" output -raw api_subnet_domain_name
    [ "$output" = "api.tfcompat.oraclevcn.com" ]

    run "$TF_BIN" output -raw container_repository_namespace
    [ "$output" = "floci-local" ]

    run "$TF_BIN" output -raw rule_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw topic_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw subscription_state
    [ "$output" = "PENDING" ]

    run "$TF_BIN" output -raw alarm_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw log_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw db_system_ca_certificate
    [[ "$output" == "-----BEGIN CERTIFICATE-----"* ]]

    run "$TF_BIN" output -raw configuration_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw configuration_shape
    [ "$output" = "VM.Standard.E4.Flex" ]

    run "$TF_BIN" output -raw backup_state
    [ "$output" = "ACTIVE" ]

    run "$TF_BIN" output -raw backup_source_type
    [ "$output" = "MANUAL" ]
}

@test "object content is readable through the plain API" {
    run curl -sf "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}/n/floci-local/b/tf-compat-bucket/o/terraform%2Fhello.txt"
    [ "$status" -eq 0 ]
    [ "$output" = "hello from terraform" ]
}

@test "lifecycle policy is readable through the plain API" {
    run curl -sf "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}/n/floci-local/b/tf-compat-bucket/l"
    [ "$status" -eq 0 ]
    [[ "$output" == *'"name":"tf-compat-delete-tmp"'* ]]
    [[ "$output" == *'"target":"multipart-uploads"'* ]]
}

@test "plan after apply shows no drift" {
    run "$TF_BIN" plan -detailed-exitcode -input=false -no-color
    # Shown only when the test fails: the plan is the only place the drifting attribute is named.
    echo "$output"
    [ "$status" -eq 0 ]
}

@test "destroy removes every resource" {
    run "$TF_BIN" destroy -auto-approve -input=false -no-color
    [ "$status" -eq 0 ]
    [[ "$output" == *"Destroy complete! Resources: 39 destroyed"* ]]

    run curl -s -o /dev/null -w '%{http_code}' \
        "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}/n/floci-local/b/tf-compat-bucket"
    [ "$output" = "404" ]
}
