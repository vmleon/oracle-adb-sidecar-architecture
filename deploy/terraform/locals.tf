locals {
  project_name = var.project_name
  deploy_id    = random_string.deploy_id.result
  anywhere     = "0.0.0.0/0"
  tcp          = "6"

  public_subnet_cidr = "10.0.1.0/24"
  app_subnet_cidr    = "10.0.2.0/24"
  db_subnet_cidr     = "10.0.3.0/24"
}
