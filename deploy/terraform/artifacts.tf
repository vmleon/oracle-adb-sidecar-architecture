data "archive_file" "ansible_ops_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../ansible/ops"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_ops_artifact.zip"
}

data "archive_file" "ansible_backend_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../ansible/backend"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_backend_artifact.zip"
}

data "archive_file" "ansible_frontend_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../ansible/frontend"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_frontend_artifact.zip"
}

data "archive_file" "ansible_databases_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../ansible/databases"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_databases_artifact.zip"
}

data "archive_file" "database_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../database"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/database_artifact.zip"
}

data "archive_file" "backend_jar_artifact" {
  type             = "zip"
  source_file      = "${path.module}/../../src/backend/build/libs/backend-1.0.0.jar"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/backend_artifact.zip"
}

data "archive_file" "frontend_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../src/frontend/dist/"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/frontend_artifact.zip"
}
