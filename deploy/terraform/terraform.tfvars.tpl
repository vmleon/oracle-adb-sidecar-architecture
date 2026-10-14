config_file_profile = "${OCI_PROFILE}"
tenancy_ocid        = "${TENANCY_OCID}"
region              = "${REGION}"
home_region         = "${HOME_REGION}"
compartment_ocid    = "${COMPARTMENT_OCID}"
project_name        = "${PROJECT_NAME}"

ssh_public_key   = "${SSH_PUBLIC_KEY}"
ssh_ingress_cidr = "${SSH_INGRESS_CIDR}"

adb_admin_password   = "${ADB_ADMIN_PASSWORD}"
oracle_db_password   = "${ORACLE_DB_PASSWORD}"
postgres_db_password = "${POSTGRES_DB_PASSWORD}"
mongo_db_password    = "${MONGO_DB_PASSWORD}"

oci_genai_region         = "${GENAI_REGION}"
oci_genai_compartment_id = "${GENAI_COMPARTMENT_OCID}"

# The gateway's resource principal needs a dynamic group and a policy in the
# tenancy root. false skips both; an administrator then applies
# `terraform output resource_principal_statements` by hand.
create_identity_resources = ${CREATE_IDENTITY_RESOURCES}
