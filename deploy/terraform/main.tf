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
  source = "./modules/tier"

  tier_name           = "databases"
  service_name        = local.service_name
  project_name        = local.project_name
  deploy_id           = local.deploy_id
  region              = var.region
  compartment_ocid    = var.compartment_ocid
  ads                 = data.oci_identity_availability_domains.ads.availability_domains
  subnet_id           = oci_core_subnet.db_subnet.id
  ssh_public_key      = var.ssh_public_key
  instance_shape      = var.instance_shape
  instance_ocpus      = var.databases_compute_ocpus
  instance_memory_gbs = var.databases_compute_memory_gb
  # Three container data directories share the boot volume.
  boot_volume_size_in_gbs = 200

  artifact_par_url    = oci_objectstorage_preauthrequest.artifact["ansible_databases"].full_path
  ansible_collections = ["ansible.posix"]
  ansible_params = {
    project_name         = local.project_name
    region_name          = var.region
    oracle_db_password   = var.oracle_db_password
    postgres_db_password = var.postgres_db_password
    mongo_db_password    = var.mongo_db_password
  }
}

module "backend" {
  source = "./modules/tier"

  tier_name        = "backend"
  service_name     = local.service_name
  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid
  ads              = data.oci_identity_availability_domains.ads.availability_domains
  subnet_id        = oci_core_subnet.app_subnet.id
  ssh_public_key   = var.ssh_public_key
  instance_shape   = var.instance_shape

  artifact_par_url    = oci_objectstorage_preauthrequest.artifact["ansible_backend"].full_path
  payload_par_urls    = { backend_jar = oci_objectstorage_preauthrequest.artifact["backend_jar"].full_path }
  wallet_par_url      = oci_objectstorage_preauthrequest.adb_wallet.full_path
  ansible_collections = ["ansible.posix"]
  ansible_params = {
    project_name         = local.project_name
    region_name          = var.region
    backend_jar          = "/home/opc/backend_jar/backend-1.0.0.jar"
    adb_service_name     = local.adb_service_name
    adb_admin_password   = var.adb_admin_password
    oracle_db_password   = var.oracle_db_password
    postgres_db_password = var.postgres_db_password
    mongo_db_password    = var.mongo_db_password
    databases_private_ip = module.databases.private_ip
  }
}

module "frontend" {
  source = "./modules/tier"

  tier_name        = "frontend"
  service_name     = local.service_name
  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid
  ads              = data.oci_identity_availability_domains.ads.availability_domains
  subnet_id        = oci_core_subnet.app_subnet.id
  ssh_public_key   = var.ssh_public_key
  instance_shape   = var.instance_shape

  artifact_par_url    = oci_objectstorage_preauthrequest.artifact["ansible_frontend"].full_path
  payload_par_urls    = { frontend_dist = oci_objectstorage_preauthrequest.artifact["frontend_dist"].full_path }
  ansible_collections = ["ansible.posix"]
  ansible_params = {
    project_name       = local.project_name
    region_name        = var.region
    frontend_dist      = "/home/opc/frontend_dist"
    backend_private_ip = module.backend.private_ip
  }
}

module "ops" {
  source = "./modules/tier"

  tier_name        = "ops"
  service_name     = local.service_name
  project_name     = local.project_name
  deploy_id        = local.deploy_id
  region           = var.region
  compartment_ocid = var.compartment_ocid
  ads              = data.oci_identity_availability_domains.ads.availability_domains
  subnet_id        = oci_core_subnet.public_subnet.id
  assign_public_ip = true
  create_bastion   = true
  ssh_public_key   = var.ssh_public_key
  instance_shape   = var.instance_shape

  artifact_par_url    = oci_objectstorage_preauthrequest.artifact["ansible_ops"].full_path
  payload_par_urls    = { database = oci_objectstorage_preauthrequest.artifact["database"].full_path }
  wallet_par_url      = oci_objectstorage_preauthrequest.adb_wallet.full_path
  ansible_collections = ["ansible.posix"]
  ansible_params = {
    project_name             = local.project_name
    region_name              = var.region
    adb_service_name         = local.adb_service_name
    adb_admin_password       = var.adb_admin_password
    oracle_db_password       = var.oracle_db_password
    postgres_db_password     = var.postgres_db_password
    mongo_db_password        = var.mongo_db_password
    databases_private_ip     = module.databases.private_ip
    databases_fqdn           = "databases${local.deploy_id}.db.vcn${local.deploy_id}.oraclevcn.com"
    backend_private_ip       = module.backend.private_ip
    frontend_private_ip      = module.frontend.private_ip
    wallet_dir               = "/home/opc/wallet"
    liquibase_adb_dir        = "/home/opc/database/liquibase/adb"
    liquibase_oracle_dir     = "/home/opc/database/liquibase/oracle"
    liquibase_postgres_dir   = "/home/opc/database/liquibase/postgres"
    mongo_init_js            = "/home/opc/database/mongo/init.js"
    rag_bucket_name          = oci_objectstorage_bucket.banking_rag_docs.name
    rag_bucket_namespace     = oci_objectstorage_bucket.banking_rag_docs.namespace
    oci_genai_region         = var.oci_genai_region
    oci_genai_compartment_id = var.oci_genai_compartment_id
  }
}
