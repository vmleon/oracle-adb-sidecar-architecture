data "archive_file" "artifact" {
  for_each         = local.artifacts
  type             = "zip"
  source_dir       = each.value
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/${each.key}.zip"
}

# Terraform-managed objects rather than a CLI upload: they stay in state, so
# `destroy` empties the bucket before removing it instead of failing on a
# non-empty bucket, and the archive's hash rides along so a changed artifact
# updates on the next apply.
resource "oci_objectstorage_object" "artifact" {
  for_each    = local.artifacts
  bucket      = oci_objectstorage_bucket.artifacts_bucket.name
  namespace   = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object      = "${each.key}.zip"
  source      = data.archive_file.artifact[each.key].output_path
  content_md5 = data.archive_file.artifact[each.key].output_md5
}

# A read-only PAR is how an instance with no credentials fetches its payload.
resource "oci_objectstorage_preauthrequest" "artifact" {
  for_each     = local.artifacts
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "${each.key}_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.artifact[each.key].object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}

# The ADB wallet is a Terraform output, not a file on disk, so it is published
# as base64 text; the tier bootstrap decodes it.
resource "oci_objectstorage_object" "adb_wallet" {
  bucket    = oci_objectstorage_bucket.artifacts_bucket.name
  content   = module.adbs.wallet_zip_base64
  namespace = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  object    = "adb_wallet.zip.b64"
}

resource "oci_objectstorage_preauthrequest" "adb_wallet" {
  namespace    = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  bucket       = oci_objectstorage_bucket.artifacts_bucket.name
  name         = "adb_wallet_par"
  access_type  = "ObjectRead"
  object_name  = oci_objectstorage_object.adb_wallet.object
  time_expires = timeadd(time_static.deploy_time.rfc3339, "${var.artifacts_par_expiration_in_days * 24}h")
}
