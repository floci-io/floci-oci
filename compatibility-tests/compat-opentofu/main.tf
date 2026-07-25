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
