terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 6.0.0"
    }
  }
}

variable "tenancy_ocid" {
  type    = string
  default = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
}

variable "user_ocid" {
  type    = string
  default = "ocid1.user.oc1..flociiacuser00000000000000000000000000000000000000000000000"
}

variable "private_key_path" {
  type    = string
  default = "./test_key.pem"
}

# The emulator endpoint itself is injected via the provider's per-client host
# overrides, e.g.:
#   TF_VAR_CLIENT_HOST_OVERRIDES="oci_identity.IdentityClient=http://localhost:4599;oci_object_storage.ObjectStorageClient=http://localhost:4599"
provider "oci" {
  tenancy_ocid         = var.tenancy_ocid
  user_ocid            = var.user_ocid
  fingerprint          = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
  private_key_path     = var.private_key_path
  region               = "us-ashburn-1"
  disable_auto_retries = true
}
