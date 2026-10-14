# The Bastion agent plugin needs a few minutes on the instance before the
# service will accept a bastion targeting its subnet.
resource "time_sleep" "bastion_plugin" {
  count = var.create_bastion ? 1 : 0

  depends_on      = [oci_core_instance.this]
  create_duration = "3m"
}

resource "oci_bastion_bastion" "this" {
  count = var.create_bastion ? 1 : 0

  # Uppercase to match what the service returns. Lowercase reads back as
  # "STANDARD" and shows up as a permanent diff that replaces the bastion on
  # every apply.
  bastion_type     = "STANDARD"
  compartment_id   = var.compartment_ocid
  target_subnet_id = var.subnet_id

  client_cidr_block_allow_list = ["0.0.0.0/0"]
  name                         = "${var.tier_name}_${var.project_name}${var.deploy_id}"

  depends_on = [time_sleep.bastion_plugin]
}
