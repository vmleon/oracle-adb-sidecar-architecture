# Oracle ADB 26ai Sidecar Architecture

**Keep your current app. Keep your current databases and their lifecycle. Attach Autonomous Database 26ai as a sidecar, layer AI features on top, and consolidate datasources on your own schedule.**

This repo is a working implementation of the stepping-stone pattern. Three Podman containers on the `databases` compute (Oracle Database Free 26ai, PostgreSQL 18, MongoDB 8) stand in for the kind of production databases an enterprise already runs. ADB 26ai is attached alongside them as the _sidecar_ — not the production store. It reaches into each engine via DB_LINK views, letting teams adopt Vector Search, Hybrid Vector Index, Select AI Agents, and the rest of 26ai's feature set over the same data without rehosting or rewriting.

The frontend ships four routes against a small banking demo dataset seeded on first deploy: **accounts + transactions** in Oracle Free, **policies + rules** in PostgreSQL, **support_tickets** in MongoDB.

- `/app` — **current app path.** The backend opens direct JDBC/Mongo connections to each production database. Proves every datasource is reachable; this is what your app already does today.
- `/sidecar` — **sidecar path.** The backend queries ADB; ADB resolves `V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES` over DB_LINK. Proves the federated path end-to-end. (Mongo via sidecar is deliberately disabled; see [docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md](docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md).)
- `/future` — **AI features.** Placeholder for Select AI Agents and other 26ai capabilities that land next.
- `/measurements` — **direct vs federated dashboard.** Wall-clock timing for every query, persisted asynchronously to ADB, with summary stats and box plots so the "federated is slower — by how much?" question has a data answer.

### `/app` — direct

![Current app screenshot](images/current-app.png)

Five cards, one per table (accounts, transactions, policies, rules, support_tickets), each with a wall-clock badge measured at the backend boundary. One click fans out into five parallel HTTP requests and each card fills in independently as its response returns.

### `/sidecar` — federated via ADB

![ADB sidecar screenshot](images/federated.png)

Same five cards, same dataset, but every query is now routed through the ADB sidecar and its DB_LINK views. The numbers next to each card show the extra latency the federated hop costs (compare with `/app` side by side). The `support_tickets` card is statically marked "not available" — the ADB heterogeneous MongoDB gateway is broken.

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
        front["Front · nginx + Angular 21<br/>/app · /sidecar · /future · /measurements"]
        back[Back<br/>Spring Boot 3.5 / Java 23]
    end

    subgraph dbnet [DB subnet 10.0.3.0/24 · production workload · simulated]
        subgraph databases [databases compute · podman]
            oracle[(Oracle Free 26ai<br/>:1521)]
            postgres[(Postgres 18<br/>:5432)]
            mongo[(Mongo 8<br/>:27017)]
        end
    end

    adb[(Autonomous Database 26ai<br/><b>AI sidecar</b> · Vector · Select AI<br/>query_measurements)]

    internet --> lb
    internet --> ops
    lb -->|/| front
    lb -->|/api/*<br/>/actuator/*| back
    front -->|/api/*| back
    back -->|wallet| adb
    back --> oracle
    back --> postgres
    back --> mongo
    adb -->|DB_LINK V_* views| oracle
    adb -->|DB_LINK V_* views| postgres
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

Open the load balancer IP in a browser and click through `/app`, `/sidecar`, and `/measurements`. The backend health check, for quick sanity:

```bash
curl http://<lb_public_ip>/api/v1/health
```

## Measuring the federated tax

Customers asked first about the ADB sidecar architecture typically ask: _how much does the federated path cost in latency?_ The `/measurements` route answers that directly.

**What is timed.** Exactly one JDBC/Mongo call per measurement, at the backend boundary (`System.nanoTime()` immediately before the call, again immediately after). HTTP handling, JSON serialization, and the measurement-row INSERT are all outside the timed region — the INSERT is fired asynchronously on a dedicated executor so it can't pollute the number.

**Where it lives.** Rows are persisted to `QUERY_MEASUREMENTS` in ADB. Each row carries `query_id`, `route` (`direct` | `federated`), `elapsed_ms`, `rows_returned`, `success`, `run_id`, and `measured_at`.

**How to read the dashboard.** The summary table shows `n`, mean, and p95 for both routes side by side per query, with a shaded `N` column marking the start of each section. The rightmost `Δ mean (ms)` column is `federated_mean − direct_mean` in absolute ms. Below the table, box plots show the distribution shape for each query. "Trim outliers (IQR)" is on by default and strips points outside `[Q1 − 1.5·IQR, Q3 + 1.5·IQR]` — without it, rare warm-up runs in the 5000-7000 ms range dominate the Y axis and the boxes collapse to flat lines. Toggle it off if you want to see those outliers.

![Measurements dashboard screenshot](images/measurements.png)

## Cleanup

```bash
cd deploy/tf/app && terraform destroy
```

`manage.py clean` refuses if Terraform state still has resources:

```bash
cd ../../..
python manage.py clean
```

## More info

- [docs/FEDERATED_QUERIES.md](docs/FEDERATED_QUERIES.md) — the deep dive on how ADB reaches Oracle Free / Postgres / Mongo through `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`, with the two hard requirements (DNS-resolvable hostname, Mongo data outside `admin`) and the `ORA-17008` mid-run recovery path.
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — day-two playbook for each tier (ops, databases, back, front) plus how to poke at each database from the ops bastion.
- [NOTES.md](NOTES.md) — what's intentionally deferred and the iteration roadmap.
