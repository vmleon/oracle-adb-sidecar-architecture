# CLAUDE.md

Repo-specific notes for working in this codebase.

## What this is

A PoC deployment of the AI Data Gateway pattern: Autonomous AI Database 26ai
attached alongside three containerised production databases, with Select AI,
vector RAG, and an agent team layered on top.

It is a proof of concept, not a product: prefer simple, direct changes over
abstraction. Read [README.md](README.md) (overview), [DEPLOY.md](DEPLOY.md)
(runbook), [DEMO.md](DEMO.md) (walkthrough), [REFERENCE.md](REFERENCE.md)
(portable DDL guide) and [DESIGN.md](DESIGN.md) (decisions) before changing
architecture, and [REFERENCE.md](REFERENCE.md) before changing any Oracle DDL.

## Commands

The CLI and its dependencies install into a virtualenv.

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install -e ".[dev]"
```

Everything goes through `./manage.py` (executable, shebang — not
`python manage.py`). The lifecycle, each command idempotent and mapping to one
section of [DEPLOY.md](DEPLOY.md):

| Verb        | Owns                                                                      |
| ----------- | ------------------------------------------------------------------------- |
| `setup`     | check tools, discover the tenancy, generate `.env`, render `terraform.tfvars` |
| `build`     | compile the backend jar and the frontend dist                             |
| `provision` | `terraform init` and `apply`; cloud-init then runs every playbook         |
| `deploy`    | copy `database/` to ops and re-run the ops playbook: Liquibase on all four engines |
| `status`    | endpoints, the readiness of every database, where to look when a tier is not up |
| `reset`     | confirm, then `deploy`                                                    |
| `teardown`  | `terraform destroy` and local build output; keeps `.env` unless `--purge` |

Every verb that prompts takes `--yes`, so the whole flow runs unattended during
a recovery. Don't add docs or scripts that tell the reader to
`cd deploy/terraform && terraform ...`.

## CLI conventions

[manage.py](manage.py) is a single-file Typer CLI — no classes, no logging
framework, no retry or backoff. Every path, port, and name has exactly one home:
the constants block at the top of the file.

Shell out through `_run` and `_sh`, which echo the command with secrets
redacted. `setup` is the only writer of `.env`, using `python-dotenv`'s
`set_key`. `.env` is gitignored and has no committed example, because `setup`
generates it from scratch; passwords already in it are reused, never rotated.
Every other command calls `load_config()`, which exits with a pointer back to
`setup` when `.env` is missing.

`setup` checks every tool and version floor before its first prompt, and
discovers profile, regions and compartments through the `oci` CLI. The GenAI
region picker offers only regions where `REGION_PROBE` answers.

[manage.py](manage.py) renders
[terraform.tfvars.tpl](deploy/terraform/terraform.tfvars.tpl) with stdlib
`string.Template` (`${VAR}`), never Jinja.

Terraform authenticates with `config_file_profile`. The gateway authenticates
with `OCI$RESOURCE_PRINCIPAL`, backed by a dynamic group and policy that are
Terraform resources in [identity.tf](deploy/terraform/identity.tf), in the
tenancy root, gated on `create_identity_resources`. `user_ocid`,
`fingerprint`, and private key content never enter `terraform.tfvars`,
cloud-init, or Liquibase parameters.

## Conventions

- **Tier words are `ops`, `frontend`, `backend`, `databases`** — everywhere:
  the `modules/tier` calls in [main.tf](deploy/terraform/main.tf), Ansible
  playbook directories, inventory groups, bootstrap paths. Never `front` /
  `back`.
- **Ansible roles are named for what they build**: `opstools`, `webstack`,
  `appstack`, `dbstack`. Each tier's `server.yaml` applies that one role to that
  one host group. Jinja templates live in `roles/<role>/templates/` and are
  referenced bare (`src: nginx.conf.j2`); static assets live in
  `roles/<role>/files/` and are also referenced bare.
- **Terraform is flat**: `deploy/terraform/` is the root module, `modules/`
  (`tier`, `adbs`) sits beneath it. [provider.tf](deploy/terraform/provider.tf)
  holds both the `terraform` and `provider` blocks; there is no `versions.tf`.
- **`.terraform.lock.hcl` is committed** for reproducible provider versions.
- **One bootstrap for every tier**:
  [bootstrap.tftpl](deploy/terraform/modules/tier/userdata/bootstrap.tftpl)
  installs itself as a systemd unit that retries until the playbook succeeds,
  and writes `/var/lib/aidatagateway/bootstrap.ok` only then. Extra payloads
  and the wallet are module inputs, not per-tier scripts.

## Oracle conventions

All ADB DDL is idempotent — every credential, database link, profile, agent,
tool, and team wrapped in a DROP-if-exists guard — so a half-applied changelog
converges on retry.

SQLcl runbook steps are a connect/run/exit trio: `TERM=dumb sql -name <conn>`
(the `TERM=dumb` prefix is mandatory — SQLcl cannot build a terminal without
it), the SQL typed at the `SQL>` prompt, then `exit`. Steps that run on ops
open with an SSH preamble.

## Documentation

Root docs are [README.md](README.md) (hub), [DEPLOY.md](DEPLOY.md) (runbook),
[DEMO.md](DEMO.md) (narrative), [REFERENCE.md](REFERENCE.md) (portable DDL
guide), [DESIGN.md](DESIGN.md) (decisions), [BACKLOG.md](BACKLOG.md)
(unbuilt). Keep steps out of REFERENCE and concepts out of DEPLOY and DEMO.
Deep dives go in `docs/` with kebab-case filenames and an entry in
[docs/README.md](docs/README.md).

One runnable command per code block, with a sentence above it and no comments
inside it. End every verification step with a one-line `Expect:` stating the
success result. Link every file you name. Write in the present tense,
describing the system as it is now: no changelogs, no "previously", no status
markers.

## Tests

`tests/` covers pure functions only: argument builders, template rendering,
password rules. No container, no network, no mocking of `subprocess`.

## Verifying a change

Run the CLI tests.

```bash
pytest
```

Expect: all tests pass.

Check the Terraform formatting without rewriting files.

```bash
terraform -chdir=deploy/terraform fmt -check -recursive
```

Expect: no output.

Validate the Terraform configuration.

```bash
terraform -chdir=deploy/terraform validate
```

Expect: Success! The configuration is valid.

Parse every playbook without running it.

```bash
for t in ops frontend backend databases; do ansible-playbook --syntax-check -i localhost, deploy/ansible/$t/server.yaml; done
```

Expect: four playbook names printed, with no error.

Build both applications, which also runs the backend tests.

```bash
./manage.py build
```

Expect: the jar and dist paths printed.
