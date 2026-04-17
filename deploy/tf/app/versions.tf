terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.35"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5.1"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "2.4.2"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }
}
