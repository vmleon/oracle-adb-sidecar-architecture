resource "oci_bastion_bastion" "ops_bastion" {
  # Uppercase to match what the service returns. Lowercase reads back as
  # "STANDARD" and shows up as a permanent diff that replaces the bastion on
  # every apply.
  bastion_type     = "STANDARD"
  compartment_id   = var.compartment_ocid
  target_subnet_id = var.subnet_id

  client_cidr_block_allow_list = [local.anywhere]
  name                         = "ops_${var.project_name}${var.deploy_id}"

  depends_on = [time_sleep.wait_for_instance]
}
