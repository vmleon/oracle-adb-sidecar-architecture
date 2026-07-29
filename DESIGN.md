# Design

Why the system is shaped the way it is. [`README.md`](README.md) says what it
is, [`DEPLOY.md`](DEPLOY.md) how to stand it up, and
[`REFERENCE.md`](REFERENCE.md) how to port each capability elsewhere.

---

## The thesis

Bring 26ai capabilities — Vector Search, Hybrid Vector Index, the Select AI
Agent framework, NL2SQL — to workloads that still run on older or non-Oracle
engines, without rehosting them. An Autonomous AI Database 26ai instance is
attached alongside the production databases as the **AI Data Gateway** and
reaches into them over database links.

The pattern itself works against any Oracle AI Database, including 19c, minus
the vector and RAG functionality. Only the demo requires 26ai, for the vector
and agent surfaces.

## Tiers

| Tier                            | Component                                 | Subnet                 | Notes                                                       |
| ------------------------------- | ----------------------------------------- | ---------------------- | ----------------------------------------------------------- |
| Frontend                        | Angular 21 served by nginx                | private (app)          | Reverse-proxies `/api/*` to the backend                     |
| Backend                         | Spring Boot 3.5 / Java 23                 | private (app)          | Holds 4 datasource beans (3 JDBC + Mongo)                   |
| Production workload (simulated) | Podman containers on one compute (4 OCPU) | private (db)           | Oracle Database Free 26ai, PostgreSQL 18, MongoDB 8         |
| AI Data Gateway                 | Autonomous AI Database 26ai (OLTP, ECPU)  | private endpoint, mTLS | Vector, Hybrid Vector Index, Select AI Agent framework      |
| Ops                             | Bastion compute (1 OCPU)                  | public                 | OCI Bastion service enabled; runs Liquibase for all engines |
| Edge                            | Flexible Load Balancer                    | public                 | `/api*` → backend, default → frontend                       |

The Oracle Database Free 26ai container stands in for an existing Oracle 19c
production database — Oracle does not ship a free-tier 19c binary, and the
federated path is version-agnostic.

## Decisions

**Four separate compute tiers, not a consolidated app host.** The tiers exist so
the demo can show a realistic private-subnet topology with a bastion, and so the
`direct` versus `federated` latency comparison on `/measurements` is measured
across a real network hop rather than within one host.

**Cloud-init runs Ansible locally on each instance.** Each instance pulls its own
artifact through a pre-authenticated request and runs its own playbook. There is
no SSH between instances during provisioning and no control node to keep alive,
so instances converge in parallel and a failure is isolated to one tier.

**The ops instance owns database provisioning.** It is the only host with
network reach to all four engines plus the ADB private endpoint, so Liquibase
for ADB, Oracle Free, and PostgreSQL and the `mongosh` init script all run from
there. `./manage.py reset` re-runs that playbook when a changeset changes.

**All ADB DDL is idempotent.** Every credential, database link, profile, agent,
and tool is created inside a DROP-if-exists guard. A dropped JDBC session
part-way through the changelog otherwise leaves a half-applied state, and the
retry fails on a duplicate-object error rather than converging. The ops playbook
additionally handles the stale `DATABASECHANGELOGLOCK` case by releasing the lock
and retrying.

**The gateway authenticates as itself.** Select AI profiles and the vector index
use `OCI$RESOURCE_PRINCIPAL`, so no API key or private key travels through
Terraform variables, cloud-init, or Liquibase parameters. The cost is a tenancy
dynamic group and policy that Terraform does not create — see
[`DEPLOY.md`](DEPLOY.md).

**Terraform stays on four providers.** `oci` plus `archive` (artifact zips),
`time` (PAR expiry and the ops settle window), and `random` (name suffixes). The
ADB wallet is exposed as a base64 output and uploaded to Object Storage rather
than written to local disk, which keeps a local-file provider out of the graph.

**The fraud graph lives on the gateway, not on production.** Production
`transactions` is a blockchain table, which cannot be altered to add the source
and destination key columns a property graph edge table needs. `local_accounts`
is mirrored from `V_BNK_ACCOUNTS` at deploy time and `transaction_edges` is
seeded with synthetic but account-ID-faithful patterns.

**Backend tests are skipped in `build`.** The Spring context-load test wires all
four datasource beans, which cannot succeed without running databases. Add
Testcontainers before dropping `-x test`.

## Datasource layout

| Engine                      | Tables                                                          | Reached directly | Reached via gateway |
| --------------------------- | --------------------------------------------------------------- | ---------------- | ------------------- |
| Oracle Database Free 26ai   | `customers`, `accounts`, `transactions`, `branches`             | yes              | yes                 |
| PostgreSQL 18               | `policies`, `rules`                                             | yes              | yes                 |
| MongoDB 8                   | `support_tickets`                                               | yes              | no                  |
| Autonomous AI Database 26ai | `V_BNK_*`, `banking_graph`, `QUERY_MEASUREMENTS`, agent catalog | —                | —                   |

MongoDB is reachable only directly: the ADB heterogeneous gateway reports
`object not found` for every collection. The `V_BNK_SUPPORT_TICKETS` changeset
and the Customer Care agent's access to it ship commented out, and the Mongo seed
already carries the documents, so three small edits flip it on if the gateway
behaviour changes.

## Operational constants

- **Boot volume on the databases compute is 200 GB.** Three container data
  directories share it (`/data/oracle`, `/data/postgres`, `/data/mongo`).
- **Container image tags** are set in
  `deploy/ansible/databases/roles/dbstack/templates/*.service.j2`:
  `container-registry.oracle.com/database/free:latest`,
  `docker.io/library/postgres:18-alpine`, `docker.io/library/mongo:8`. Pin the
  Oracle one to `23.26.0.0` if you don't want `latest` to drift.
- **Artifact PARs expire after 7 days** (`artifacts_par_expiration_in_days`).
  Every object in the `artifacts_*` bucket is Terraform-managed, so `destroy`
  removes the objects before the bucket. A manually uploaded object will make
  `destroy` fail with `BucketNotEmpty`.
- **Local backend development** uses `SPRING_PROFILES_ACTIVE=local` and
  `src/backend/src/main/resources/application-local.yaml`, which carries
  placeholder credentials to point at local containers and a wallet path.
