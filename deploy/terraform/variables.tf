variable "tenancy_ocid" {
  type = string
}

variable "region" {
  type = string
}

variable "config_file_profile" {
  type = string
}

variable "compartment_ocid" {
  type = string
}

variable "ssh_private_key_path" {
  type = string
}

variable "ssh_public_key" {
  type = string
}

variable "project_name" {
  type    = string
  default = "aidatagateway"
}

variable "instance_shape" {
  type    = string
  default = "VM.Standard.E4.Flex"
}

variable "adb_admin_password" {
  type      = string
  sensitive = true
}

variable "oracle_db_password" {
  type      = string
  sensitive = true
}

variable "postgres_db_password" {
  type      = string
  sensitive = true
}

variable "mongo_db_password" {
  type      = string
  sensitive = true
}

variable "ecpu_count" {
  type    = number
  default = 2
}

variable "storage_in_tbs" {
  type    = number
  default = 1
}

variable "databases_compute_ocpus" {
  type    = number
  default = 4
}

variable "databases_compute_memory_gb" {
  type    = number
  default = 32
}

variable "artifacts_par_expiration_in_days" {
  type    = number
  default = 7
}

variable "create_identity_resources" {
  description = "Create the dynamic group and policy the gateway's resource principal needs. Requires tenancy-level manage dynamic-groups and manage policies."
  type        = bool
  default     = true
}

variable "oci_genai_region" {
  type    = string
  default = "us-chicago-1"
}

# A compartment OCID is an identifier, not a secret — same treatment as
# compartment_ocid, and it has to stay readable in the policy-statement output.
variable "oci_genai_compartment_id" {
  type = string
}
