# Deploying the ADB 26ai Sidecar demo

End-to-end provisioning, day-one verification, and cleanup. The README covers what the demo _is_; this file covers how to _stand it up_ on OCI.

## Prerequisites

- OCI account with API key in `~/.oci/config`
- Python 3.9+ (`pip install -r requirements.txt`)
- Terraform 1.x
- Java 23 (Temurin or Oracle JDK)
- Node 22+, npm 10+
- Gradle (one-time, to bootstrap the wrapper: `cd src/backend && gradle wrapper --gradle-version 8.13`)
- An RSA SSH keypair (e.g. `~/.ssh/id_rsa` + `id_rsa.pub`)

## Provisioning flow

> **First time only:** create the virtualenv and install Python dependencies.

```bash
python -m venv venv
```

Activate the virtualenv (every new shell):

```bash
source venv/bin/activate
```

```bash
pip install -r requirements.txt
```

Interactive OCI config (profile, region, compartment, SSH key). Generates an Oracle-compliant DB password. Writes `.env`.

```bash
python manage.py setup
```

Builds the Spring Boot jar (`./gradlew build -x test`) and the Angular dist (`npm install && npm run build`).

```bash
python manage.py build
```

Renders `deploy/tf/app/terraform.tfvars` from `.env`.

```bash
python manage.py tf
```

Provisions VCN, ADB 26ai, 4 computes, LB, Object Storage bucket, and 7-day pre-authenticated requests (PARs) for every artifact.

```bash
cd deploy/tf/app
terraform init
terraform plan -out=tfplan
```

```bash
terraform apply tfplan
```

Cloud-init on each instance pulls its artifact via PAR and runs Ansible **locally** (no SSH between instances).

Prints the LB public IP, ops SSH command, and the demo endpoint URL.

```bash
cd ../../..
python manage.py info
```

## Verifying

After `terraform apply`, print the endpoints and SSH command:

```bash
python manage.py info
```

Open the load balancer IP in a browser and click through `/risk`, `/app`, `/sidecar`, `/agents`, and `/measurements`. The backend health check, for quick sanity:

```bash
curl http://<lb_public_ip>/api/v1/health
```

## Cleanup

```bash
cd deploy/tf/app && terraform destroy
```

`manage.py clean` refuses if Terraform state still has resources:

```bash
cd ../../..
python manage.py clean
```
