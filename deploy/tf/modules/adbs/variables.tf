terraform {
  required_providers {
    oci = {
      source = "oracle/oci"
    }
  }
}

variable "project_name" {
  type = string
}

variable "deploy_id" {
  type = string
}

variable "compartment_ocid" {
  type = string
}

variable "admin_password" {
  type      = string
  sensitive = true
}

variable "autonomous_database_db_workload" {
  type    = string
  default = "OLTP"
}

variable "autonomous_database_db_license" {
  type    = string
  default = "BRING_YOUR_OWN_LICENSE"
}

variable "autonomous_database_db_whitelisted_ips" {
  type    = list(string)
  default = ["0.0.0.0/0"] # POC only — restrict in production
}

variable "autonomous_database_compute_count" {
  type    = number
  default = 2.0
}

variable "autonomous_database_data_storage_size_in_tbs" {
  type    = number
  default = 1
}
