output "id" {
  value = oci_database_autonomous_database.adb.id
}

output "admin_password" {
  value     = var.admin_password
  sensitive = true
}

output "db_name" {
  value = oci_database_autonomous_database.adb.db_name
}

output "display_name" {
  value = oci_database_autonomous_database.adb.display_name
}

output "wallet_zip_base64" {
  value = oci_database_autonomous_database_wallet.adb_wallet.content
}
