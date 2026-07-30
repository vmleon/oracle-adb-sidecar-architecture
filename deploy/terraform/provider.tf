terraform {
  required_version = ">= 1.5.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.35"
    }
    # archive builds the Ansible / app / database zips uploaded to Object Storage.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
    # time pins the PAR expiry clock and the post-create settle window on ops.
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
    # random suffixes resource names so repeat deploys never collide.
    random = {
      source  = "hashicorp/random"
      version = "~> 3"
    }
  }
}

provider "oci" {
  tenancy_ocid = var.tenancy_ocid
  region       = var.region
  # Authenticate as the ~/.oci/config profile chosen by `./manage.py setup`.
  config_file_profile = var.config_file_profile
}

# IAM is a global service whose writes are only accepted in the tenancy home
# region: creating a dynamic group or policy anywhere else fails with
# "403-NotAllowed, Please go to your home region". identity.tf uses this alias
# so the rest of the stack can still deploy into any region.
provider "oci" {
  alias               = "home"
  tenancy_ocid        = var.tenancy_ocid
  region              = var.home_region
  config_file_profile = var.config_file_profile
}
