# Deploy

Zero to a running Live AI Hub on OCI. Every command runs from the repository
root.

The stack is four compute instances (ops, frontend, backend, databases) plus an
Autonomous AI Database 26ai, a load balancer, and an Object Storage bucket.
Cloud-init pulls each instance's artifact through a pre-authenticated request
and runs Ansible **locally** on that instance — there is no SSH between
instances during provisioning. The ops instance additionally runs Liquibase
against all four database engines.

---

## 1. Prerequisites

| Tool                      | Version | Notes                                              |
| ------------------------- | ------- | -------------------------------------------------- |
| OCI tenancy + compartment | —       | See "Tenancy prerequisites" below                  |
| OCI CLI                   | current | `oci setup config` complete (`~/.oci/config`)      |
| Terraform                 | ≥ 1.5   | `oracle/oci` provider (pulled automatically)       |
| Java                      | 23      | Temurin or Oracle JDK — builds the Spring Boot jar |
| Node / npm                | 22 / 10 | Builds the Angular dist                            |
| Python                    | 3.10+   | `pip install -r requirements.txt`                  |
| SSH keypair               | RSA     | e.g. `~/.ssh/id_rsa` + `id_rsa.pub`                |

### Tenancy permissions

The Autonomous AI Database authenticates to OCI Generative AI and Object
Storage **as itself**, through a resource principal — no API key or private key
is ever placed in Terraform variables, on an instance, or in the database. That
needs a dynamic group matching the database and a policy granting it access.

**Terraform creates both** (`deploy/terraform/identity.tf`), in the tenancy
root — the only compartment guaranteed to be an ancestor of both the GenAI
compartment and the RAG bucket's compartment. So the identity you run Terraform
as needs, in addition to the usual compartment rights:

```
allow group <your-group> to manage dynamic-groups in tenancy
allow group <your-group> to manage policies in tenancy
```

The dynamic group is scoped to this one database by OCID, so the grants cannot
widen as the compartment fills:

```
ALL {resource.type='autonomousdatabase', resource.id='<this-adb-ocid>'}
```

**If you don't have tenancy IAM rights**, answer no when `setup` asks (it sets
`create_identity_resources = false`). Terraform then skips both objects and
`provision` prints the statements an administrator has to apply. You can also
read them any time:

```bash
terraform -chdir=deploy/terraform output resource_principal_statements
```

Until those statements exist, the Select AI profiles are created but every call
to them fails to authenticate, and the policy-doc vector index cannot read its
source bucket.

> Terraform creates the dynamic group through the legacy identity API, which
> targets the tenancy's **default** identity domain. If the deployment must live
> in a non-default domain, create the dynamic group there by hand and set
> `create_identity_resources = false`.

---

## 2. Install the Python dependencies

```bash
python -m venv venv && source venv/bin/activate
```

```bash
pip install -r requirements.txt
```

---

## 3. Configure

```bash
./manage.py setup
```

Interactive. It reads `~/.oci/config` and lets you pick the profile and region
from a list, and the compartment by **typing to fuzzy-search** the full,
paginated set (same for the GenAI compartment). It then:

- generates four Oracle-compliant passwords (ADB `ADMIN`, Oracle Free,
  PostgreSQL, MongoDB),
- writes them and your choices to `.env` (git-ignored),
- renders `deploy/terraform/terraform.tfvars` (mode `0600`, git-ignored).

Terraform authenticates as the `~/.oci/config` profile you picked — the tfvars
file carries no key material. If a picker shows a plain numbered list instead of
type-to-search, install the deps: `pip install -r requirements.txt` (InquirerPy).

---

## 4. Build the application artifacts

```bash
./manage.py build
```

Builds the Spring Boot jar (`./gradlew build -x test`) and the Angular dist
(`npm install && npm run build`). Terraform uploads both to Object Storage as
deployment artifacts, so this has to run before `provision`.

---

## 5. Provision

```bash
./manage.py provision
```

Runs `terraform init` (retrying on transient provider-registry failures) then
`terraform apply` from `deploy/terraform`, so you review the plan and confirm at
the prompt. It creates the VCN, the Autonomous AI Database 26ai, four compute
instances, the load balancer, the Object Storage bucket, and a 7-day
pre-authenticated request per artifact.

Cloud-init then provisions each instance. Expect 15–20 minutes for
`terraform apply`, plus several more before the ops instance finishes Liquibase.

---

## 6. Check the deployment

```bash
./manage.py status
```

Prints the load balancer IP, the demo endpoints, and the ops SSH command.
Open the load balancer IP in a browser and click through `/risk`, `/fraud`,
`/app`, `/ai-data-gateway`, `/agents`, and `/measurements`.

The backend health check, for a quick sanity read:

```bash
curl http://<lb_public_ip>/api/v1/health
```

Bootstrap on ops succeeded when `/var/lib/aidatagateway/bootstrap.ok` exists.
To watch it live, SSH to ops and tail cloud-init:

```bash
sudo tail -f /var/log/cloud-init-output.log
```

---

## 7. Re-run database provisioning

```bash
./manage.py reset
```

Re-runs the ops playbook over SSH, which re-applies Liquibase against ADB,
Oracle Free, and PostgreSQL and re-runs the Mongo init script. Every changeset
is guarded (`CREATE OR REPLACE` / DROP-if-exists), so re-applying is a no-op
where nothing changed. Use it when a changeset was edited, or when a first
deploy half-applied the ADB changelog.

---

## 8. Tear down

```bash
./manage.py clean
```

Runs `terraform destroy` (after confirming) and then deletes the local
artifacts — `.env`, `terraform.tfvars`, Terraform state, and the build output.
Pass `--yes` to skip both prompts.

---

Day-two operations — reaching each tier, tailing each log, probing each database
from the ops bastion — live in
[`docs/troubleshooting.md`](docs/troubleshooting.md).
