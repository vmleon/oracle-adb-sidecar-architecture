# Implementation Notes

**Date**: 2026-04-17
**Architecture**: Autonomous Database 26ai attached as an **AI sidecar** to three production databases (Oracle Free 26ai / Postgres 18 / Mongo 8 running in podman on a single "databases" compute). Spring Boot backend, Angular frontend, ops bastion. The goal of the architecture is to bring 26ai features (Vector Search, Hybrid Vector Index, Select AI) to workloads that still run on older/other engines, via DB_LINK and federated queries — no rehost required. Apache Iceberg is also a target capability but requires a workload change — see gap #9.
**Iteration 1 goal**: end-to-end versions endpoint exposed by a single button, proving every datasource is wired.

## What this iteration ships

- `manage.py` (Click + Rich + InquirerPy + jinja2 + dotenv + OCI SDK) with `setup / build / tf / info / clean`.
- Terraform under `deploy/tf/` with five modules (adbs, ops, front, back, databases) and per-artifact pre-authenticated requests.
- Ansible under `deploy/ansible/` with one role per playbook, executed locally on each instance via cloud-init.
- Spring Boot 3.5 backend with 4 datasource beans (3 JDBC + Mongo) and `GET /api/v1/versions`.
- Angular 21 SPA with one route (`/versions`) — a button that calls the endpoint and renders four cards.
- Liquibase changelog scaffolding for ADB / Oracle / Postgres + `mongosh` init.js — each ships a trivial `deployment_marker` table/collection so the schema-deploy pipeline can be exercised without business logic.

## Key changes vs. the previous (deleted) plan

| Was                                                             | Now                                                                                                                     |
| --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Oracle 19c in one Podman container, on its own compute          | ADB 26ai as **AI sidecar** next to 3 podman containers (Oracle Free 26ai / Postgres 18 / Mongo 8) simulating production |
| Backend in Python Flask                                         | Spring Boot 3.5 / Java 23 (Gradle)                                                                                      |
| No frontend                                                     | Angular 21 served by nginx                                                                                              |
| 3 computes (ops/backend/db)                                     | 4 computes (ops/front/back/databases)                                                                                   |
| terraform/ + ansible/ at repo root                              | both under `deploy/`                                                                                                    |
| `stack.py`                                                      | `manage.py` (Click-based, 5 commands)                                                                                   |
| Manual Oracle Container Registry login required (NOTES TODO #2) | Oracle **Database Free** image — no registry auth needed                                                                |
| LB backend not registered (NOTES TODO #1)                       | LB backends explicit in `deploy/tf/app/lb.tf` for both front and back                                                   |
| No schema management                                            | Liquibase YAML changelogs + mongosh init.js scaffolded                                                                  |

## Known gaps & deferred items

### 1. ADB sidecar → production DBs (DB_LINK / DBMS_CLOUD_LINK)

**Status**: not yet wired. This is the core point of the architecture, deferred to iteration 2.
**Why**: ADB has a public endpoint with IP whitelist (`whitelisted_ips = ["0.0.0.0/0"]` in `modules/adbs/variables.tf` — POC default). For the ADB sidecar to reach the simulated-production compute via DB_LINK, you need either:

- An ADB **private endpoint** attached to the VCN (preferred), OR
- A public IP on the databases compute (not in the current design — it sits in a private subnet behind NAT).

**Path forward**: switch the ADB module to `nsg_ids` + private endpoint + `subnet_id = oci_core_subnet.db_subnet.id` (or a dedicated subnet). Then add DB_LINK/DBMS_CLOUD_LINK changesets under `database/liquibase/adb/` targeting the Oracle, Postgres, and Mongo production databases so 26ai features can query them federated.

### 2. Liquibase invocation is manual

The changelogs and `liquibase.properties.j2` templates are present but `manage.py` does **not** invoke Liquibase yet. To run them once iteration 1 is up:

```bash
# ADB (after terraform apply, wallet is in deploy/tf/app/generated/wallet.zip)
cd database/liquibase/adb
liquibase --url=jdbc:oracle:thin:@<svc>_high?TNS_ADMIN=<wallet_dir> \
  --username=ADMIN --password=<pwd> --changelog-file=db.changelog-master.yaml update
```

A future `manage.py liquibase` command can render the .properties.j2 files and shell out, mirroring `oracle-database-mcp-intro`'s `cloud_deploy()` pattern.

### 3. Container image tags may need adjustment

Defaults in `deploy/ansible/databases/roles/podman/files/`:

- `container-registry.oracle.com/database/free:latest-26ai`
- `docker.io/library/postgres:18-alpine`
- `docker.io/library/mongo:8`

If a tag is unavailable in your registry/region, edit the `.service.j2` files. Oracle Free's tag scheme is `<release>-<flavor>` (e.g. `26ai-full`, `26ai-lite`).

### 4. Gradle wrapper not committed

`build.gradle` and `settings.gradle` ship; the wrapper jar does not. First-time bootstrap:

```bash
cd src/backend
gradle wrapper --gradle-version 8.13
```

After that, `manage.py build` uses `./gradlew build -x test`.

### 5. Tests are skipped in `manage.py build`

The Spring context-load test would attempt to wire all four datasource beans, which would fail without running databases. Iteration 1 ships a trivial smoke test instead. When you add Testcontainers later, drop `-x test`.

### 6. `application-local.yaml` has placeholder credentials

For local dev (`SPRING_PROFILES_ACTIVE=local`), edit `src/backend/src/main/resources/application-local.yaml` to point at your local Postgres/Mongo containers and your ADB wallet path.

### 7. Boot volume for the databases compute is 200 GB

Set in `deploy/tf/modules/databases/compute.tf` (`boot_volume_size_in_gbs = 200`). Three database containers share `/data/oracle`, `/data/postgres`, `/data/mongo`. Bump if you load real data.

### 8. Cleanup of Object Storage artifacts

`terraform destroy` removes the bucket including its objects. PARs expire after 7 days regardless (`artifacts_par_expiration_in_days`). Stale PARs are harmless but can be regenerated by re-running `terraform apply`.

### 9. Apache Iceberg support — pursue alongside the AI workload

**Status**: not enabled. We want it eventually, but it isn't available on the current workload.
**Why**: per Oracle's docs ([Workload Types](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/about-autonomous-database-workloads.html), [Iceberg announcement](https://blogs.oracle.com/datawarehousing/post/iceberg-tables-autonomous-database)) Apache Iceberg querying — Native Iceberg + Unified Metadata Catalog + Data Lake Accelerator + GoldenGate→Iceberg streaming — is a **Lakehouse-workload** capability (the evolution of Autonomous Data Warehouse). The ADB module currently provisions `db_workload = "OLTP"` (Transaction Processing) in `deploy/tf/modules/adbs/variables.tf` because the headline value of the sidecar is Vector Search + Select AI federated against production data, both of which work on OLTP. Iceberg does not.

**Path forward**: a few credible options, each a follow-up iteration on its own:

- **Switch to Lakehouse** (`db_workload = "DW"`). Single line. Loses fast-path OLTP characteristics — fine if the sidecar will only run analytical / federated / Iceberg workloads.
- **Add a second ADB instance** (`module "adbs_lake"` with `db_workload = "DW"`) alongside the OLTP one. Best of both: keep OLTP for Select AI / chat-style workloads, use the Lakehouse one for Iceberg + Data Lake Accelerator. Roughly doubles ADB cost.
- **Wait for Oracle to extend Iceberg to OLTP** (no public roadmap commitment on this — track the Iceberg query reference page for changes).

When we wire this up, also add Iceberg-specific Liquibase changesets under `database/liquibase/adb/` (DBMS_CLOUD.CREATE_EXTERNAL_TABLE pointing at the relevant Iceberg catalog) and a frontend page that runs a federated Iceberg query.

## Iteration roadmap

**Iteration 2 (likely next):**

- Wire `manage.py liquibase` for all four engines.
- Add the ADB private endpoint + DB_LINK changeset so the ADB sidecar can query the production Oracle container federated.
- A second frontend page that runs a federated query from ADB into the production Oracle.

**Iteration 3:**

- DBMS_CLOUD_LINK / heterogeneous DB_LINK from the ADB sidecar out to the production Postgres and Mongo.
- Add Select AI profile creation on ADB (carried over from `oracle-database-select-ai`) so Select AI can be run against federated production data — the core value proposition of the sidecar pattern.

**Iteration 4:**

- Replace the jump-host bastion with OCI Bastion sessions only (no public IP on ops).
- Move secrets to OCI Vault.
- TLS termination at the LB.

## Verification checklist (iteration 1)

- [ ] `python manage.py setup` writes `.env` with all 11 keys.
- [ ] `python manage.py build` produces `src/backend/build/libs/backend-1.0.0.jar` and `src/frontend/dist/frontend/browser/`.
- [ ] `python manage.py tf` writes `deploy/tf/app/terraform.tfvars`.
- [ ] `terraform plan` shows ~30 resources to create with no errors.
- [ ] `terraform apply` completes in ~15–20 minutes.
- [ ] `python manage.py info` prints the LB IP and ops SSH command.
- [ ] `curl http://<lb>/api/v1/health` returns `{"status":"UP"}`.
- [ ] `curl http://<lb>/api/v1/versions` returns four non-error strings (allow 5–10 minutes after `terraform apply` for the Oracle Free container to finish initializing).
- [ ] Browser at `http://<lb>/` renders the versions page; clicking the button populates four cards.

## Reference repos used as templates

- `oracle-database-select-ai` — overall structure, manage.py shape, Terraform module split, cloud-init + ansible-on-instance pattern, LB routing policy, Spring Boot + Angular layout.
- `oracle-database-mcp-intro` — Liquibase YAML changesets + `liquibase.properties.j2` templating + dual local/cloud lifecycle.
- `oracle-database-java-agent-memory` — PAR-based artifact delivery with base64 wallet.
