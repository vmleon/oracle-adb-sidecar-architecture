# Oracle ADB 26ai Sidecar Architecture

![Sidecar architecture](images/sidecar.png)

**Keep your current app. Keep your current databases and their lifecycle. Attach Autonomous Database 26ai as a sidecar, layer AI features on top, and consolidate datasources on your own schedule.**

This repository is a working live demo of the **Oracle Select AI "AI Proxy Database" pattern** (also called _Select AI sidecar_), as described in the Oracle Database 26ai Select AI User's Guide ([Use Autonomous AI Database as an AI Proxy for Select AI](https://docs.oracle.com/en/database/oracle/oracle-database/26/selai/select-ai-sidecar-databases.html)). An ADB 26ai instance acts as the AI Proxy: production data stays in Oracle Free 26ai and PostgreSQL 18 containers, ADB reaches them via `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK` and exposes `V_BNK_*` views, and Select AI runs NL2SQL on top. The demo extends the documented NL2SQL pattern with a vector-RAG index and a 4-agent `DBMS_CLOUD_AI_AGENT.RUN_TEAM` investigation team — Select AI capabilities that compose with the AI Proxy pattern but are not covered on that specific docs page.

This repo is a working implementation of the stepping-stone pattern. Three Podman containers on the `databases` compute (Oracle Database Free 26ai, PostgreSQL 18, MongoDB 8) stand in for the kind of production databases an enterprise already runs. ADB 26ai is attached alongside them as the _sidecar_ — not the production store. It reaches into each engine via DB_LINK views, letting teams adopt Vector Search, Hybrid Vector Index, Select AI Agents, and the rest of 26ai's feature set over the same data without rehosting or rewriting.

## What stays. What's added.

The whole point of the sidecar pattern is that the box on top does not change to get the box at the bottom. The application keeps its existing connections to the production databases; the AI sidecar is bolted on alongside and reaches into the same data through DB_LINK views.

This is a stepping-stone, not an end state. The sidecar buys you time: you can ship AI-powered features against your live data — fraud screening, natural-language analytics, agent-driven investigations — while the current system follows its own migration runway to 26ai on whatever timeline the rest of the business can absorb. No big-bang re-platform, no parallel rewrite, no application freeze. The architecture you already operate stays the architecture you operate.

That timing matters. Fraud and intrusion attempts have gone AI-powered — synthetic identities at onboarding, deepfake voice on phone-banking lines, automated credential stuffing, and real-time transaction-pattern attacks tuned by adversaries who themselves have access to large language models. Defences have to be on the same generation of tooling. Waiting for a multi-year platform migration before you can layer on hybrid vector search, NL2SQL, or agent-driven investigations leaves a window in which the attackers have AI and the defenders don't. The sidecar closes that window now, on the data you already have, while the rest of the migration takes the time it needs to take.

```mermaid
flowchart LR
    classDef existing fill:#F5F2EE,stroke:#6B6560,color:#2C2723
    classDef sidecar  fill:#FDF3F1,stroke:#C74634,color:#2C2723
    classDef ai       fill:#FFF4DC,stroke:#A88040,color:#542

    subgraph current ["Current System — unchanged"]
        direction TB
        app[Your application<br/>frontend + backend]:::existing
        oracle[(Oracle / Oracle Free<br/>customers · accounts<br/>transactions · branches)]:::existing
        postgres[(PostgreSQL<br/>policies · rules)]:::existing
        mongo[(MongoDB<br/>support_tickets)]:::existing
        app --> oracle
        app --> postgres
        app --> mongo
    end

    subgraph sidecarbox ["AI Sidecar — added alongside (Autonomous Database 26ai)"]
        direction TB
        adb[(ADB 26ai)]:::sidecar
        agents[Select AI Agents<br/>multi-agent teams]:::ai
        nl2sql[Select AI NL2SQL<br/>V_BNK_* views]:::ai
        rag[Hybrid Vector Index<br/>policy-doc RAG]:::ai
        adb --- agents
        adb --- nl2sql
        adb --- rag
    end

    app -. opt-in: AI calls .-> adb
    adb -. DB_LINK reads .-> oracle
    adb -. DB_LINK reads .-> postgres
```

The frontend ships five routes against a small banking demo dataset seeded on first deploy: **customers + branches + accounts + transactions** in Oracle Free 26ai, **policies + rules** in PostgreSQL 18, **support_tickets** in MongoDB 8.

- `/risk` — **Risk Dashboard** (default landing). Reads only from the existing production databases; no ADB involvement. Six KPI cards plus six charts (sub-CTR structuring watchlist, cross-border wire flows, KYC pipeline, risk × account-status mix, ticket priority over time, and an active-rule-violations table). Each chart cites the policy and rule codes that drive it.
- `/app` — **Current System.** The backend opens direct JDBC/Mongo connections to each production database. Proves every datasource is reachable; this is what your app already does today.
- `/sidecar` — **sidecar path.** The backend queries ADB; ADB resolves `V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES` over DB_LINK. Proves the federated path end-to-end. (Mongo via sidecar is deliberately disabled; see [docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md](docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md).)
- `/agents` — **Select AI Agents.** A four-agent banking investigation team running entirely inside the ADB sidecar (`DBMS_CLOUD_AI_AGENT.RUN_TEAM`). One prompt fans out to a Transaction Analyst, a Compliance Officer (SQL + RAG over a policy-doc vector index), a Customer Care Liaison, and a Case Synthesiser; the page renders the final answer plus a per-task execution trace. See the "Select AI Agents" section below.
- `/measurements` — **direct vs federated dashboard.** Wall-clock timing for every query, persisted asynchronously to ADB, with summary stats and box plots so the "federated is slower — by how much?" question has a data answer.

### `/risk` — Risk Dashboard

![Risk Dashboard screenshot](images/risk.png)

A compliance & risk overview built from the same production data as `/app`. KPI strip across the top (KYC attention, frozen accounts, high-risk customers, sub-CTR activity, decline velocity, open HIGH-priority tickets) followed by six chart cards. Every chart card has a banking-language footer that cites the relevant rule codes (`R-AML-005`, `R-FRAUD-007`, `R-OFAC-001`, …) and policy codes (`P-CTR-01`, `P-OFAC-01`, `P-KYC-01`, …) so a compliance officer can read it without a translator.

The dashboard is intentionally the human counterpart to `/agents`: the same patterns that get computed visually here are what the Select AI investigation team narrates in plain English over there.

### `/app` — Current System

![Current System screenshot](images/current-app.png)

Five cards, one per table (accounts, transactions, policies, rules, support_tickets), each with a wall-clock badge measured at the backend boundary. One click fans out into five parallel HTTP requests and each card fills in independently as its response returns.

### `/sidecar` — federated via ADB

![ADB sidecar screenshot](images/federated.png)

Same five cards, same dataset, but every query is now routed through the ADB sidecar and its DB_LINK views. The numbers next to each card show the extra latency the federated hop costs (compare with `/app` side by side). The `support_tickets` card is statically marked "not available" — the ADB heterogeneous MongoDB gateway is broken.

### `/agents` — Select AI Agents

![Select AI Agents screenshot](images/agents.png)

The same banking dataset, but every question is now answered by a team of four
agents collaborating inside ADB. The backend issues one
`DBMS_CLOUD_AI_AGENT.RUN_TEAM` call; ADB plans the work, calls OCI Generative
AI for each agent, runs the SQL/RAG tools against `V_BNK_*` views, and returns
both the final synthesised answer and a structured execution trace.

**The team — `BANKING_INVESTIGATION_TEAM`, sequential process:**

| #   | Agent                   | Profile                     | Tools                                        | Reads from                                                                                             |
| --- | ----------------------- | --------------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 1   | `TRANSACTION_ANALYST`   | `BANKING_NL2SQL_TXN`        | `TXN_SQL_TOOL`                               | `V_BNK_CUSTOMERS`, `V_BNK_ACCOUNTS`, `V_BNK_TRANSACTIONS`, `V_BNK_BRANCHES`                            |
| 2   | `COMPLIANCE_OFFICER`    | `BANKING_NL2SQL_COMPLIANCE` | `COMPLIANCE_SQL_TOOL`, `COMPLIANCE_RAG_TOOL` | `V_BNK_POLICIES`, `V_BNK_RULES`, `BANKING_POLICY_INDEX` (5 markdown policy docs in OCI Object Storage) |
| 3   | `CUSTOMER_CARE_LIAISON` | `BANKING_NL2SQL_CARE`       | `CARE_SQL_TOOL`                              | `V_BNK_CUSTOMERS` today; `V_BNK_SUPPORT_TICKETS` once the Mongo gateway is fixed                       |
| 4   | `CASE_SYNTHESIZER`      | `BANKING_CHAT`              | (none — pure LLM reasoning)                  | The other agents' outputs                                                                              |

```mermaid
sequenceDiagram
    autonumber
    participant U as User<br/>(/agents page)
    participant B as Backend<br/>(Spring Boot)
    participant T as TRANSACTION_ANALYST
    participant C as COMPLIANCE_OFFICER
    participant L as CUSTOMER_CARE_LIAISON
    participant S as CASE_SYNTHESIZER

    U->>B: POST /api/v1/agents { prompt, conversationId? }
    B->>T: PULL_TXN_FACTS(query)
    T-->>B: facts
    B->>C: ASSESS_COMPLIANCE(query, facts)
    C-->>B: assessment (rules + policy quotes)
    B->>L: GATHER_CARE_CONTEXT(query, facts)
    L-->>B: customer context
    B->>S: SYNTHESIZE_CASE(query, assessment, context)
    S-->>B: case file
    B-->>U: { answer, trace, conversationId }
```

```mermaid
flowchart LR
    classDef def fill:#fff,stroke:#999,color:#333
    classDef agent fill:#dde9ff,stroke:#345,color:#234
    classDef store fill:#f0f0f0,stroke:#666,color:#333
    classDef rag   fill:#fff4dc,stroke:#a80,color:#542

    T[TRANSACTION_ANALYST]:::agent
    C[COMPLIANCE_OFFICER]:::agent
    L[CUSTOMER_CARE_LIAISON]:::agent
    S[CASE_SYNTHESIZER]:::agent

    O[("Oracle Free 26ai<br/>customers · accounts<br/>transactions · branches")]:::store
    P[("Postgres 18<br/>policies · rules")]:::store
    M[("MongoDB 8<br/>support_tickets")]:::store
    R[("OCI Object Storage<br/>BANKING_POLICY_INDEX<br/>(5 markdown docs)")]:::rag

    T -->|V_BNK_* views| O
    C -->|V_BNK_POLICIES, V_BNK_RULES| P
    C -.RAG.-> R
    L -->|V_BNK_CUSTOMERS| O
    L -.deferred until Mongo gateway fix.-> M
    S --- nope[no datasource — chat profile only]:::def
```

**Five demo questions** (clickable chips on the page; each reaches a different combination of agents and tools):

1. _Are there any suspicious patterns on Carol Diaz's accounts this month?_
2. _Bob Chen disputed a $230 charge — what should we do?_
3. _Summarise Alice Morgan's risk profile._
4. _Why is Jamal Reed's checking account frozen?_
5. _What policies apply to international wires above $10K?_

**Mongo support tickets are wired but deferred.** `V_BNK_SUPPORT_TICKETS` is shipped as a commented-out Liquibase changeset; the seed in `database/mongo/init.js` is extended from 4 to ~25 documents so the data is in place. When the ADB heterogeneous-gateway issue (`docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`) is resolved, three small Liquibase edits flip the CARE agent online.

### `/measurements` — direct vs federated

Customers asked first about the ADB sidecar architecture typically ask: _how much does the federated path cost in latency?_ The `/measurements` route answers that directly.

**What is timed.** Exactly one JDBC/Mongo call per measurement, at the backend boundary (`System.nanoTime()` immediately before the call, again immediately after). HTTP handling, JSON serialization, and the measurement-row INSERT are all outside the timed region — the INSERT is fired asynchronously on a dedicated executor so it can't pollute the number.

**Where it lives.** Rows are persisted to `QUERY_MEASUREMENTS` in ADB. Each row carries `query_id`, `route` (`direct` | `federated`), `elapsed_ms`, `rows_returned`, `success`, `run_id`, and `measured_at`.

**How to read the dashboard.** The summary table shows `n`, mean, and p95 for both routes side by side per query, with a shaded `N` column marking the start of each section. The rightmost `Δ mean (ms)` column is `federated_mean − direct_mean` in absolute ms. Below the table, box plots show the distribution shape for each query. "Trim outliers (IQR)" is on by default and strips points outside `[Q1 − 1.5·IQR, Q3 + 1.5·IQR]` — without it, rare warm-up runs in the 5000-7000 ms range dominate the Y axis and the boxes collapse to flat lines. Toggle it off if you want to see those outliers.

![Measurements dashboard screenshot](images/measurements.png)

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
        front["Front · nginx + Angular 21<br/>/risk · /app · /sidecar · /agents · /measurements"]
        back[Back<br/>Spring Boot 3.5 / Java 23]
    end

    subgraph dbnet [DB subnet 10.0.3.0/24 · production workload · simulated]
        subgraph databases [databases compute · podman]
            oracle[(Oracle Free 26ai<br/>:1521)]
            postgres[(Postgres 18<br/>:5432)]
            mongo[(Mongo 8<br/>:27017)]
        end
    end

    adb[(Autonomous Database 26ai<br/><b>AI sidecar</b> · Vector · Select AI Agents<br/>BANKING_INVESTIGATION_TEAM · BANKING_POLICY_INDEX<br/>query_measurements)]

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

## Deploying

End-to-end provisioning, prerequisites, and cleanup live in **[DEPLOY.md](DEPLOY.md)** — virtualenv setup, the `manage.py setup → build → tf → info` flow, the `terraform apply` step, and how to tear everything down.

## More info

- [DEPLOY.md](DEPLOY.md) — provisioning prerequisites, the `manage.py` flow, and cleanup.
- [docs/FEDERATED_QUERIES.md](docs/FEDERATED_QUERIES.md) — the deep dive on how ADB reaches Oracle Free / Postgres / Mongo through `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`, with the two hard requirements (DNS-resolvable hostname, Mongo data outside `admin`) and the `ORA-17008` mid-run recovery path.
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) — day-two playbook for each tier (ops, databases, back, front) plus how to poke at each database from the ops bastion.
- [docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md](docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md) — the two `PG_LINK` heterogeneous-gateway failure modes (5-minute idle drop and the durable AI-agent enumeration wedge), and why `PG_LINK` is not deployed in this repo.
- [docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md](docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md) — known issue: the third heterogeneous engine (Mongo via `MONGO_LINK`) is unusable due to a DataDirect ODBC bug.
- [NOTES.md](NOTES.md) — what's intentionally deferred and the iteration roadmap.

### Official Oracle references

- [Use Autonomous AI Database as an AI Proxy for Select AI](https://docs.oracle.com/en/database/oracle/oracle-database/26/selai/select-ai-sidecar-databases.html) — the Oracle Database 26ai Select AI User's Guide page that defines the AI Proxy Database / sidecar pattern this repo demonstrates.
- [Use an AI Proxy Database for Select AI NL2SQL](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/select-ai-dblinks.html) — the same pattern in the ADB Serverless docs, with the explicit list of supported heterogeneous engines.
- [Select AI Proxy Integration release note (January 2026)](https://docs.oracle.com/en-us/iaas/releasenotes/autonomous-database-serverless/2026-01-selectai-proxy-int.htm) — when the AI Proxy / sidecar terminology landed in ADB Serverless.
- [Unlocking Data for All with Sidecar — Oracle Autonomous AI Database blog](https://blogs.oracle.com/autonomous-ai-database/unlocking-data-for-all-with-sidecar-empowering-business-users-with-aidriven-insights) — narrative framing of the pattern for a less technical audience.
