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
  default = "adbsidecar"
}

variable "instance_shape" {
  type    = string
  default = "VM.Standard.E4.Flex"
}

variable "db_admin_password" {
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
