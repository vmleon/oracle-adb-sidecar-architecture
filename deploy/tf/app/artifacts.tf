data "archive_file" "ansible_ops_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../ansible/ops"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_ops_artifact.zip"
}

data "archive_file" "ansible_back_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../ansible/back"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_back_artifact.zip"
}

data "archive_file" "ansible_front_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../ansible/front"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_front_artifact.zip"
}

data "archive_file" "ansible_databases_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../ansible/databases"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/ansible_databases_artifact.zip"
}

data "archive_file" "back_jar_artifact" {
  type             = "zip"
  source_file      = "${path.module}/../../../src/backend/build/libs/backend-1.0.0.jar"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/back_artifact.zip"
}

data "archive_file" "front_artifact" {
  type             = "zip"
  source_dir       = "${path.module}/../../../src/frontend/dist/"
  output_file_mode = "0666"
  output_path      = "${path.module}/generated/front_artifact.zip"
}
