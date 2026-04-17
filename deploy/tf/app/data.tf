data "oci_objectstorage_namespace" "objectstorage_namespace" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_services" "all_services" {
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}
