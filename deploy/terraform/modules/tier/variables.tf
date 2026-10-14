variable "compartment_ocid" {
  description = "Compartment the instance is created in."
  type        = string
}

variable "tier_name" {
  description = "One of ops, frontend, backend, databases."
  type        = string

  validation {
    condition     = contains(["ops", "frontend", "backend", "databases"], var.tier_name)
    error_message = "tier_name must be one of ops, frontend, backend, databases."
  }
}

variable "service_name" {
  description = "Prefix for the bootstrap's systemd unit, log, and sentinel paths."
  type        = string
}

variable "project_name" {
  description = "Project name prefixed to every resource."
  type        = string
}

variable "deploy_id" {
  description = "Random suffix keeping names distinct across deploys."
  type        = string
}

variable "region" {
  description = "Region identifier, used to reach the regional yum endpoint."
  type        = string
}

variable "ads" {
  description = "Availability domains from the tenancy data source."
  type        = list(any)
}

variable "subnet_id" {
  description = "Subnet the instance attaches to."
  type        = string
}

variable "assign_public_ip" {
  description = "True only for tiers in the public subnet."
  type        = bool
  default     = false
}

variable "ssh_public_key" {
  description = "Public key installed for the opc user."
  type        = string
}

variable "instance_shape" {
  description = "Compute shape."
  type        = string
}

variable "instance_ocpus" {
  description = "OCPUs for the flexible shape."
  type        = number
  default     = 1
}

variable "instance_memory_gbs" {
  description = "Memory for the flexible shape."
  type        = number
  default     = 16
}

variable "boot_volume_size_in_gbs" {
  description = "Boot volume size; null keeps the image default."
  type        = number
  default     = null
}

variable "create_bastion" {
  description = "Enable the Bastion agent plugin and create an OCI Bastion on the subnet (ops only)."
  type        = bool
  default     = false
}

variable "artifact_par_url" {
  description = "Read-only PAR of the tier's Ansible zip; unzipped to /home/opc/artifact."
  type        = string
  sensitive   = true
}

variable "payload_par_urls" {
  description = "Extra zips by name, each unzipped to /home/opc/<name>."
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "wallet_par_url" {
  description = "PAR of the base64-encoded ADB wallet zip; empty when the tier does not reach ADB."
  type        = string
  default     = ""
  sensitive   = true
}

variable "ansible_params" {
  description = "Values written to /home/opc/ansible_params.json for the playbook."
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "ansible_collections" {
  description = "Galaxy collections the playbook needs beyond ansible-core."
  type        = list(string)
  default     = []
}
