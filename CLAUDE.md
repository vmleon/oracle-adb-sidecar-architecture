# CLAUDE.md

Repo-specific notes for working in this codebase.

## What this is

A PoC deployment of the AI Data Gateway pattern: Autonomous AI Database 26ai
attached alongside three containerised production databases, with Select AI,
vector RAG, and an agent team layered on top. Read [`DESIGN.md`](DESIGN.md)
before changing architecture, [`REFERENCE.md`](REFERENCE.md) before changing
any Oracle DDL.

## Deploy flow

Everything goes through `./manage.py` (executable, shebang — not
`python manage.py`):

```
setup → build → provision → status        reset · clean
```

`provision` owns `terraform init` (with retry) and `apply`; `--yes`
auto-approves for unattended rebuilds. Don't add docs or scripts that tell the
reader to `cd deploy/terraform && terraform ...`.

`clean` keeps `.env` and `terraform.tfvars` so a rebuild doesn't need the
interactive `setup`; `--purge` drops them, `--local-only` skips the destroy.

## Conventions

- **Tier words are `ops`, `frontend`, `backend`, `databases`** — everywhere:
  Terraform modules, Ansible playbook directories, variable names, inventory
  groups, cloud-init paths. Never `front` / `back`.
- **Ansible roles are named for what they build**: `opstools`, `webstack`,
  `appstack`, `dbstack`. Jinja templates live in `roles/<role>/templates/` and
  are referenced bare (`src: nginx.conf.j2`); static assets live in
  `roles/<role>/files/` and are also referenced bare.
- **Terraform is flat**: `deploy/terraform/` is the root module, `modules/`
  sits beneath it. `provider.tf` holds both the `terraform` and `provider`
  blocks; there is no `versions.tf`.
- **`.terraform.lock.hcl` is committed** for reproducible provider versions.
- **No key material in variables.** The gateway authenticates with
  `OCI$RESOURCE_PRINCIPAL`; Terraform authenticates with `config_file_profile`.
  Nothing should reintroduce `oci_private_api_key` / `user_ocid` / `fingerprint`
  into tfvars, cloud-init, or Liquibase parameters. The dynamic group and policy
  backing the resource principal are Terraform resources in `identity.tf`, in
  the tenancy root, gated on `create_identity_resources`.
- **All ADB DDL must be idempotent** — every credential, database link, profile,
  agent, tool, and team wrapped in a DROP-if-exists guard. A half-applied
  changelog otherwise cannot converge on retry.

## Documentation

Root docs are `README.md` (hub), `DEPLOY.md` (runbook), `DEMO.md` (narrative),
`REFERENCE.md` (portable DDL guide), `DESIGN.md` (decisions), `BACKLOG.md`
(unbuilt). Deep dives go in `docs/` with kebab-case filenames and an entry in
`docs/README.md`.

Write every doc in the present tense, describing the system as it is now. No
changelogs, no "previously", no status markers.

## Verifying a change

```bash
terraform -chdir=deploy/terraform fmt -recursive
terraform -chdir=deploy/terraform validate
ansible-playbook --syntax-check -i localhost, deploy/ansible/<tier>/server.yaml
```
