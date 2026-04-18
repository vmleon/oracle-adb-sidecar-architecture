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

variable "ssh_public_key" {
  type = string
}

variable "ansible_ops_artifact_par_full_path" {
  type = string
}

variable "wallet_par_full_path" {
  type = string
}

variable "database_par_full_path" {
  type = string
}

variable "adb_service_name" {
  type = string
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

variable "databases_private_ip" {
  type = string
}

variable "back_private_ip" {
  type = string
}

variable "front_private_ip" {
  type = string
}
