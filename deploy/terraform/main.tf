resource "random_string" "deploy_id" {
  length  = 2
  special = false
  upper   = false
}

module "adbs" {
  source = "./modules/adbs"

  project_name                                 = local.project_name
  deploy_id                                    = local.deploy_id
  compartment_ocid                             = var.compartment_ocid
  admin_password                               = var.adb_admin_password
  autonomous_database_compute_count            = var.ecpu_count
  autonomous_database_data_storage_size_in_tbs = var.storage_in_tbs
  subnet_id                                    = oci_core_subnet.db_subnet.id
  nsg_ids                                      = [oci_core_network_security_group.nsg_adb.id]
}

module "databases" {
  source = "./modules/databases"

  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid

  subnet_id      = oci_core_subnet.db_subnet.id
  instance_shape = var.instance_shape
  ssh_public_key = var.ssh_public_key
  ads            = data.oci_identity_availability_domains.ads.availability_domains
  ocpus          = var.databases_compute_ocpus
  memory_in_gbs  = var.databases_compute_memory_gb

  oracle_db_password   = var.oracle_db_password
  postgres_db_password = var.postgres_db_password
  mongo_db_password    = var.mongo_db_password

  ansible_databases_artifact_par_full_path = oci_objectstorage_preauthrequest.ansible_databases_artifact_par.full_path
}

module "backend" {
  source = "./modules/backend"

  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid

  subnet_id      = oci_core_subnet.app_subnet.id
  instance_shape = var.instance_shape
  ssh_public_key = var.ssh_public_key
  ads            = data.oci_identity_availability_domains.ads.availability_domains

  adb_service_name     = "${local.project_name}${local.deploy_id}"
  adb_admin_password   = var.adb_admin_password
  oracle_db_password   = var.oracle_db_password
  postgres_db_password = var.postgres_db_password
  mongo_db_password    = var.mongo_db_password
  databases_private_ip = module.databases.private_ip

  ansible_backend_artifact_par_full_path = oci_objectstorage_preauthrequest.ansible_backend_artifact_par.full_path
  backend_jar_par_full_path              = oci_objectstorage_preauthrequest.backend_jar_artifact_par.full_path
  wallet_par_full_path                   = oci_objectstorage_preauthrequest.adb_wallet_artifact_par.full_path
}

module "frontend" {
  source = "./modules/frontend"

  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid

  subnet_id      = oci_core_subnet.app_subnet.id
  instance_shape = var.instance_shape
  ssh_public_key = var.ssh_public_key
  ads            = data.oci_identity_availability_domains.ads.availability_domains

  backend_private_ip = module.backend.private_ip

  ansible_frontend_artifact_par_full_path = oci_objectstorage_preauthrequest.ansible_frontend_artifact_par.full_path
  frontend_artifact_par_full_path         = oci_objectstorage_preauthrequest.frontend_artifact_par.full_path
}

module "ops" {
  source = "./modules/ops"

  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid

  subnet_id      = oci_core_subnet.public_subnet.id
  instance_shape = var.instance_shape
  ssh_public_key = var.ssh_public_key
  ads            = data.oci_identity_availability_domains.ads.availability_domains

  ansible_ops_artifact_par_full_path = oci_objectstorage_preauthrequest.ansible_ops_artifact_par.full_path
  wallet_par_full_path               = oci_objectstorage_preauthrequest.adb_wallet_artifact_par.full_path
  database_par_full_path             = oci_objectstorage_preauthrequest.database_artifact_par.full_path
  adb_service_name                   = "${local.project_name}${local.deploy_id}"
  adb_admin_password                 = var.adb_admin_password
  oracle_db_password                 = var.oracle_db_password
  postgres_db_password               = var.postgres_db_password
  mongo_db_password                  = var.mongo_db_password
  databases_private_ip               = module.databases.private_ip
  databases_fqdn                     = "databases${local.deploy_id}.db.vcn${local.deploy_id}.oraclevcn.com"
  backend_private_ip                 = module.backend.private_ip
  frontend_private_ip                = module.frontend.private_ip
  rag_bucket_name                    = oci_objectstorage_bucket.banking_rag_docs.name
  rag_bucket_namespace               = oci_objectstorage_bucket.banking_rag_docs.namespace
  oci_genai_region                   = var.oci_genai_region
  oci_genai_compartment_id           = var.oci_genai_compartment_id
}
