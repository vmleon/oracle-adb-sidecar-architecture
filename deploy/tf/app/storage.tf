resource "time_static" "deploy_time" {}

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
