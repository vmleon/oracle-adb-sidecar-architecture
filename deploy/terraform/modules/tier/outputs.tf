output "id" {
  description = "OCID of the tier instance."
  value       = oci_core_instance.this.id
}

output "private_ip" {
  description = "Private IP other tiers reach this one on."
  value       = oci_core_instance.this.private_ip
}

output "public_ip" {
  description = "Public IP, empty for tiers in a private subnet."
  value       = oci_core_instance.this.public_ip
}
