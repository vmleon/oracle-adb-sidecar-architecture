# Resource-principal identity for the AI Data Gateway.
#
# The Autonomous AI Database authenticates to OCI Generative AI and Object
# Storage as itself (OCI$RESOURCE_PRINCIPAL), so no API key or private key is
# stored anywhere in the deployment. That requires a dynamic group matching this
# database and a policy granting it what Select AI uses.
#
# Both objects live in the tenancy root — the only compartment guaranteed to be
# an ancestor of both the GenAI compartment and the RAG bucket's compartment, so
# a single policy can cover them wherever they sit. Creating them therefore needs
# tenancy-level `manage dynamic-groups` and `manage policies`. Without those
# rights, set `create_identity_resources = false` and have an administrator
# create the two objects; `terraform output resource_principal_statements`
# prints exactly what they need.

locals {
  identity_name = "${local.project_name}${local.deploy_id}"

  # A policy at the root addresses compartments by OCID. The root compartment is
  # the tenancy itself, which policy syntax spells `in tenancy` rather than
  # `in compartment id <tenancy-ocid>`.
  genai_scope = (
    var.oci_genai_compartment_id == var.tenancy_ocid
    ? "in tenancy"
    : "in compartment id ${var.oci_genai_compartment_id}"
  )
  bucket_scope = (
    var.compartment_ocid == var.tenancy_ocid
    ? "in tenancy"
    : "in compartment id ${var.compartment_ocid}"
  )

  resource_principal_statements = [
    "allow dynamic-group dg-${local.identity_name} to use generative-ai-family ${local.genai_scope}",
    "allow dynamic-group dg-${local.identity_name} to read objects ${local.bucket_scope} where target.bucket.name = '${oci_objectstorage_bucket.banking_rag_docs.name}'",
  ]
}

# Scoped to this one database by OCID rather than to every Autonomous Database
# in the compartment, so the grants below cannot widen as the compartment fills.
resource "oci_identity_dynamic_group" "adb_resource_principal" {
  count = var.create_identity_resources ? 1 : 0

  compartment_id = var.tenancy_ocid
  name           = "dg-${local.identity_name}"
  description    = "Autonomous AI Database acting as the AI Data Gateway for ${local.identity_name}"
  matching_rule  = "ALL {resource.type='autonomousdatabase', resource.id='${module.adbs.id}'}"
}

resource "oci_identity_policy" "adb_resource_principal" {
  count = var.create_identity_resources ? 1 : 0

  compartment_id = var.tenancy_ocid
  name           = "policy-${local.identity_name}"
  description    = "Select AI and RAG access for the ${local.identity_name} AI Data Gateway"
  statements     = local.resource_principal_statements

  depends_on = [oci_identity_dynamic_group.adb_resource_principal]
}
