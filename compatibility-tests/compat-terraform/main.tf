# Exercises the floci-oci emulator through the real oracle/oci provider:
# Identity (users, groups, memberships, policies) and Object Storage
# (namespace data source, bucket, object).

data "oci_objectstorage_namespace" "ns" {
  compartment_id = var.tenancy_ocid
}

resource "oci_identity_user" "tf_user" {
  compartment_id = var.tenancy_ocid
  name           = "tf-compat-user"
  description    = "Created by the terraform compatibility suite"
  email          = "tf-compat@example.com"
}

resource "oci_identity_group" "tf_group" {
  compartment_id = var.tenancy_ocid
  name           = "tf-compat-group"
  description    = "Created by the terraform compatibility suite"
}

resource "oci_identity_user_group_membership" "tf_membership" {
  user_id  = oci_identity_user.tf_user.id
  group_id = oci_identity_group.tf_group.id
}

resource "oci_identity_policy" "tf_policy" {
  compartment_id = var.tenancy_ocid
  name           = "tf-compat-policy"
  description    = "Created by the terraform compatibility suite"
  statements = [
    "Allow group tf-compat-group to read all-resources in tenancy",
  ]
}

resource "oci_objectstorage_bucket" "tf_bucket" {
  compartment_id = var.tenancy_ocid
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "tf-compat-bucket"
}

resource "oci_objectstorage_object" "tf_object" {
  namespace   = data.oci_objectstorage_namespace.ns.namespace
  bucket      = oci_objectstorage_bucket.tf_bucket.name
  object      = "terraform/hello.txt"
  content     = "hello from terraform"
  content_type = "text/plain"
}

output "namespace" {
  value = data.oci_objectstorage_namespace.ns.namespace
}

output "user_id" {
  value = oci_identity_user.tf_user.id
}

output "user_state" {
  value = oci_identity_user.tf_user.state
}

output "bucket_name" {
  value = oci_objectstorage_bucket.tf_bucket.name
}

output "object_md5" {
  value = oci_objectstorage_object.tf_object.content_md5
}

# ── Queue (work-request driven control plane) ────────────────────────────────

resource "oci_queue_queue" "tf_queue" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-queue"

  retention_in_seconds  = 3600
  visibility_in_seconds = 30
}

# ── KMS vault + key (endpoint indirection) ───────────────────────────────────

resource "oci_kms_vault" "tf_vault" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-vault"
  vault_type     = "DEFAULT"
}

resource "oci_kms_key" "tf_key" {
  compartment_id      = var.tenancy_ocid
  display_name        = "tf-compat-key"
  management_endpoint = oci_kms_vault.tf_vault.management_endpoint

  key_shape {
    algorithm = "AES"
    length    = 32
  }
}

# ── Vault secret ─────────────────────────────────────────────────────────────

resource "oci_vault_secret" "tf_secret" {
  compartment_id = var.tenancy_ocid
  vault_id       = oci_kms_vault.tf_vault.id
  key_id         = oci_kms_key.tf_key.id
  secret_name    = "tf-compat-secret"

  secret_content {
    content_type = "BASE64"
    content      = base64encode("terraform secret payload")
  }
}

# ── Streaming (dual body + work-request create) ──────────────────────────────

resource "oci_streaming_stream" "tf_stream" {
  name               = "tf-compat-stream"
  partitions         = 2
  compartment_id     = var.tenancy_ocid
  retention_in_hours = 24
}

# NOTE: oci_functions_application is deliberately NOT exercised here — the provider
# hardcodes a 5-minute post-destroy sleep (ExtraWaitPostDelete) outside httpreplay
# mode, which would dominate the suite's runtime. Functions coverage lives in the
# SDK suites and FunctionsDockerTest instead.

output "queue_messages_endpoint" {
  value = oci_queue_queue.tf_queue.messages_endpoint
}

output "vault_management_endpoint" {
  value = oci_kms_vault.tf_vault.management_endpoint
}

output "key_state" {
  value = oci_kms_key.tf_key.state
}

output "stream_messages_endpoint" {
  value = oci_streaming_stream.tf_stream.messages_endpoint
}

# ── Container Engine for Kubernetes (OKE) ───────────────────────────────────

resource "oci_containerengine_cluster" "tf_cluster" {
  compartment_id     = var.tenancy_ocid
  name               = "tf-compat-cluster"
  vcn_id             = "ocid1.vcn.oc1.iad.tfvcn"
  kubernetes_version = "v1.30.1"
}

resource "oci_containerengine_node_pool" "tf_node_pool" {
  cluster_id         = oci_containerengine_cluster.tf_cluster.id
  compartment_id     = var.tenancy_ocid
  name               = "tf-compat-nodepool"
  kubernetes_version = "v1.30.1"
  node_shape         = "VM.Standard.E4.Flex"
}

output "cluster_id" {
  value = oci_containerengine_cluster.tf_cluster.id
}
