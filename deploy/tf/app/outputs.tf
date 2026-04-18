output "deployment_name" {
  value = "${local.project_name}${local.deploy_id}"
}

output "lb_public_ip" {
  value = oci_core_public_ip.public_reserved_ip.ip_address
}

output "ops_public_ip" {
  value = module.ops.public_ip
}

output "back_private_ip" {
  value = module.back.private_ip
}

output "front_private_ip" {
  value = module.front.private_ip
}

output "databases_private_ip" {
  value = module.databases.private_ip
}

output "adb_db_name" {
  value = module.adbs.db_name
}

output "adb_admin_password" {
  value     = module.adbs.admin_password
  sensitive = true
}
