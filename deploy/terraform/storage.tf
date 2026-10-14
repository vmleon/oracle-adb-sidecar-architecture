resource "time_static" "deploy_time" {}

resource "oci_objectstorage_bucket" "banking_rag_docs" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
  name           = "banking-rag-docs"
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Disabled"
}

# Upload each policy markdown as a Terraform-managed object. This puts the
# objects in state, so `terraform destroy` removes them before the bucket
# (no 409-BucketNotEmpty), and content changes trigger re-upload on apply.
resource "oci_objectstorage_object" "banking_rag_docs" {
  for_each  = fileset("${path.module}/../../database/banking-policy-docs", "*.md")
  bucket    = oci_objectstorage_bucket.banking_rag_docs.name
  namespace = oci_objectstorage_bucket.banking_rag_docs.namespace
  object    = each.value
  source    = "${path.module}/../../database/banking-policy-docs/${each.value}"
}

resource "oci_objectstorage_bucket" "artifacts_bucket" {
  compartment_id = var.compartment_ocid
  name           = "artifacts_${local.project_name}${local.deploy_id}"
  namespace      = data.oci_objectstorage_namespace.objectstorage_namespace.namespace
}
