locals {
  cloud_init_content = templatefile("${path.module}/userdata/bootstrap.tftpl", {
    project_name               = var.project_name
    region_name                = var.region
    ansible_back_par_full_path = var.ansible_back_artifact_par_full_path
    back_jar_par_full_path     = var.back_jar_par_full_path
    wallet_par_full_path       = var.wallet_par_full_path
    adb_service_name           = var.adb_service_name
    adb_admin_password         = var.adb_admin_password
    oracle_db_password         = var.oracle_db_password
    postgres_db_password       = var.postgres_db_password
    mongo_db_password          = var.mongo_db_password
    databases_private_ip       = var.databases_private_ip
  })
}

data "oci_core_images" "ol9_images" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Oracle Linux"
  operating_system_version = "9"
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"

  filter {
    name   = "display_name"
    values = ["^Oracle-Linux-9\\.\\d+-\\d{4}\\.\\d{2}\\.\\d{2}-\\d+$"]
    regex  = true
  }
}

resource "oci_core_instance" "instance" {
  availability_domain = lookup(var.ads[0], "name")
  compartment_id      = var.compartment_ocid
  display_name        = "back${var.project_name}${var.deploy_id}"
  shape               = var.instance_shape

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(local.cloud_init_content)
  }

  shape_config {
    ocpus         = 1
    memory_in_gbs = 16
  }

  create_vnic_details {
    subnet_id                 = var.subnet_id
    assign_public_ip          = false
    display_name              = "back${var.project_name}${var.deploy_id}"
    assign_private_dns_record = true
    hostname_label            = "back${var.project_name}${var.deploy_id}"
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ol9_images.images[0].id
  }

  timeouts {
    create = "60m"
  }
}
