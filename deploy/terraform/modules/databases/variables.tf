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

variable "region" {
  type = string
}

variable "compartment_ocid" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "ads" {
  type = list(any)
}

variable "instance_shape" {
  type = string
}

variable "ocpus" {
  type    = number
  default = 4
}

variable "memory_in_gbs" {
  type    = number
  default = 32
}

variable "ssh_public_key" {
  type = string
}

variable "ansible_databases_artifact_par_full_path" {
  type = string
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
