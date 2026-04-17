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
