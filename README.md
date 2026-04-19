# Oracle ADB 26ai Sidecar Architecture

A **stepping-stone pattern** for bringing Autonomous Database 26ai capabilities — Vector Search, Hybrid Vector Index, Select AI, and the rest of the 26ai feature set — to workloads that still live in older Oracle, PostgreSQL, and MongoDB deployments.

In this architecture **ADB 26ai is the sidecar**. It does _not_ host the production data. The three Podman containers on the `databases` compute (Oracle Database Free 26ai, PostgreSQL 18, MongoDB 8) stand in for the existing production databases that a typical enterprise already runs. ADB 26ai is attached alongside them and reaches into each one via DB_LINK and federated queries, layering 26ai's modern AI/analytics capabilities on top — so teams can adopt Vector Search, Select AI, etc. without rehosting or rewriting the production systems first.

This unlocks an incremental modernization path: keep the existing production databases running unchanged, use the ADB 26ai sidecar to power new AI features against the same data, and migrate workloads into ADB 26ai on your own schedule.

The frontend ships two buttons against a small banking demo dataset seeded on first deploy: **accounts + transactions** in Oracle Free, **policies + rules** in PostgreSQL, **support_tickets** in MongoDB. The first button reads each database directly from the Spring Boot backend (smoke test that every datasource is reachable). The second asks the ADB 26ai sidecar to project the same data through DB_LINK views (`V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES`, `V_SUPPORT_TICKETS`) — proving the federated path end-to-end. Subsequent iterations build richer 26ai features (Vector Search, Select AI) on top of those same views.

## Architecture

| Tier                            | Component                                 | Subnet                   | Notes                                                                                |
| ------------------------------- | ----------------------------------------- | ------------------------ | ------------------------------------------------------------------------------------ |
| Frontend                        | Angular 21 served by nginx                | private (app)            | Reverse-proxies `/api/*` to back                                                     |
| Backend                         | Spring Boot 3.5 / Java 23                 | private (app)            | Holds 4 datasource beans (3 JDBC + Mongo)                                            |
| Production workload (simulated) | Podman containers on one compute (4 OCPU) | private (db)             | Oracle Free 26ai, Postgres 18, Mongo 8 — stand-ins for existing production databases |
| AI sidecar                      | Autonomous Database 26ai (OLTP, ECPU)     | OCI-managed, mTLS wallet | Vector Search, Hybrid Vector Index, Select AI — layered over prod via DB_LINK        |
| Ops                             | Bastion compute (1 OCPU)                  | public                   | OCI Bastion service enabled                                                          |
| Edge                            | Flexible Load Balancer                    | public                   | `/api*` → back, default → front                                                      |

```mermaid
flowchart TB
    internet([Internet])

    subgraph public [Public subnet 10.0.1.0/24]
        lb[Load Balancer<br/>flexible]
        ops[Ops<br/>bastion]
    end

    subgraph appnet [App subnet 10.0.2.0/24]
        front[Front<br/>nginx + Angular 21]
        back[Back<br/>Spring Boot 3.5 / Java 23]
    end

    subgraph dbnet [DB subnet 10.0.3.0/24 · production workload · simulated]
        subgraph databases [databases compute · podman]
            oracle[(Oracle Free 26ai<br/>:1521)]
            postgres[(Postgres 18<br/>:5432)]
            mongo[(Mongo 8<br/>:27017)]
        end
    end

    adb[(Autonomous Database 26ai<br/><b>AI sidecar</b> · Vector · Select AI)]

    internet --> lb
    internet --> ops
    lb -->|/| front
    lb -->|/api/*<br/>/actuator/*| back
    front -->|/api/*| back
    back -->|wallet| adb
    back --> oracle
    back --> postgres
    back --> mongo
    adb -.->|DB_LINK / federated queries<br/>future iterations| databases
```

## Layout

```
.
├── manage.py                       # Click CLI: setup → build → tf → info → clean
├── requirements.txt
├── deploy/
│   ├── tf/
│   │   ├── app/                   # main.tf, network.tf, lb.tf, storage.tf, artifacts.tf, ...
│   │   └── modules/
│   │       ├── adbs/              # Autonomous Database 26ai + wallet
│   │       ├── ops/               # bastion compute + OCI Bastion service
│   │       ├── front/             # nginx + Angular dist
│   │       ├── back/              # Spring Boot jar via systemd
│   │       └── databases/         # podman host with 3 systemd container units
│   └── ansible/
│       ├── ops/                   # roles/base — install jump-host tools
│       ├── front/                 # roles/app  — nginx + reverse proxy
│       ├── back/                  # roles/java — JDK 23 + jar + systemd
│       └── databases/             # roles/podman — 3 container services
├── src/
│   ├── backend/                   # Java 23 / Gradle / Spring Boot 3.5
│   └── frontend/                  # Angular 21
└── database/
    ├── liquibase/{adb,oracle,postgres}/   # YAML changelogs + .properties.j2
    └── mongo/init.js                       # mongosh schema seed
```

## Provisioning flow

> **First time only:** create the virtualenv and install Python dependencies.

```bash
python -m venv venv
```

```bash
pip install -r requirements.txt
```

Activate the virtualenv (every new shell):

```bash
source venv/bin/activate
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
```

```bash
python manage.py info
```

## Prerequisites

- OCI account with API key in `~/.oci/config`
- Python 3.9+ (`pip install -r requirements.txt`)
- Terraform 1.x
- Java 23 (Temurin or Oracle JDK)
- Node 22+, npm 10+
- Gradle (one-time, to bootstrap the wrapper: `cd src/backend && gradle wrapper --gradle-version 8.13`)
- An RSA SSH keypair (e.g. `~/.ssh/id_rsa` + `id_rsa.pub`)

## Verifying

After `terraform apply`, print the endpoints and SSH command:

```bash
python manage.py info
```

Open the load balancer IP in a browser, or hit the endpoints directly. Health check:

```bash
curl http://<lb_public_ip>/api/v1/health
```

Direct path — backend opens a connection to each production engine:

```bash
curl http://<lb_public_ip>/api/v1/demo
```

Federated path — backend queries ADB, which resolves DB_LINK views to the three engines:

```bash
curl http://<lb_public_ip>/api/v1/demo/via-sidecar
```

The demo endpoint returns the banking dataset grouped by engine:

```json
{
  "oracle":   { "accounts":        [...], "transactions": [...] },
  "postgres": { "policies":        [...], "rules":        [...] },
  "mongo":    { "support_tickets": [...] }
}
```

Both endpoints return the **same shape** — the only difference is the path
the data travels:

- `/demo` — backend opens a JDBC / Mongo connection to each production engine.
- `/demo/via-sidecar` — backend issues a single JDBC query to ADB, which
  resolves each view (`V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES`,
  `V_SUPPORT_TICKETS`) through its DB_LINKs to the three production engines.

## Cleanup

```bash
cd deploy/tf/app && terraform destroy
```

`manage.py clean` refuses if Terraform state still has resources:

```bash
cd ../../..
python manage.py clean
```

## Reference architectures

The project's structure was derived from three sibling repos in the workspace:

- `oracle-database-select-ai` — manage.py + Terraform + Ansible + Spring Boot + Angular layout
- `oracle-database-mcp-intro` — Liquibase invocation patterns + dual local/cloud lifecycle
- `oracle-database-java-agent-memory` — cloud-init + PAR-based artifact delivery

See [NOTES.md](NOTES.md) for what's intentionally deferred and the iteration roadmap.
