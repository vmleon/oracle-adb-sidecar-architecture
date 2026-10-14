# Deploy

Zero to a running Live AI Hub on OCI. Every command runs from the repository
root.

The stack is four compute instances (ops, frontend, backend, databases) plus an
Autonomous AI Database 26ai, a load balancer, and an Object Storage bucket.
Cloud-init pulls each instance's artifact through a pre-authenticated request
and runs Ansible **locally** on that instance, under a systemd unit that retries
until the playbook succeeds — there is no SSH between instances during
provisioning. The ops instance additionally runs Liquibase against all four
database engines.

---

## 1. Prerequisites

| Tool                      | Version | Notes                                                      |
| ------------------------- | ------- | ---------------------------------------------------------- |
| OCI tenancy + compartment | —       | See "Tenancy permissions" below                            |
| OCI CLI                   | current | `oci setup config` complete (`~/.oci/config`)              |
| Terraform                 | ≥ 1.7   | `oracle/oci` provider (pulled automatically)               |
| Java                      | 25      | Builds the backend jar                                     |
| Node / npm                | 24 / 11 | Builds the Angular dist                                    |
| Python                    | 3.11+   | Runs the CLI                                               |
| SSH keypair               | —       | `~/.ssh/id_ed25519.pub` or `~/.ssh/id_rsa.pub`             |

`setup` checks every tool and prints the install command for any that is
missing.

Install the CLI and its dependencies into a virtualenv.

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install -e ".[dev]"
```

Expect: `./manage.py --help` lists seven commands.

### Tenancy permissions

The Autonomous AI Database authenticates to OCI Generative AI and Object
Storage **as itself**, through a resource principal — no API key or private key
is ever placed in Terraform variables, on an instance, or in the database. That
needs a dynamic group matching the database and a policy granting it access.

**Terraform creates both** ([identity.tf](deploy/terraform/identity.tf)), in
the tenancy root — the only compartment guaranteed to be an ancestor of both the
GenAI compartment and the RAG bucket's compartment. So the identity you run
Terraform as needs, in addition to the usual compartment rights:

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
`provision` prints the statements an administrator has to apply. Until those
statements exist, the Select AI profiles are created but every call to them
fails to authenticate, and the policy-doc vector index cannot read its source
bucket.

IAM is global but only accepts writes in the tenancy **home region**, so the two
objects are created through a provider alias pinned to it while the rest of the
stack deploys into whatever region you picked. `setup` detects the home region
and writes it to `terraform.tfvars` as `home_region`; nothing else needs doing.

> Terraform creates the dynamic group through the legacy identity API, which
> targets the tenancy's **default** identity domain. If the deployment must live
> in a non-default domain, create the dynamic group there by hand and set
> `create_identity_resources = false`.

---

## 2. `setup`

Check the required tools, discover the tenancy, generate `.env`, and render
`terraform.tfvars`.

```bash
./manage.py setup
```

The prompts are fuzzy pickers — type to filter. `setup` reads the profiles
from `~/.oci/config`, lists the subscribed regions and notes which one is the
tenancy home region, probes each subscribed region for OCI Generative AI and
offers only the regions that answer as the GenAI region, then lists every
ACTIVE compartment in the tenancy as a `Parent/Child` path, with the tenancy
root as its own choice, for both the workload and the GenAI compartment. It
generates four Oracle-compliant passwords (ADB `ADMIN`, Oracle Free,
PostgreSQL, MongoDB) on the first run and reuses them on every later run.
`--yes` reuses whatever `.env` already holds and falls back to the first
profile, the home region, and the tenancy root.

Terraform authenticates as the `~/.oci/config` profile you picked — the tfvars
file carries no key material.

Expect: `.env` and `deploy/terraform/terraform.tfvars` (mode `0600`) exist,
and `.env` records `REGION`, `HOME_REGION` and `GENAI_REGION` as separate
values.

---

## 3. `build`

Build the Spring Boot jar and the Angular dist. Terraform uploads both to
Object Storage as deployment artifacts, so this has to run before `provision`.

```bash
./manage.py build
```

Expect: `src/backend/build/libs/backend-1.0.0.jar` and
`src/frontend/dist/frontend/browser/` exist, and the backend tests pass.

---

## 4. `provision`

Run `terraform init` and `terraform apply`, reviewing the plan at the prompt.
Pass `--yes` to auto-approve.

```bash
./manage.py provision
```

It creates the VCN, the Autonomous AI Database 26ai, four compute instances,
the load balancer, the Object Storage bucket, and a 7-day pre-authenticated
request per artifact. Cloud-init then provisions each instance. Expect 15–20
minutes for `terraform apply`, plus several more before the ops instance
finishes Liquibase.

Expect: `Apply complete`, followed by the `lb_url` and `ops_ssh` outputs.

---

## 5. `deploy`

Copy [database/](database/) to ops, then re-run the ops playbook over SSH,
which re-applies Liquibase against ADB, Oracle Free, and PostgreSQL and re-runs
the Mongo init script. Cloud-init runs this once on its own after `provision`;
run it by hand when a changeset changed, or when the first run on ops failed.
Every changeset is guarded (`CREATE OR REPLACE` / DROP-if-exists), so
re-applying is a no-op where nothing changed.

```bash
./manage.py deploy
```

Expect: `database provisioning re-applied`. If ops has not finished its first
bootstrap yet, `deploy` copies the files, says so and prints the log to follow;
the bootstrap retries every minute and picks the copied files up.

---

## 6. `status`

Print the load balancer URL, the ops SSH command, the private IPs, and the
backend's readiness snapshot.

```bash
./manage.py status
```

Expect: every component `UP` once the ops bootstrap has finished. Open the
load balancer URL in a browser and click through `/risk`, `/fraud`, `/app`,
`/ai-data-gateway`, `/agents`, and `/measurements`.

A tier is built when `/var/lib/aidatagateway/bootstrap.ok` exists on it. To
watch a tier that is not there yet, SSH to it and follow the bootstrap log.

```bash
sudo tail -f /var/log/aidatagateway-bootstrap.log
```

Expect: `Bootstrap complete: /var/lib/aidatagateway/bootstrap.ok`.

---

## 7. `reset`

Confirm, then run `deploy`. Pass `--yes` to skip the prompt for unattended
recovery.

```bash
./manage.py reset
```

Expect: the same output as `deploy`.

---

## 8. `teardown`

Run `terraform destroy` (after confirming) and delete the local build output
and Terraform state. `.env` and `terraform.tfvars` are **kept**, so the same
deployment can be rebuilt with `build` + `provision` without re-answering
`setup`.

```bash
./manage.py teardown
```

| Flag      | Effect                                                                |
| --------- | --------------------------------------------------------------------- |
| `--yes`   | Auto-approve the destroy                                              |
| `--purge` | Also delete `.env` and `terraform.tfvars` — next deploy needs `setup` |

Expect: `Destroy complete`, then the list of removed local paths.

---

## Full rebuild in one paste

The whole lifecycle unattended, reusing the `.env` from an earlier `setup`.

```bash
./manage.py setup --yes && ./manage.py build && ./manage.py provision --yes && ./manage.py status
```

Expect: the readiness snapshot, with components turning `UP` as the ops
bootstrap finishes.

Day-two operations — reaching each tier, tailing each log, probing each database
from the ops bastion — live in
[docs/troubleshooting.md](docs/troubleshooting.md).
