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

variable "databases_fqdn" {
  type = string
}

variable "back_private_ip" {
  type = string
}

variable "front_private_ip" {
  type = string
}

variable "rag_bucket_name" {
  type = string
}

variable "rag_bucket_namespace" {
  type = string
}

variable "oci_user_ocid" {
  type      = string
  sensitive = true
}

variable "oci_tenancy_ocid" {
  type      = string
  sensitive = true
}

variable "oci_fingerprint" {
  type      = string
  sensitive = true
}

variable "oci_private_api_key" {
  type      = string
  sensitive = true
}

variable "oci_genai_region" {
  type    = string
  default = "us-chicago-1"
}

variable "oci_genai_compartment_id" {
  type      = string
  sensitive = true
}
