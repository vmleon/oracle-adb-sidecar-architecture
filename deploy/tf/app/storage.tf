resource "time_static" "deploy_time" {}

resource "oci_objectstorage_bucket" "banking_rag_docs" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  name           = "banking-rag-docs"
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Disabled"
}

# OCI buckets refuse to delete if non-empty and the provider has no
# force_destroy. The Ansible role uploads the policy markdown docs into
# this bucket, so terraform destroy fails with 409-BucketNotEmpty unless
# we sweep it first. This null_resource depends on the bucket, so it is
# destroyed *before* the bucket and its destroy-time local-exec empties
# it. Requires `oci` CLI on the machine running terraform — set
# OCI_CLI_PATH in env if `oci` is not on PATH for terraform's shell.
resource "null_resource" "empty_banking_rag_docs_on_destroy" {
  triggers = {
    bucket_name = oci_objectstorage_bucket.banking_rag_docs.name
    namespace   = oci_objectstorage_bucket.banking_rag_docs.namespace
    profile     = var.config_file_profile
  }

  provisioner "local-exec" {
    when        = destroy
    interpreter = ["/bin/bash", "-c"]
    # No `|| true`: we want a hard failure if oci is missing or the
    # delete is rejected, otherwise the bucket destroy that follows
    # will 409 anyway and the operator has to debug blind.
    command = <<-EOT
      set -euo pipefail
      OCI_BIN="$${OCI_CLI_PATH:-oci}"
      command -v "$$OCI_BIN" >/dev/null 2>&1 \
        || { echo "ERROR: oci CLI not found in PATH; install oci-cli or set OCI_CLI_PATH" >&2; exit 1; }
      echo "Sweeping bucket ${self.triggers.bucket_name} (namespace ${self.triggers.namespace}) before delete..."
      "$$OCI_BIN" os object bulk-delete \
        --profile '${self.triggers.profile}' \
        --namespace-name '${self.triggers.namespace}' \
        --bucket-name '${self.triggers.bucket_name}' \
        --force
    EOT
  }
}

resource "oci_objectstorage_bucket" "artifacts_bucket" {
  compartment_id = var.compartment_ocid
  name           = "artifacts_${local.project_name}${local.deploy_id}"
  namespace      = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
}

# --- Ansible artifacts ---

resource "oci_objectstorage_object" "ansible_ops_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.ansible_ops_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "ansible_ops_artifact.zip"
  content_md5 = data.archive_file.ansible_ops_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "ansible_ops_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "ansible_ops_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.ansible_ops_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

resource "oci_objectstorage_object" "ansible_back_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.ansible_back_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "ansible_back_artifact.zip"
  content_md5 = data.archive_file.ansible_back_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "ansible_back_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "ansible_back_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.ansible_back_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

resource "oci_objectstorage_object" "ansible_front_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.ansible_front_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "ansible_front_artifact.zip"
  content_md5 = data.archive_file.ansible_front_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "ansible_front_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "ansible_front_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.ansible_front_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

resource "oci_objectstorage_object" "ansible_databases_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.ansible_databases_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "ansible_databases_artifact.zip"
  content_md5 = data.archive_file.ansible_databases_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "ansible_databases_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "ansible_databases_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.ansible_databases_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

# --- Database schema bundle (all four engines) ---

resource "oci_objectstorage_object" "database_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.database_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "database_artifact.zip"
  content_md5 = data.archive_file.database_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "database_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "database_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.database_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

# --- Application artifacts ---

resource "oci_objectstorage_object" "back_jar_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.back_jar_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "back_jar_artifact.zip"
  content_md5 = data.archive_file.back_jar_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "back_jar_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "back_jar_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.back_jar_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

resource "oci_objectstorage_object" "front_artifact_object" {
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  source      = data.archive_file.front_artifact.output_path
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "front_artifact.zip"
  content_md5 = data.archive_file.front_artifact.output_md5
}

resource "oci_objectstorage_preauthrequest" "front_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "front_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.front_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

# --- ADB wallet ---

resource "oci_objectstorage_object" "adb_wallet_artifact_object" {
  bucket    = oci_objectstorage_bucket.artifacts_bucket.name
  content   = module.adbs.wallet_zip_base64
  namespace = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object    = "adb_wallet_artifact.zip"
}

resource "oci_objectstorage_preauthrequest" "adb_wallet_artifact_par" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "adb_wallet_artifact_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.adb_wallet_artifact_object.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}
