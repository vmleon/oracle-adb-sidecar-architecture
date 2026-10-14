locals {
  project_name = var.project_name
  deploy_id    = random_string.deploy_id.result
  # Fixed name for the bootstrap's systemd unit, log, and sentinel on every
  # tier; manage.py status points at the same paths.
  service_name     = "aidatagateway"
  adb_service_name = "${local.project_name}${local.deploy_id}"
  anywhere         = "0.0.0.0/0"
  tcp              = "6"

  public_subnet_cidr = "10.0.1.0/24"
  app_subnet_cidr    = "10.0.2.0/24"
  db_subnet_cidr     = "10.0.3.0/24"

  # Artifact name -> the directory zipped and published for it. Each entry
  # produces an archive, a bucket object, and a read-only PAR, so adding a
  # payload is one line here rather than three resource blocks.
  artifacts = {
    ansible_ops       = "${path.module}/../ansible/ops"
    ansible_frontend  = "${path.module}/../ansible/frontend"
    ansible_backend   = "${path.module}/../ansible/backend"
    ansible_databases = "${path.module}/../ansible/databases"
    database          = "${path.module}/../../database"
    backend_jar       = "${path.module}/../../src/backend/build/libs"
    frontend_dist     = "${path.module}/../../src/frontend/dist/frontend/browser"
  }
}
