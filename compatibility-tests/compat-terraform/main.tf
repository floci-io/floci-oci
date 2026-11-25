# Exercises the floci-oci emulator through the real oracle/oci provider:
# Identity (users, groups, memberships, policies), Object Storage
# (namespace data source, bucket, object), Networking (VCN) and the services built on it.

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

resource "oci_identity_api_key" "tf_api_key" {
  user_id   = oci_identity_user.tf_user.id
  key_value = <<-EOT
    -----BEGIN PUBLIC KEY-----
    MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqmswy49RvVuMjNRWJxcS
    MTq64YSbd053nhblbqk27iG3wq4f2JiqnjdBChJoksFkei7kX/nMC7Efw2O6iIZC
    OgLDYYGgmGQ7A08mPSYdgBd98iX+P3clyg9Ytf6QUDQf5wC4Zhco6jSFmLiBm128
    R1BrYDCoQj99aBY1M6/zkymfwpF0YmdurFM51mj40IE27vpVfD+WnKUr6pwb7fWZ
    Inj9EOkDSytO+gA6btaxkvJraRV4QffR8T02GCKZDKaw2Bkltou3Mxj9gZN6+C9p
    sZfOpm70g1ZHH/tfXLGGmVecNGLegBvxieuR/7Vujdml5sPgHmvW6tJHrxd0Zi7w
    rQIDAQAB
    -----END PUBLIC KEY-----
  EOT
}

data "oci_identity_api_keys" "tf_api_keys" {
  user_id    = oci_identity_user.tf_user.id
  depends_on = [oci_identity_api_key.tf_api_key]
}

output "api_key_fingerprint" {
  value = oci_identity_api_key.tf_api_key.fingerprint
}

output "api_keys_listed" {
  value = length(data.oci_identity_api_keys.tf_api_keys.api_keys)
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
  versioning     = "Enabled"

  object_events_enabled = true
}

resource "oci_objectstorage_object" "tf_object" {
  namespace   = data.oci_objectstorage_namespace.ns.namespace
  bucket      = oci_objectstorage_bucket.tf_bucket.name
  object      = "terraform/hello.txt"
  content     = "hello from terraform"
  content_type = "text/plain"
  # The bucket is versioned: destroy must remove every version for the bucket delete to succeed.
  delete_all_object_versions = true
}

resource "oci_objectstorage_object_lifecycle_policy" "tf_lifecycle" {
  namespace = data.oci_objectstorage_namespace.ns.namespace
  bucket    = oci_objectstorage_bucket.tf_bucket.name

  # Long ages and a prefix no other resource writes under, so the policy never removes tf_object.
  rules {
    name        = "tf-compat-delete-tmp"
    action      = "DELETE"
    is_enabled  = true
    time_amount = 30
    time_unit   = "DAYS"
    target      = "objects"

    object_name_filter {
      inclusion_prefixes = ["tf-compat-tmp/"]
      exclusion_patterns = ["*.keep"]
    }
  }

  rules {
    name        = "tf-compat-abort-uploads"
    action      = "ABORT"
    is_enabled  = false
    time_amount = 7
    time_unit   = "DAYS"
    target      = "multipart-uploads"
  }
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

output "bucket_versioning" {
  value = oci_objectstorage_bucket.tf_bucket.versioning
}

output "object_version_id" {
  value = oci_objectstorage_object.tf_object.version_id
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

# ── Events ──────────────────────────────────────────────────────────────────

resource "oci_events_rule" "tf_rule" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-rule"
  is_enabled     = true
  condition = jsonencode({
    eventType = ["com.oraclecloud.objectstorage.createobject"]
    data = {
      additionalDetails = {
        bucketName = "tf-compat-bucket"
      }
    }
  })

  actions {
    action {
      action_type = "OSS"
      is_enabled  = true
      stream_id   = oci_streaming_stream.tf_stream.id
    }
  }
}

output "rule_state" {
  value = oci_events_rule.tf_rule.state
}

# ── Notifications ───────────────────────────────────────────────────────────

resource "oci_ons_notification_topic" "tf_topic" {
  compartment_id = var.tenancy_ocid
  name           = "tf-compat-topic"
  description    = "terraform compat topic"
}

resource "oci_ons_subscription" "tf_subscription" {
  compartment_id = var.tenancy_ocid
  topic_id       = oci_ons_notification_topic.tf_topic.id
  protocol       = "EMAIL"
  endpoint       = "tf-compat@example.com"
}

output "topic_state" {
  value = oci_ons_notification_topic.tf_topic.state
}

output "subscription_state" {
  value = oci_ons_subscription.tf_subscription.state
}

# ── Monitoring ──────────────────────────────────────────────────────────────

resource "oci_monitoring_alarm" "tf_alarm" {
  compartment_id        = var.tenancy_ocid
  metric_compartment_id = var.tenancy_ocid
  display_name          = "tf-compat-alarm"
  namespace             = "tf_compat"
  query                 = "Latency[1m]{host = \"web-1\"}.max() > 500"
  severity              = "CRITICAL"
  destinations          = [oci_ons_notification_topic.tf_topic.id]
  is_enabled            = true
  pending_duration      = "PT5M"
  body                  = "Latency is above 500 ms"
}

output "alarm_state" {
  value = oci_monitoring_alarm.tf_alarm.state
}

# ── Logging ─────────────────────────────────────────────────────────────────

resource "oci_logging_log_group" "tf_log_group" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-log-group"
  description    = "terraform compat log group"
}

resource "oci_logging_log" "tf_log" {
  log_group_id       = oci_logging_log_group.tf_log_group.id
  display_name       = "tf-compat-log"
  log_type           = "CUSTOM"
  retention_duration = 60
}

output "log_state" {
  value = oci_logging_log.tf_log.state
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

# ── Networking (VCN) ───────────────────────────────────────────────────────

data "oci_core_services" "all" {
  filter {
    name   = "name"
    values = ["All .* Services In Oracle Services Network"]
    regex  = true
  }
}

resource "oci_core_vcn" "tf_vcn" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-vcn"
  cidr_blocks    = ["10.0.0.0/16"]
  dns_label      = "tfcompat"
}

resource "oci_core_internet_gateway" "tf_igw" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-igw"
  enabled        = true
}

resource "oci_core_nat_gateway" "tf_nat" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-nat"
}

resource "oci_core_service_gateway" "tf_sgw" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-sgw"

  services {
    service_id = data.oci_core_services.all.services[0].id
  }
}

resource "oci_core_route_table" "tf_public_rt" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.tf_igw.id
  }
}

resource "oci_core_route_table" "tf_private_rt" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-private-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_nat_gateway.tf_nat.id
  }

  route_rules {
    destination       = data.oci_core_services.all.services[0].cidr_block
    destination_type  = "SERVICE_CIDR_BLOCK"
    network_entity_id = oci_core_service_gateway.tf_sgw.id
  }
}

resource "oci_core_security_list" "tf_sl" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-sl"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  ingress_security_rules {
    source   = "10.0.0.0/16"
    protocol = "6"

    tcp_options {
      min = 5432
      max = 5432
    }
  }
}

resource "oci_core_network_security_group" "tf_nsg" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-nsg"
}

resource "oci_core_network_security_group_security_rule" "tf_nsg_https" {
  network_security_group_id = oci_core_network_security_group.tf_nsg.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = "0.0.0.0/0"
  source_type               = "CIDR_BLOCK"

  tcp_options {
    destination_port_range {
      min = 443
      max = 443
    }
  }
}

resource "oci_core_dhcp_options" "tf_dhcp" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-dhcp"

  options {
    type        = "DomainNameServer"
    server_type = "VcnLocalPlusInternet"
  }
}

resource "oci_core_subnet" "tf_api_subnet" {
  compartment_id    = var.tenancy_ocid
  vcn_id            = oci_core_vcn.tf_vcn.id
  display_name      = "tf-compat-api-subnet"
  cidr_block        = "10.0.0.0/28"
  dns_label         = "api"
  route_table_id    = oci_core_route_table.tf_public_rt.id
  security_list_ids = [oci_core_security_list.tf_sl.id]
  dhcp_options_id   = oci_core_dhcp_options.tf_dhcp.id
}

resource "oci_core_subnet" "tf_lb_subnet" {
  compartment_id = var.tenancy_ocid
  vcn_id         = oci_core_vcn.tf_vcn.id
  display_name   = "tf-compat-lb-subnet"
  cidr_block     = "10.0.1.0/24"
  route_table_id = oci_core_route_table.tf_public_rt.id
}

resource "oci_core_subnet" "tf_private_subnet" {
  compartment_id             = var.tenancy_ocid
  vcn_id                     = oci_core_vcn.tf_vcn.id
  display_name               = "tf-compat-private-subnet"
  cidr_block                 = "10.0.2.0/24"
  route_table_id             = oci_core_route_table.tf_private_rt.id
  prohibit_public_ip_on_vnic = true
}

output "vcn_domain_name" {
  value = oci_core_vcn.tf_vcn.vcn_domain_name
}

output "api_subnet_domain_name" {
  value = oci_core_subnet.tf_api_subnet.subnet_domain_name
}

# ── Container Registry (OCIR) ───────────────────────────────────────────────

resource "oci_artifacts_container_configuration" "tf_container_configuration" {
  compartment_id                      = var.tenancy_ocid
  is_repository_created_on_first_push = true
}

resource "oci_artifacts_container_repository" "tf_repository" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat/app"
  is_public      = false
  is_immutable   = false

  readme {
    content = "# tf-compat"
    format  = "TEXT_MARKDOWN"
  }
}

output "container_repository_namespace" {
  value = oci_artifacts_container_repository.tf_repository.namespace
}

# ── Container Engine for Kubernetes (OKE) ───────────────────────────────────

# Every optional setting Terraform reads back is set, so the second plan in the bats suite
# shows any of them the emulator fails to return as sent.
resource "oci_containerengine_cluster" "tf_cluster" {
  compartment_id     = var.tenancy_ocid
  name               = "tf-compat-cluster"
  vcn_id             = oci_core_vcn.tf_vcn.id
  kubernetes_version = "v1.30.1"
  type               = "ENHANCED_CLUSTER"

  endpoint_config {
    subnet_id            = oci_core_subnet.tf_api_subnet.id
    is_public_ip_enabled = false
    nsg_ids              = [oci_core_network_security_group.tf_nsg.id]
  }

  cluster_pod_network_options {
    cni_type = "FLANNEL_OVERLAY"
  }

  options {
    service_lb_subnet_ids = [oci_core_subnet.tf_lb_subnet.id]

    kubernetes_network_config {
      pods_cidr     = "10.244.0.0/16"
      services_cidr = "10.96.0.0/16"
    }

    add_ons {
      is_kubernetes_dashboard_enabled = false
      is_tiller_enabled               = false
    }

    admission_controller_options {
      is_pod_security_policy_enabled = false
    }
  }

  image_policy_config {
    is_policy_enabled = false
  }

  freeform_tags = {
    "suite" = "compat"
  }
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

output "cluster_type" {
  value = oci_containerengine_cluster.tf_cluster.type
}

data "oci_tenantmanagercontrolplane_organizations" "orgs" {
  compartment_id = var.tenancy_ocid
}

output "organizations_read" {
  value = length(data.oci_tenantmanagercontrolplane_organizations.orgs.organization_collection)
}

# ── Database with PostgreSQL ────────────────────────────────────────────────

resource "oci_psql_db_system" "tf_db_system" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-db"
  db_version     = "15"
  shape          = "PostgreSQL.VM.Standard.E4.Flex.2.32GB"

  network_details {
    subnet_id = oci_core_subnet.tf_private_subnet.id
  }

  storage_details {
    is_regionally_durable = true
    system_type           = "OCI_OPTIMIZED_STORAGE"
  }

  credentials {
    username = "admin_user"
    password_details {
      password_type = "PLAIN_TEXT"
      password      = "BEstrO0ng_#11"
    }
  }
}

data "oci_psql_db_system_connection_detail" "tf_db_connection" {
  db_system_id = oci_psql_db_system.tf_db_system.id
}

output "db_system_state" {
  value = oci_psql_db_system.tf_db_system.state
}

output "db_system_port" {
  value = data.oci_psql_db_system_connection_detail.tf_db_connection.primary_db_endpoint[0].port
}

output "db_system_ca_certificate" {
  value = data.oci_psql_db_system_connection_detail.tf_db_connection.ca_certificate
}

resource "oci_psql_configuration" "tf_flexible_configuration" {
  compartment_id = var.tenancy_ocid
  display_name   = "tf-compat-flex-configuration"
  db_version     = "15"
  shape          = "VM.Standard.E4.Flex"

  db_configuration_overrides {
    items {
      config_key             = "effective_io_concurrency"
      overriden_config_value = "1"
    }
  }

  instance_memory_size_in_gbs = "0"
  instance_ocpu_count         = "0"
  is_flexible                 = true
}

output "configuration_state" {
  value = oci_psql_configuration.tf_flexible_configuration.state
}

output "configuration_shape" {
  value = oci_psql_configuration.tf_flexible_configuration.shape
}

resource "oci_psql_backup" "tf_db_backup" {
  compartment_id = var.tenancy_ocid
  db_system_id   = oci_psql_db_system.tf_db_system.id
  display_name   = "tf-db-backup"
}

output "backup_state" {
  value = oci_psql_backup.tf_db_backup.state
}

output "backup_source_type" {
  value = oci_psql_backup.tf_db_backup.source_type
}

# NoSQL Database: the provider acceptance test's DDL (identity and UUID columns, a shard key),
# an index on it and the tables data source. Both resources wait on work requests whose
# resource is entityType TABLE.
resource "oci_nosql_table" "tf_table" {
  compartment_id = var.tenancy_ocid
  name           = "tf_compat_table"
  ddl_statement  = "CREATE TABLE IF NOT EXISTS tf_compat_table(id INTEGER GENERATED ALWAYS AS IDENTITY, name STRING, age STRING, info JSON, guid STRING AS UUID, PRIMARY KEY(SHARD(id)))"

  table_limits {
    max_read_units     = 10
    max_write_units    = 10
    max_storage_in_gbs = 1
  }
}

resource "oci_nosql_index" "tf_index" {
  table_name_or_id = oci_nosql_table.tf_table.id
  name             = "tf_compat_name_idx"

  keys {
    column_name = "name"
  }
}

data "oci_nosql_tables" "tf_tables" {
  compartment_id = var.tenancy_ocid
  name           = oci_nosql_table.tf_table.name
}

output "nosql_table_state" {
  value = oci_nosql_table.tf_table.state
}

output "nosql_index_state" {
  value = oci_nosql_index.tf_index.state
}

output "nosql_tables_listed" {
  value = length(data.oci_nosql_tables.tf_tables.table_collection)
}
