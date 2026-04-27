# Implementation Notes

**Date**: 2026-04-17
**Architecture**: Autonomous Database 26ai attached as an **AI sidecar** to three production databases (Oracle Free 26ai / Postgres 18 / Mongo 8 running in podman on a single "databases" compute). Spring Boot backend, Angular frontend, ops bastion. The goal of the architecture is to bring 26ai features (Vector Search, Hybrid Vector Index, Select AI) to workloads that still run on older/other engines, via DB_LINK and federated queries — no rehost required. Apache Iceberg is also a target capability but requires a workload change — see gap #9.
**Iteration 1 goal**: end-to-end banking-demo endpoint exposed by a single button, proving every datasource is wired — both directly and through the ADB sidecar via DB_LINK.

## What this iteration ships

- `manage.py` (Click + Rich + InquirerPy + jinja2 + dotenv + OCI SDK) with `setup / build / tf / info / clean`.
- Terraform under `deploy/tf/` with five modules (adbs, ops, front, back, databases) and per-artifact pre-authenticated requests.
- Ansible under `deploy/ansible/` with one role per playbook, executed locally on each instance via cloud-init.
- Spring Boot 3.5 backend with 4 datasource beans (3 JDBC + Mongo) exposing `GET /api/v1/query?table=&route=&runId=` (and `/api/v1/health`, `/api/v1/measurements*`).
- Angular 21 SPA with one route (`/demo`) — two buttons that call each endpoint and render a per-entity table per engine.
- Liquibase changelog scaffolding for ADB / Oracle / Postgres + `mongosh` init.js — each engine ships `deployment_marker` plus the banking demo data (`accounts`/`transactions` in Oracle, `policies`/`rules` in Postgres, `support_tickets` in Mongo).

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

### 1. ADB sidecar → production DBs (DB_LINK)

**Status**: wired in iteration 2.
**How**: ADB now runs on a **private endpoint** in `db_subnet` (`modules/adbs/db.tf` via `subnet_id` + `nsg_ids`). The `nsg_adb` NSG (`deploy/tf/app/network.tf`) allows ingress on 1522 from the app and public subnets, and the `db_seclist` now allows the ADB private endpoint (same-subnet source) to reach Oracle/Postgres/Mongo on 1521/5432/27017.

`database/liquibase/adb/002-db-links.yaml` creates three `DBMS_CLOUD.CREATE_CREDENTIAL` credentials, three `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK` entries (`ORAFREE_LINK`, `PG_LINK`, `MONGO_LINK`) and five banking views (`V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES`, `V_SUPPORT_TICKETS`) on ADB. The backend's `GET /api/v1/query?route=federated` projects through these views; the frontend's per-table cards offer a `direct` vs `federated` toggle that hits the same endpoint.

### 2. Liquibase invocation

Runs automatically on the ops bastion during cloud-init (`deploy/ansible/ops/roles/base/tasks/main.yaml`): installs Liquibase 4.29.2 + ojdbc11, renders `liquibase.properties.j2` from Terraform-supplied vars, waits for the three container DBs to accept TCP, then runs `liquibase update` against the ADB private endpoint. Idempotent; re-applying Terraform re-runs cloud-init, but Liquibase tracks applied changesets.

Output is captured to `/home/opc/ops/liquibase.log` on the ops host.

### 3. Container image tags may need adjustment

Defaults in `deploy/ansible/databases/roles/podman/files/`:

- `container-registry.oracle.com/database/free:latest` (currently 26ai; explicit version tag is `23.26.0.0` — Oracle's internal line for 26ai is 23.26.x)
- `docker.io/library/postgres:18-alpine`
- `docker.io/library/mongo:8`

If a tag is unavailable in your registry/region, edit the `.service.j2` files. Pin to `23.26.0.0` (or `23.26.0.0-lite` / `23.26.0.0-lite-arm64`) if you don't want `latest` to drift.

### 4. Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/` are committed. `manage.py build` runs `./gradlew build -x test` straight from a fresh clone — no `gradle` install required.

### 5. Tests are skipped in `manage.py build`

The Spring context-load test would attempt to wire all four datasource beans, which would fail without running databases. Iteration 1 ships a trivial smoke test instead. When you add Testcontainers later, drop `-x test`.

### 6. `application-local.yaml` has placeholder credentials

For local dev (`SPRING_PROFILES_ACTIVE=local`), edit `src/backend/src/main/resources/application-local.yaml` to point at your local Postgres/Mongo containers and your ADB wallet path.

### 7. Boot volume for the databases compute is 200 GB

Set in `deploy/tf/modules/databases/compute.tf` (`boot_volume_size_in_gbs = 200`). Three database containers share `/data/oracle`, `/data/postgres`, `/data/mongo`. Bump if you load real data.

### 8. Cleanup of Object Storage artifacts

Every object in `artifacts_*` is a Terraform-managed resource, so `terraform destroy` deletes the objects before the bucket and the bucket delete succeeds. If you manually upload an object to that bucket (e.g. ad-hoc debugging), `destroy` will fail with `BucketNotEmpty` — delete the stray object first or bump the OCI provider to a version that supports `force_delete`. PARs expire after 7 days regardless (`artifacts_par_expiration_in_days`).

### 9. Apache Iceberg support — pursue alongside the AI workload

**Status**: not enabled. We want it eventually, but it isn't available on the current workload.
**Why**: per Oracle's docs ([Workload Types](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/about-autonomous-database-workloads.html), [Iceberg announcement](https://blogs.oracle.com/datawarehousing/post/iceberg-tables-autonomous-database)) Apache Iceberg querying — Native Iceberg + Unified Metadata Catalog + Data Lake Accelerator + GoldenGate→Iceberg streaming — is a **Lakehouse-workload** capability (the evolution of Autonomous Data Warehouse). The ADB module currently provisions `db_workload = "OLTP"` (Transaction Processing) in `deploy/tf/modules/adbs/variables.tf` because the headline value of the sidecar is Vector Search + Select AI federated against production data, both of which work on OLTP. Iceberg does not.

**Path forward**: a few credible options, each a follow-up iteration on its own:

- **Switch to Lakehouse** (`db_workload = "DW"`). Single line. Loses fast-path OLTP characteristics — fine if the sidecar will only run analytical / federated / Iceberg workloads.
- **Add a second ADB instance** (`module "adbs_lake"` with `db_workload = "DW"`) alongside the OLTP one. Best of both: keep OLTP for Select AI / chat-style workloads, use the Lakehouse one for Iceberg + Data Lake Accelerator. Roughly doubles ADB cost.
- **Wait for Oracle to extend Iceberg to OLTP** (no public roadmap commitment on this — track the Iceberg query reference page for changes).

When we wire this up, also add Iceberg-specific Liquibase changesets under `database/liquibase/adb/` (DBMS_CLOUD.CREATE_EXTERNAL_TABLE pointing at the relevant Iceberg catalog) and a frontend page that runs a federated Iceberg query.

### 10. Heterogeneous connectivity support matrix

**Status**: not verified. ADB's Oracle-Managed Heterogeneous Connectivity has a version matrix per target engine (`db_type`). We currently pin **PostgreSQL 18** and **MongoDB 8** in `database/liquibase/adb/002-db-links.yaml` and `docs/FEDERATED_QUERIES.md` without checking whether those specific target versions are in the matrix published by the ADB release we land on.

**TODO**: after the first deploy, run on ADB

```sql
SELECT database_type, database_version, gateway_param_name, gateway_param_description
FROM   HETEROGENEOUS_CONNECTIVITY_INFO
WHERE  database_type IN ('postgres','mongodb');
```

If the listed `database_version` range does not cover 18 (postgres) or 8 (mongodb), drop the production container versions to a supported one and update the tags in `deploy/ansible/databases/roles/podman/files/*.service.j2` plus the prose in `docs/FEDERATED_QUERIES.md`. For the POC we accept the risk that a first deploy may surface a gateway-version mismatch that needs a downgrade.

## Iteration roadmap

**TODO — first line of work after the architecture is done:**

Ship at least one real 26ai capability against federated production data — otherwise the sidecar story is indistinguishable from Oracle Database Gateway. Concrete minimum:

- Create a Select AI profile on ADB (`DBMS_CLOUD_AI.CREATE_PROFILE`) that points at the federated banking views already created by `002-db-links.yaml` (`V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES`, `V_SUPPORT_TICKETS`).
- Add a third frontend page: free-text prompt → `/api/v1/select-ai` → `SELECT AI CHAT ...` on ADB → answer derived from live production-container data ("which customers hit any AML rule last week?").
- Follow up with a Vector Search example (embed `support_tickets.subject` into an ADB `VECTOR` column + similarity query over support narratives).

Until this ships, every reviewer will ask "so what does 26ai actually give me here?"

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
- [ ] `curl "http://<lb>/api/v1/query?table=accounts&route=direct&runId=smoke"` returns banking rows from Oracle Free (allow 5–10 minutes after `terraform apply` for the Oracle Free container to finish initializing). Repeat for `table=policies` (Postgres) and `table=support_tickets` (Mongo).
- [ ] Same calls with `route=federated` return the rows projected through ADB DB_LINK views (`support_tickets` is expected to 501 — Mongo heterogeneous link is unsupported, see `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`).
- [ ] Browser at `http://<lb>/` renders the versions page; clicking the button populates four cards.

## Reference repos used as templates

- `oracle-database-select-ai` — overall structure, manage.py shape, Terraform module split, cloud-init + ansible-on-instance pattern, LB routing policy, Spring Boot + Angular layout.
- `oracle-database-mcp-intro` — Liquibase YAML changesets + `liquibase.properties.j2` templating + dual local/cloud lifecycle.
- `oracle-database-java-agent-memory` — PAR-based artifact delivery with base64 wallet.
