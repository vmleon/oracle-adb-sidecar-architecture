# Select AI Agents — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static `/future` placeholder with a working `/agents` page that drives a four-agent banking-investigation team running inside the ADB sidecar (`DBMS_CLOUD_AI_AGENT.RUN_TEAM`), with full execution trace rendered in the UI and a richer banking dataset that surfaces five embedded demo narratives.

**Architecture:** Banking schema upgrade (Oracle Free + Postgres + Mongo seed extension) → additive `V_BNK_*` federated views in ADB (existing views untouched) → Select AI primitives in ADB (credential, 5 profiles, 1 vector index, 4 tools, 4 agents, 4 tasks, 1 team) → Spring Boot endpoint (`POST /api/v1/agents`) that calls `RUN_TEAM` and assembles a trace from `USER_AI_AGENT_*` catalog views → new Angular `/agents` page with chat-with-trace UX.

**Tech Stack:** Liquibase YAML (changelogs), Oracle Free 26ai, PostgreSQL 18, MongoDB 8, Autonomous Database 26ai (Select AI / Select AI Agent), OCI Generative AI, OCI Object Storage, Terraform, Ansible (Jinja2), Spring Boot 3.5 / Java 23 (JdbcTemplate, JUnit 5, Mockito), Angular 19 (standalone components, signals).

**Spec:** [`docs/superpowers/specs/2026-04-28-select-ai-agents-design.md`](../specs/2026-04-28-select-ai-agents-design.md)

---

## File map

### Created

```
database/liquibase/oracle/003-banking-rich.yaml
database/liquibase/postgres/003-compliance-rich.yaml
database/liquibase/adb/004-banking-views-extended.yaml
database/liquibase/adb/005-select-ai-agents.yaml
database/banking-policy-docs/aml-and-ctr-procedures.md
database/banking-policy-docs/kyc-and-cip-requirements.md
database/banking-policy-docs/reg-e-dispute-handling.md
database/banking-policy-docs/ofac-sanctions-screening.md
database/banking-policy-docs/wire-transfer-sop.md
src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsController.java
src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsService.java
src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentRunRequest.java
src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentRunResponse.java
src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentTrace.java
src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsServiceTest.java
src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsControllerTest.java
src/frontend/src/app/pages/agents-page.component.ts
src/frontend/src/app/services/agents.service.ts
docs/AGENTS_DEMO.md
```

### Modified

```
database/liquibase/oracle/db.changelog-master.yaml          (include 003)
database/liquibase/postgres/db.changelog-master.yaml        (include 003)
database/liquibase/adb/db.changelog-master.yaml             (include 004 + 005)
database/liquibase/adb/liquibase.properties.j2              (OCI vars + bucket vars)
database/mongo/init.js                                      (4 → ~25 tickets)
deploy/tf/app/storage.tf                                    (banking-rag-docs bucket)
deploy/tf/app/main.tf or outputs                            (export bucket name + namespace)
deploy/ansible/databases/server.yaml + roles                (render properties, upload docs, run new changelogs)
src/backend/src/main/resources/application.yaml or .j2      (selectai.agents.team)
src/frontend/src/app/app.routes.ts                          (future → agents)
src/frontend/src/app/nav.component.ts                       (label + path)
README.md                                                   (replace /future bullet, mermaid edits, new section §16.3)
manage.py / .env handling                                   (six new OCI_* vars surfaced into Ansible inventory)
```

### Deleted

```
src/frontend/src/app/pages/future-page.component.ts
```

---

## Task list (21 tasks)

| Phase           | #   | Task                                                 | Output                                     |
| --------------- | --- | ---------------------------------------------------- | ------------------------------------------ |
| Banking dataset | 1   | Oracle Free banking schema                           | DDL changesets in `003-banking-rich.yaml`  |
|                 | 2   | Oracle Free banking seed                             | INSERTs in `003-banking-rich.yaml`         |
|                 | 3   | Postgres compliance schema                           | DDL in `003-compliance-rich.yaml`          |
|                 | 4   | Postgres compliance seed                             | INSERTs in `003-compliance-rich.yaml`      |
|                 | 5   | Mongo seed extension                                 | `database/mongo/init.js` extended          |
| ADB views       | 6   | ADB `V_BNK_*` views                                  | `004-banking-views-extended.yaml`          |
| RAG             | 7   | Five policy markdown docs                            | `database/banking-policy-docs/*.md`        |
| Deploy plumbing | 8   | Terraform RAG bucket + outputs                       | `deploy/tf/app/storage.tf` etc.            |
|                 | 9   | Liquibase properties + manage.py wiring              | OCI vars passed end-to-end                 |
| Select AI DDL   | 10  | ADB 005 — ACL + credential + profiles + vector index | first half of `005-select-ai-agents.yaml`  |
|                 | 11  | ADB 005 — tools + agents + tasks + team              | second half of `005-select-ai-agents.yaml` |
| Ansible         | 12  | Ansible: upload docs + run new Liquibase             | `deploy/ansible/databases/...`             |
| Backend         | 13  | Backend DTOs                                         | 5 record files                             |
|                 | 14  | Backend `AgentsService` (TDD)                        | service + unit test                        |
|                 | 15  | Backend `AgentsController` (TDD)                     | controller + `@WebMvcTest`                 |
|                 | 16  | Backend application config                           | `selectai.agents.team`                     |
| Frontend        | 17  | Route rename + delete future + nav update            | router + nav cleaned                       |
|                 | 18  | Frontend `AgentsService`                             | typed HTTP client                          |
|                 | 19  | Frontend `AgentsPageComponent`                       | chat UI with trace toggle                  |
| Docs            | 20  | README updates                                       | §16.1, 16.2, 16.3                          |
|                 | 21  | `docs/AGENTS_DEMO.md`                                | manual demo plan                           |

---

## Cross-cutting conventions

**Idempotent ADB DDL.** Per memory and per `database/liquibase/adb/002-db-links.yaml`: every `CREATE_*` for credentials, profiles, agents, tasks, teams, tools, vector index is preceded by a guarded `DROP` so a partial first run that errors mid-DDL (e.g. `ORA-17008`) recovers on the next pass. Pattern:

```sql
BEGIN
  BEGIN DBMS_CLOUD_AI_AGENT.DROP_TEAM(team_name => 'X', force => true);
    EXCEPTION WHEN OTHERS THEN NULL;
  END;
  DBMS_CLOUD_AI_AGENT.CREATE_TEAM( team_name => 'X', attributes => '{...}');
END;
/
```

**Liquibase changeset settings for PL/SQL blocks.** Always:

```yaml
- sql:
    endDelimiter: "/"
    splitStatements: false
    sql: |
      ...PL/SQL block...
      /
```

**Demo date.** Seed timestamps are anchored to a frozen demo date `2026-04-15`. All "this month" / "yesterday" stories assume the operator's clock is sometime in April–May 2026.

**Frozen IDs.** Customer IDs 1, 2, 3 are kept for Alice Morgan, Bob Chen, Carol Diaz so the existing `/app` and `/sidecar` rows are preserved.

**Verification gate after every dataset/DDL task.** After applying a dataset/DDL change, run the existing `/app` and `/sidecar` queries (or their backend equivalents) and confirm they still return their original column shape — see Task 6 for the explicit smoke check.

**Commit messages.** Conventional commits, no Claude attribution (per global CLAUDE.md). Format: `feat(scope): subject`, `fix(scope): subject`, `docs(scope): subject`. Scopes used here: `banking`, `compliance`, `mongo`, `adb`, `rag`, `tf`, `ansible`, `back`, `front`, `docs`.

---

## Task 1: Oracle Free banking schema

**Files:**

- Create: `database/liquibase/oracle/003-banking-rich.yaml`
- Modify: `database/liquibase/oracle/db.changelog-master.yaml` (one-line `include`)

- [ ] **Step 1.1: Create the changelog file with schema-only changesets**

Create `database/liquibase/oracle/003-banking-rich.yaml` with this exact content:

```yaml
databaseChangeLog:
  - changeSet:
      id: oracle-003-create-customers
      author: adbsidecar
      changes:
        - createTable:
            tableName: customers
            columns:
              - column:
                  name: id
                  type: NUMBER(10)
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: name
                  type: VARCHAR2(100)
                  constraints:
                    nullable: false
              - column:
                  name: email
                  type: VARCHAR2(120)
              - column:
                  name: country_code
                  type: VARCHAR2(2)
              - column:
                  name: kyc_status
                  type: VARCHAR2(16)
                  constraints:
                    nullable: false
              - column:
                  name: risk_tier
                  type: VARCHAR2(8)
                  constraints:
                    nullable: false
              - column:
                  name: joined_at
                  type: DATE

  - changeSet:
      id: oracle-003-customers-checks
      author: adbsidecar
      changes:
        - sql:
            sql: |
              ALTER TABLE customers ADD CONSTRAINT customers_kyc_chk
                CHECK (kyc_status IN ('VERIFIED','PENDING','EXPIRED'))
        - sql:
            sql: |
              ALTER TABLE customers ADD CONSTRAINT customers_risk_chk
                CHECK (risk_tier IN ('LOW','MEDIUM','HIGH'))

  - changeSet:
      id: oracle-003-create-branches
      author: adbsidecar
      changes:
        - createTable:
            tableName: branches
            columns:
              - column:
                  name: id
                  type: NUMBER(10)
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: branch_code
                  type: VARCHAR2(8)
                  constraints:
                    nullable: false
                    unique: true
              - column:
                  name: city
                  type: VARCHAR2(80)
                  constraints:
                    nullable: false
              - column:
                  name: country_code
                  type: VARCHAR2(2)
                  constraints:
                    nullable: false
              - column:
                  name: manager_name
                  type: VARCHAR2(100)

  - changeSet:
      id: oracle-003-alter-accounts
      author: adbsidecar
      changes:
        - addColumn:
            tableName: accounts
            columns:
              - column: { name: customer_id, type: NUMBER(10) }
              - column: { name: branch_id, type: NUMBER(10) }
              - column: { name: account_type, type: VARCHAR2(16) }
              - column: { name: currency, type: VARCHAR2(3) }
              - column: { name: opened_at, type: DATE }
              - column: { name: status, type: VARCHAR2(8) }

  - changeSet:
      id: oracle-003-accounts-fks
      author: adbsidecar
      changes:
        - addForeignKeyConstraint:
            constraintName: fk_accounts_customer
            baseTableName: accounts
            baseColumnNames: customer_id
            referencedTableName: customers
            referencedColumnNames: id
        - addForeignKeyConstraint:
            constraintName: fk_accounts_branch
            baseTableName: accounts
            baseColumnNames: branch_id
            referencedTableName: branches
            referencedColumnNames: id

  - changeSet:
      id: oracle-003-alter-transactions
      author: adbsidecar
      changes:
        - addColumn:
            tableName: transactions
            columns:
              - column: { name: txn_type, type: VARCHAR2(8) }
              - column: { name: currency, type: VARCHAR2(3) }
              - column: { name: channel, type: VARCHAR2(8) }
              - column: { name: merchant, type: VARCHAR2(80) }
              - column: { name: merchant_country, type: VARCHAR2(2) }
              - column: { name: counterparty_account, type: VARCHAR2(40) }
              - column: { name: status, type: VARCHAR2(8) }
              - column: { name: occurred_at, type: TIMESTAMP }
```

- [ ] **Step 1.2: Wire the changelog into the master**

Edit `database/liquibase/oracle/db.changelog-master.yaml` and add one entry **after** the existing `002-banking.yaml` include:

```yaml
- include:
    file: 003-banking-rich.yaml
    relativeToChangelogFile: true
```

- [ ] **Step 1.3: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/oracle/003-banking-rich.yaml'))" && python -c "import yaml; yaml.safe_load(open('database/liquibase/oracle/db.changelog-master.yaml'))"`
Expected: silent exit (no traceback).

- [ ] **Step 1.4: Commit**

```bash
git add database/liquibase/oracle/003-banking-rich.yaml database/liquibase/oracle/db.changelog-master.yaml
git commit -m "feat(banking): add customers/branches tables and extend accounts/transactions"
```

---

## Task 2: Oracle Free banking seed

**Files:**

- Modify: `database/liquibase/oracle/003-banking-rich.yaml` (append seed changesets)

This task seeds 20 customers, 6 branches, ~32 new accounts (total 35 with existing 3), ~140 new transactions (total ~148 with existing 8), backfills the new columns on the 3 existing accounts and 8 existing transactions, and populates the denormalised `customer_name` cache on every account row.

All rows are deterministic so the demo trace is reproducible. Existing IDs (accounts 1–3, transactions 1–8) are preserved and assigned to customers 1–3.

- [ ] **Step 2.1: Append the customers seed changeset**

Append to the end of `database/liquibase/oracle/003-banking-rich.yaml`:

```yaml
- changeSet:
    id: oracle-003-seed-customers
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT ALL
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (1,  'Alice Morgan',     'alice.morgan@example.com',     'US', 'EXPIRED',  'HIGH',   DATE '2018-03-15')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (2,  'Bob Chen',         'bob.chen@example.com',         'US', 'VERIFIED', 'MEDIUM', DATE '2019-07-22')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (3,  'Carol Diaz',       'carol.diaz@example.com',       'US', 'VERIFIED', 'LOW',    DATE '2017-11-04')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (4,  'Jamal Reed',       'jamal.reed@example.com',       'US', 'VERIFIED', 'LOW',    DATE '2021-02-09')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (5,  'Priya Iyer',       'priya.iyer@example.com',       'US', 'VERIFIED', 'LOW',    DATE '2020-05-30')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (6,  'Marco Russo',      'marco.russo@example.com',      'IT', 'VERIFIED', 'LOW',    DATE '2022-01-18')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (7,  'Yuki Tanaka',      'yuki.tanaka@example.com',      'JP', 'VERIFIED', 'LOW',    DATE '2021-09-03')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (8,  'Sara Cohen',       'sara.cohen@example.com',       'US', 'VERIFIED', 'LOW',    DATE '2022-06-12')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (9,  'Liam Walsh',       'liam.walsh@example.com',       'IE', 'VERIFIED', 'LOW',    DATE '2023-04-25')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (10, 'Aisha Khan',       'aisha.khan@example.com',       'GB', 'VERIFIED', 'MEDIUM', DATE '2020-11-08')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (11, 'Diego Vargas',     'diego.vargas@example.com',     'MX', 'VERIFIED', 'LOW',    DATE '2023-08-19')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (12, 'Mei Lin',          'mei.lin@example.com',          'SG', 'VERIFIED', 'LOW',    DATE '2021-12-01')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (13, 'Olu Adebayo',      'olu.adebayo@example.com',      'NG', 'PENDING',  'MEDIUM', DATE '2024-02-14')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (14, 'Tomas Herrera',    'tomas.herrera@example.com',    'AR', 'VERIFIED', 'LOW',    DATE '2022-10-27')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (15, 'Hannah Berg',      'hannah.berg@example.com',      'DE', 'VERIFIED', 'LOW',    DATE '2021-03-05')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (16, 'Ravi Menon',       'ravi.menon@example.com',       'IN', 'VERIFIED', 'LOW',    DATE '2023-06-21')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (17, 'Elena Petrova',    'elena.petrova@example.com',    'BG', 'VERIFIED', 'MEDIUM', DATE '2020-08-14')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (18, 'Jonas Lind',       'jonas.lind@example.com',       'SE', 'VERIFIED', 'LOW',    DATE '2024-01-09')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (19, 'Fatima Hassan',    'fatima.hassan@example.com',    'AE', 'VERIFIED', 'LOW',    DATE '2022-04-30')
              INTO customers (id, name, email, country_code, kyc_status, risk_tier, joined_at) VALUES (20, 'Ben Wright',       'ben.wright@example.com',       'US', 'VERIFIED', 'LOW',    DATE '2023-11-22')
            SELECT 1 FROM DUAL
```

- [ ] **Step 2.2: Append the branches seed changeset**

Append:

```yaml
- changeSet:
    id: oracle-003-seed-branches
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT ALL
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (1, 'NYC-01', 'New York',     'US', 'Diana Chen')
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (2, 'NYC-02', 'Brooklyn',     'US', 'Marcus Hill')
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (3, 'SFO-01', 'San Francisco','US', 'Renata Patel')
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (4, 'LON-01', 'London',       'GB', 'Oliver Bennett')
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (5, 'BER-01', 'Berlin',       'DE', 'Klaus Weber')
              INTO branches (id, branch_code, city,            country_code, manager_name)    VALUES (6, 'SGP-01', 'Singapore',    'SG', 'Wei Lim')
            SELECT 1 FROM DUAL
```

- [ ] **Step 2.3: Append the existing-accounts backfill + new-accounts seed**

Append:

```yaml
- changeSet:
    id: oracle-003-backfill-existing-accounts
    author: adbsidecar
    changes:
      - sql:
          splitStatements: true
          endDelimiter: ";"
          sql: |
            UPDATE accounts SET customer_id = 1, branch_id = 1, account_type = 'CHECKING',     currency = 'USD', opened_at = DATE '2018-03-15', status = 'ACTIVE' WHERE id = 1;
            UPDATE accounts SET customer_id = 2, branch_id = 3, account_type = 'CHECKING',     currency = 'USD', opened_at = DATE '2019-07-22', status = 'ACTIVE' WHERE id = 2;
            UPDATE accounts SET customer_id = 3, branch_id = 1, account_type = 'CHECKING',     currency = 'USD', opened_at = DATE '2017-11-04', status = 'ACTIVE' WHERE id = 3;

- changeSet:
    id: oracle-003-seed-accounts-new
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT ALL
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (21, 'Carol Diaz',     4200.00,    3, 1, 'SAVINGS',      'USD', DATE '2018-06-12', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (22, 'Bob Chen',       2350.50,    2, 3, 'CREDIT',       'USD', DATE '2020-02-04', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (23, 'Alice Morgan',  18450.75,    1, 1, 'SAVINGS',      'USD', DATE '2018-03-15', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (24, 'Jamal Reed',     1840.00,    4, 3, 'CHECKING',     'USD', DATE '2021-02-09', 'FROZEN')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (25, 'Priya Iyer',     7820.40,    5, 3, 'CHECKING',     'USD', DATE '2020-05-30', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (26, 'Marco Russo',    3210.00,    6, 5, 'CHECKING',     'EUR', DATE '2022-01-18', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (27, 'Yuki Tanaka',    5670.00,    7, 6, 'CHECKING',     'USD', DATE '2021-09-03', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (28, 'Sara Cohen',     9240.50,    8, 1, 'CHECKING',     'USD', DATE '2022-06-12', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (29, 'Liam Walsh',     2100.00,    9, 4, 'CHECKING',     'GBP', DATE '2023-04-25', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (30, 'Aisha Khan',     6480.10,   10, 4, 'CHECKING',     'GBP', DATE '2020-11-08', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (31, 'Diego Vargas',   1990.00,   11, 3, 'CHECKING',     'USD', DATE '2023-08-19', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (32, 'Mei Lin',        4300.00,   12, 6, 'CHECKING',     'SGD', DATE '2021-12-01', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (33, 'Olu Adebayo',    1200.00,   13, 1, 'CHECKING',     'USD', DATE '2024-02-14', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (34, 'Tomas Herrera',  3550.00,   14, 1, 'CHECKING',     'USD', DATE '2022-10-27', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (35, 'Hannah Berg',    4900.25,   15, 5, 'CHECKING',     'EUR', DATE '2021-03-05', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (36, 'Ravi Menon',     2780.00,   16, 6, 'CHECKING',     'USD', DATE '2023-06-21', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (37, 'Elena Petrova',  6210.00,   17, 5, 'CHECKING',     'EUR', DATE '2020-08-14', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (38, 'Jonas Lind',     3300.00,   18, 5, 'CHECKING',     'EUR', DATE '2024-01-09', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (39, 'Fatima Hassan',  8120.00,   19, 6, 'CHECKING',     'USD', DATE '2022-04-30', 'ACTIVE')
              INTO accounts (id, customer_name,    balance,    customer_id, branch_id, account_type,   currency, opened_at,             status)    VALUES (40, 'Ben Wright',     2410.00,   20, 1, 'CHECKING',     'USD', DATE '2023-11-22', 'ACTIVE')
            SELECT 1 FROM DUAL
```

- [ ] **Step 2.4: Append the existing-transactions backfill**

The 8 existing transactions (ids 1–8) need their new columns populated. They're treated as routine prior activity:

```yaml
- changeSet:
    id: oracle-003-backfill-existing-transactions
    author: adbsidecar
    changes:
      - sql:
          splitStatements: true
          endDelimiter: ";"
          sql: |
            UPDATE transactions SET txn_type = 'CARD',     currency = 'USD', channel = 'POS',    merchant = 'Trader Joe''s', merchant_country = 'US', status = 'POSTED', occurred_at = TIMESTAMP '2026-03-20 12:14:00' WHERE id = 1;
            UPDATE transactions SET txn_type = 'ACH',      currency = 'USD', channel = 'ONLINE', merchant = NULL,            merchant_country = NULL, status = 'POSTED', occurred_at = TIMESTAMP '2026-04-01 09:00:00' WHERE id = 2;
            UPDATE transactions SET txn_type = 'CARD',     currency = 'USD', channel = 'POS',    merchant = 'Starbucks',     merchant_country = 'US', status = 'POSTED', occurred_at = TIMESTAMP '2026-03-22 08:42:00' WHERE id = 3;
            UPDATE transactions SET txn_type = 'ACH',      currency = 'USD', channel = 'ONLINE', merchant = NULL,            merchant_country = NULL, status = 'POSTED', occurred_at = TIMESTAMP '2026-04-01 09:00:00' WHERE id = 4;
            UPDATE transactions SET txn_type = 'CARD',     currency = 'USD', channel = 'POS',    merchant = 'Best Buy',      merchant_country = 'US', status = 'POSTED', occurred_at = TIMESTAMP '2026-04-08 15:30:00' WHERE id = 5;
            UPDATE transactions SET txn_type = 'ACH',      currency = 'USD', channel = 'ONLINE', merchant = NULL,            merchant_country = NULL, status = 'POSTED', occurred_at = TIMESTAMP '2026-04-01 09:00:00' WHERE id = 6;
            UPDATE transactions SET txn_type = 'CARD',     currency = 'USD', channel = 'POS',    merchant = 'CVS',           merchant_country = 'US', status = 'POSTED', occurred_at = TIMESTAMP '2026-04-04 17:11:00' WHERE id = 7;
            UPDATE transactions SET txn_type = 'CARD',     currency = 'USD', channel = 'POS',    merchant = 'Whole Foods',   merchant_country = 'US', status = 'POSTED', occurred_at = TIMESTAMP '2026-04-10 18:55:00' WHERE id = 8;
```

- [ ] **Step 2.5: Append the narrative transactions seed**

This is the demo-critical seed. Every ID, date and amount matches the spec §7.3 narratives exactly.

```yaml
- changeSet:
    id: oracle-003-seed-transactions-narratives
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT ALL
              -- Carol Diaz (account_id=3) — structuring
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (101, 3,  -9500.00, 'WIRE', 'USD', 'ONLINE', NULL,           'BY', 'BY-86-AKBB-30120-2034', 'POSTED',   TIMESTAMP '2026-04-09 09:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (102, 3,  -9700.00, 'WIRE', 'USD', 'ONLINE', NULL,           'BY', 'BY-86-AKBB-30120-2035', 'POSTED',   TIMESTAMP '2026-04-10 10:22:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (103, 3,  -9800.00, 'WIRE', 'USD', 'ONLINE', NULL,           'BY', 'BY-86-AKBB-30120-2036', 'POSTED',   TIMESTAMP '2026-04-12 14:08:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (104, 3,  -9550.00, 'WIRE', 'USD', 'ONLINE', NULL,           'BY', 'BY-86-AKBB-30120-2037', 'POSTED',   TIMESTAMP '2026-04-14 11:46:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (105, 3,  +3200.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'EMPLOYER-PAYROLL',     'POSTED',   TIMESTAMP '2026-03-15 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (106, 3,  +3200.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'EMPLOYER-PAYROLL',     'POSTED',   TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (107, 3,  -85.40,   'CARD', 'USD', 'POS',    'Whole Foods',  'US', NULL,                    'POSTED',   TIMESTAMP '2026-03-25 18:33:00')
              -- Bob Chen (account_id=2) — Reg E duplicate post
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (110, 2,  -230.00,  'CARD', 'USD', 'POS',    'Acme Hardware','US', NULL,                    'POSTED',   TIMESTAMP '2026-04-13 18:42:17')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (111, 2,  -230.00,  'CARD', 'USD', 'POS',    'Acme Hardware','US', NULL,                    'POSTED',   TIMESTAMP '2026-04-13 18:46:33')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (112, 2,  +5800.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'EMPLOYER-PAYROLL',     'POSTED',   TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (113, 2,  -340.00,  'CARD', 'USD', 'POS',    'Costco',       'US', NULL,                    'POSTED',   TIMESTAMP '2026-04-05 13:08:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (114, 2,  -65.00,   'CARD', 'USD', 'POS',    'Shell',        'US', NULL,                    'POSTED',   TIMESTAMP '2026-04-10 07:55:00')
              -- Alice Morgan (account_id=1) — five large cash deposits at branch NYC-01
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (120, 1,  +9100.00, 'ATM',  'USD', 'BRANCH', NULL,           'US', 'CASH',                 'POSTED',   TIMESTAMP '2026-04-05 10:30:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (121, 1,  +9200.00, 'ATM',  'USD', 'BRANCH', NULL,           'US', 'CASH',                 'POSTED',   TIMESTAMP '2026-04-08 11:15:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (122, 1,  +9000.00, 'ATM',  'USD', 'BRANCH', NULL,           'US', 'CASH',                 'POSTED',   TIMESTAMP '2026-04-10 09:45:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (123, 1,  +9400.00, 'ATM',  'USD', 'BRANCH', NULL,           'US', 'CASH',                 'POSTED',   TIMESTAMP '2026-04-12 14:20:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (124, 1,  +9300.00, 'ATM',  'USD', 'BRANCH', NULL,           'US', 'CASH',                 'POSTED',   TIMESTAMP '2026-04-14 13:05:00')
              -- Jamal Reed (account_id=24) — velocity declines
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (130, 24, -45.00,   'CARD', 'USD', 'POS',    'Shell',        'US', NULL,                    'DECLINED', TIMESTAMP '2026-04-14 14:02:11')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (131, 24, -52.00,   'CARD', 'EUR', 'POS',    'Total Energies','FR', NULL,                   'DECLINED', TIMESTAMP '2026-04-14 14:18:33')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (132, 24, -38.00,   'CARD', 'GBP', 'POS',    'BP',           'GB', NULL,                    'DECLINED', TIMESTAMP '2026-04-14 14:46:55')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (133, 24, +4500.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'EMPLOYER-PAYROLL',     'POSTED',   TIMESTAMP '2026-04-01 09:00:00')
              -- Priya Iyer (account_id=25) — clean baseline
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (140, 25, +5200.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'EMPLOYER-PAYROLL',     'POSTED',   TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (141, 25, -42.00,   'CARD', 'USD', 'POS',    'Trader Joe''s','US', NULL,                    'POSTED',   TIMESTAMP '2026-04-04 17:30:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (142, 25, -1500.00, 'ACH',  'USD', 'ONLINE', NULL,           NULL, 'RENT-LANDLORD',         'POSTED',   TIMESTAMP '2026-04-08 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,           merchant_country, counterparty_account, status,   occurred_at) VALUES (143, 25, -78.00,   'CARD', 'USD', 'POS',    'PG&E',         'US', NULL,                    'POSTED',   TIMESTAMP '2026-04-12 10:00:00')
            SELECT 1 FROM DUAL
```

- [ ] **Step 2.6: Append the routine transactions seed**

Routine transactions for customers 6–20 (accounts 26–40). Each gets one salary deposit + 2 small card purchases + 1 rent ACH:

```yaml
- changeSet:
    id: oracle-003-seed-transactions-routine
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT ALL
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (200, 26, +3500.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (201, 26, -1200.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (202, 26, -55.00,   'CARD', 'EUR', 'POS',    'Carrefour',      'IT', NULL,                'POSTED', TIMESTAMP '2026-04-09 19:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (203, 26, -88.00,   'CARD', 'EUR', 'POS',    'Esselunga',      'IT', NULL,                'POSTED', TIMESTAMP '2026-04-13 18:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (210, 27, +5400.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (211, 27, -1800.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (212, 27, -45.00,   'CARD', 'JPY', 'POS',    'Family Mart',    'JP', NULL,                'POSTED', TIMESTAMP '2026-04-07 13:11:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (213, 27, -120.00,  'CARD', 'JPY', 'POS',    'Don Quijote',    'JP', NULL,                'POSTED', TIMESTAMP '2026-04-11 20:47:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (220, 28, +6200.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (221, 28, -2100.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (222, 28, -64.00,   'CARD', 'USD', 'POS',    'Whole Foods',    'US', NULL,                'POSTED', TIMESTAMP '2026-04-06 18:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (223, 28, -110.00,  'CARD', 'USD', 'POS',    'Costco',         'US', NULL,                'POSTED', TIMESTAMP '2026-04-12 11:42:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (230, 29, +3100.00, 'ACH',  'GBP', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (231, 29, -900.00,  'ACH',  'GBP', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (232, 29, -39.00,   'CARD', 'GBP', 'POS',    'Tesco',          'GB', NULL,                'POSTED', TIMESTAMP '2026-04-08 19:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (233, 29, -52.00,   'CARD', 'GBP', 'POS',    'Sainsbury''s',   'GB', NULL,                'POSTED', TIMESTAMP '2026-04-13 18:30:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (240, 30, +4400.00, 'ACH',  'GBP', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (241, 30, -1500.00, 'ACH',  'GBP', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (242, 30, -68.00,   'CARD', 'GBP', 'POS',    'Marks & Spencer','GB', NULL,                'POSTED', TIMESTAMP '2026-04-09 17:25:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (243, 30, -41.00,   'CARD', 'GBP', 'POS',    'Pret a Manger',  'GB', NULL,                'POSTED', TIMESTAMP '2026-04-14 12:30:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (250, 31, +3800.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (251, 31, -1100.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (252, 31, -47.00,   'CARD', 'USD', 'POS',    'Walmart',        'US', NULL,                'POSTED', TIMESTAMP '2026-04-07 19:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (253, 31, -29.00,   'CARD', 'USD', 'POS',    'Chipotle',       'US', NULL,                'POSTED', TIMESTAMP '2026-04-11 12:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (260, 32, +5100.00, 'ACH',  'SGD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (261, 32, -1700.00, 'ACH',  'SGD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (262, 32, -38.00,   'CARD', 'SGD', 'POS',    'NTUC FairPrice', 'SG', NULL,                'POSTED', TIMESTAMP '2026-04-09 18:25:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (263, 32, -22.00,   'CARD', 'SGD', 'POS',    'Toast Box',      'SG', NULL,                'POSTED', TIMESTAMP '2026-04-13 09:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (270, 33, +2900.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (271, 33, -800.00,  'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (272, 33, -34.00,   'CARD', 'USD', 'POS',    'Walmart',        'US', NULL,                'POSTED', TIMESTAMP '2026-04-08 17:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (273, 33, -22.00,   'CARD', 'USD', 'POS',    'Subway',         'US', NULL,                'POSTED', TIMESTAMP '2026-04-12 13:42:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (280, 34, +4100.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (281, 34, -1300.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (282, 34, -49.00,   'CARD', 'USD', 'POS',    'Whole Foods',    'US', NULL,                'POSTED', TIMESTAMP '2026-04-09 18:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (283, 34, -32.00,   'CARD', 'USD', 'POS',    'Starbucks',      'US', NULL,                'POSTED', TIMESTAMP '2026-04-13 08:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (290, 35, +4700.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (291, 35, -1400.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (292, 35, -42.00,   'CARD', 'EUR', 'POS',    'REWE',           'DE', NULL,                'POSTED', TIMESTAMP '2026-04-08 18:42:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (293, 35, -55.00,   'CARD', 'EUR', 'POS',    'Edeka',          'DE', NULL,                'POSTED', TIMESTAMP '2026-04-13 17:55:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (300, 36, +3300.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (301, 36, -950.00,  'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (302, 36, -29.00,   'CARD', 'USD', 'POS',    'Trader Joe''s',  'US', NULL,                'POSTED', TIMESTAMP '2026-04-08 19:08:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (303, 36, -41.00,   'CARD', 'USD', 'POS',    'Costco',         'US', NULL,                'POSTED', TIMESTAMP '2026-04-12 14:22:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (310, 37, +5500.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (311, 37, -1600.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (312, 37, -33.00,   'CARD', 'EUR', 'POS',    'Lidl',           'DE', NULL,                'POSTED', TIMESTAMP '2026-04-09 18:30:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (313, 37, -48.00,   'CARD', 'EUR', 'POS',    'Edeka',          'DE', NULL,                'POSTED', TIMESTAMP '2026-04-13 17:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (320, 38, +4200.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (321, 38, -1100.00, 'ACH',  'EUR', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (322, 38, -38.00,   'CARD', 'EUR', 'POS',    'ICA',            'SE', NULL,                'POSTED', TIMESTAMP '2026-04-08 17:42:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (323, 38, -52.00,   'CARD', 'EUR', 'POS',    'Coop',           'SE', NULL,                'POSTED', TIMESTAMP '2026-04-13 18:14:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (330, 39, +6800.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (331, 39, -2400.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (332, 39, -75.00,   'CARD', 'AED', 'POS',    'Carrefour',      'AE', NULL,                'POSTED', TIMESTAMP '2026-04-09 19:55:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (333, 39, -90.00,   'CARD', 'AED', 'POS',    'Spinneys',       'AE', NULL,                'POSTED', TIMESTAMP '2026-04-13 18:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (340, 40, +3700.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'EMPLOYER-PAYROLL',  'POSTED', TIMESTAMP '2026-04-01 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (341, 40, -1050.00, 'ACH',  'USD', 'ONLINE', NULL,             NULL, 'RENT-LANDLORD',     'POSTED', TIMESTAMP '2026-04-03 09:00:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (342, 40, -36.00,   'CARD', 'USD', 'POS',    'Trader Joe''s',  'US', NULL,                'POSTED', TIMESTAMP '2026-04-08 18:42:00')
              INTO transactions (id, account_id, amount,    txn_type, currency, channel, merchant,         merchant_country, counterparty_account, status,   occurred_at) VALUES (343, 40, -54.00,   'CARD', 'USD', 'POS',    'Whole Foods',    'US', NULL,                'POSTED', TIMESTAMP '2026-04-13 17:30:00')
            SELECT 1 FROM DUAL
```

- [ ] **Step 2.7: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/oracle/003-banking-rich.yaml'))"`
Expected: silent exit.

- [ ] **Step 2.8: Commit**

```bash
git add database/liquibase/oracle/003-banking-rich.yaml
git commit -m "feat(banking): seed 20 customers, 6 branches, 32 accounts, ~140 transactions"
```

---

## Task 3: Postgres compliance schema

**Files:**

- Create: `database/liquibase/postgres/003-compliance-rich.yaml`
- Modify: `database/liquibase/postgres/db.changelog-master.yaml`

- [ ] **Step 3.1: Create the changelog file**

Create `database/liquibase/postgres/003-compliance-rich.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: postgres-003-alter-policies
      author: adbsidecar
      changes:
        - addColumn:
            tableName: policies
            columns:
              - column: { name: code, type: VARCHAR(20) }
              - column: { name: category, type: VARCHAR(16) }
              - column: { name: effective_at, type: DATE }

  - changeSet:
      id: postgres-003-policies-code-unique
      author: adbsidecar
      changes:
        - addUniqueConstraint:
            constraintName: uq_policies_code
            tableName: policies
            columnNames: code

  - changeSet:
      id: postgres-003-alter-rules
      author: adbsidecar
      changes:
        - addColumn:
            tableName: rules
            columns:
              - column: { name: code, type: VARCHAR(20) }
              - column: { name: name, type: VARCHAR(120) }
              - column: { name: threshold_amount, type: NUMERIC(12, 2) }
              - column: { name: threshold_count, type: INTEGER }
              - column: { name: threshold_window, type: VARCHAR(16) }
              - column: { name: severity, type: VARCHAR(8) }
              - column: { name: description, type: TEXT }
              - column: { name: policy_code, type: VARCHAR(20) }

  - changeSet:
      id: postgres-003-rules-code-unique
      author: adbsidecar
      changes:
        - addUniqueConstraint:
            constraintName: uq_rules_code
            tableName: rules
            columnNames: code
```

- [ ] **Step 3.2: Wire master changelog**

Edit `database/liquibase/postgres/db.changelog-master.yaml` and add after the `002-banking.yaml` include:

```yaml
- include:
    file: 003-compliance-rich.yaml
    relativeToChangelogFile: true
```

- [ ] **Step 3.3: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/postgres/003-compliance-rich.yaml'))" && python -c "import yaml; yaml.safe_load(open('database/liquibase/postgres/db.changelog-master.yaml'))"`
Expected: silent exit.

- [ ] **Step 3.4: Commit**

```bash
git add database/liquibase/postgres/003-compliance-rich.yaml database/liquibase/postgres/db.changelog-master.yaml
git commit -m "feat(compliance): extend policies/rules with codes, categories, thresholds, severity"
```

---

## Task 4: Postgres compliance seed

**Files:**

- Modify: `database/liquibase/postgres/003-compliance-rich.yaml` (append seed changesets)

The seed backfills the 2 existing policies with codes/categories, adds 6 new policies (total 8); backfills the 5 existing rules with codes/names/severity, adds 13 new rules (total 18). Rule codes match the narratives in the spec §7.3.

- [ ] **Step 4.1: Append the policies backfill + new policies**

Append:

```yaml
- changeSet:
    id: postgres-003-backfill-policies
    author: adbsidecar
    changes:
      - sql:
          splitStatements: true
          endDelimiter: ";"
          sql: |
            UPDATE policies SET code = 'P-FRAUD-01', category = 'FRAUD', effective_at = DATE '2024-01-01' WHERE id = 1;
            UPDATE policies SET code = 'P-AML-01',   category = 'AML',   effective_at = DATE '2024-01-01' WHERE id = 2;

- changeSet:
    id: postgres-003-seed-policies-new
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT INTO policies (id, name, description, code, category, effective_at) VALUES
              (3, 'kyc-cip',           'Customer Identification Programme — KYC due diligence and periodic refresh',                            'P-KYC-01',  'KYC',       DATE '2024-01-01'),
              (4, 'reg-e-disputes',    'Regulation E unauthorised electronic transfer dispute handling and timelines',                          'P-REGE-01', 'REG_E',     DATE '2024-01-01'),
              (5, 'ofac-sanctions',    'OFAC SDN list screening and blocked-country procedures for wires and account opening',                  'P-OFAC-01', 'SANCTIONS', DATE '2024-01-01'),
              (6, 'wire-transfer-sop', 'Wire transfer standard operating procedure: limits, jurisdictions, hold-and-release',                   'P-WIRE-01', 'WIRE',      DATE '2024-01-01'),
              (7, 'ctr-reporting',     'Currency Transaction Reporting — $10K cash threshold filing requirement',                               'P-CTR-01',  'AML',       DATE '2024-01-01'),
              (8, 'sar-procedures',    'Suspicious Activity Reporting — narrative requirements and 30-day filing window',                       'P-SAR-01',  'AML',       DATE '2024-01-01')
            ;
```

- [ ] **Step 4.2: Append the rules backfill + new rules**

Append:

```yaml
- changeSet:
    id: postgres-003-backfill-rules
    author: adbsidecar
    changes:
      - sql:
          splitStatements: true
          endDelimiter: ";"
          sql: |
            UPDATE rules SET code = 'R-FRAUD-001', name = 'Online high-amount transaction',     threshold_amount = 5000,  severity = 'WARNING',   description = 'Online transaction over $5,000 deviates from baseline.',                  policy_code = 'P-FRAUD-01' WHERE id = 1;
            UPDATE rules SET code = 'R-FRAUD-002', name = 'High velocity 24h',                  threshold_count  = 10,    threshold_window = 'P24H', severity = 'WARNING', description = 'More than 10 transactions in 24 hours.',                                  policy_code = 'P-FRAUD-01' WHERE id = 2;
            UPDATE rules SET code = 'R-AML-001',   name = 'Single transaction at CTR threshold',threshold_amount = 10000, severity = 'VIOLATION', description = 'Single cash transaction at or above $10,000 triggers CTR filing.',          policy_code = 'P-AML-01'   WHERE id = 3;
            UPDATE rules SET code = 'R-AML-WATCH', name = 'Watchlist counterparty',                                       severity = 'VIOLATION', description = 'Counterparty appears on the bank''s internal watchlist.',                  policy_code = 'P-AML-01'   WHERE id = 4;
            UPDATE rules SET code = 'R-OFAC-001',  name = 'Sanctioned-country counterparty',                              severity = 'VIOLATION', description = 'Counterparty country code is on the OFAC blocked-country list (BY, IR, KP, RU, SY, …).', policy_code = 'P-OFAC-01' WHERE id = 5;

- changeSet:
    id: postgres-003-seed-rules-new
    author: adbsidecar
    changes:
      - sql:
          splitStatements: false
          sql: |
            INSERT INTO rules (id, policy_id, expression, code, name, threshold_amount, threshold_count, threshold_window, severity, description, policy_code) VALUES
              (6,  2, 'sum_cash_30d > 40000',                'R-AML-002',   'Aggregated cash deposits',              40000, NULL, 'P30D',  'VIOLATION', 'Sum of cash deposits exceeding $40,000 within a 30-day window.', 'P-AML-01'),
              (7,  2, 'count(amount IN [9000,10000)) >= 3',  'R-AML-005',   'Structuring under CTR threshold',       NULL,  3,    'P7D',   'VIOLATION', 'Three or more transactions just under $10,000 within 7 days.',  'P-AML-01'),
              (8,  1, 'kyc_status = ''EXPIRED''',            'R-KYC-001',   'KYC document expired',                  NULL,  NULL, NULL,    'WARNING',   'Customer KYC documents have lapsed.',                            'P-KYC-01'),
              (9,  1, 'risk_tier = ''HIGH'' AND kyc_age > 3y','R-KYC-002',  'KYC refresh overdue (HIGH risk)',       NULL,  NULL, 'P3Y',   'VIOLATION', 'High-risk customers must refresh KYC every 3 years.',            'P-KYC-01'),
              (10, 1, 'flagged_activity = TRUE',             'R-KYC-003',   'Re-verify KYC on suspicious activity',  NULL,  NULL, NULL,    'WARNING',   'Initiate KYC refresh whenever new suspicious activity surfaces.','P-KYC-01'),
              (11, 1, 'dispute_provisional_due',             'R-REGE-001',  'Provisional credit deadline',           NULL,  10,   'P10D',  'INFO',      'Provisional credit must be issued within 10 business days of a dispute.', 'P-REGE-01'),
              (12, 1, 'dispute_window',                      'R-REGE-002',  'Reg E dispute window',                  NULL,  NULL, 'P60D',  'INFO',      'Reg E disputes must be filed within 60 days of statement date.', 'P-REGE-01'),
              (13, 2, 'wire_requires_sdn_screen',            'R-OFAC-002',  'Wire SDN screening required',           NULL,  NULL, NULL,    'VIOLATION', 'All outbound wires must screen against the OFAC SDN list.',     'P-OFAC-01'),
              (14, 2, 'intl_wire_amount > 10000',            'R-WIRE-001',  'International wire senior approval',    10000, NULL, NULL,    'WARNING',   'International wires above $10,000 require senior officer approval.', 'P-WIRE-01'),
              (15, 2, 'wire_24h_total > 50000',              'R-WIRE-002',  'Same-day wire cap',                     50000, NULL, 'P24H',  'WARNING',   'Aggregate same-day wire activity above $50,000 requires hold-and-release review.', 'P-WIRE-01'),
              (16, 1, 'card_geo_distinct_1h >= 2',           'R-FRAUD-003', 'Multi-geo card use within 1 hour',      NULL,  2,    'PT1H',  'WARNING',   'Card-present transactions in two or more countries within one hour.', 'P-FRAUD-01'),
              (17, 1, 'card_declined_count_1h >= 3',         'R-FRAUD-007', 'Velocity-triggered freeze',             NULL,  3,    'PT1H',  'VIOLATION', 'Three or more declined card authorisations within one hour auto-freeze the account.', 'P-FRAUD-01'),
              (18, 2, 'cash_amount > 10000',                 'R-CTR-001',   'CTR filing required (cash > $10K)',     10000, NULL, NULL,    'VIOLATION', 'Cash transaction at or above $10,000 triggers a CTR filing.',   'P-CTR-01')
            ;
```

- [ ] **Step 4.3: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/postgres/003-compliance-rich.yaml'))"`
Expected: silent exit.

- [ ] **Step 4.4: Commit**

```bash
git add database/liquibase/postgres/003-compliance-rich.yaml
git commit -m "feat(compliance): seed 8 policies and 18 rules covering AML/KYC/Reg E/OFAC/WIRE/FRAUD/CTR"
```

---

## Task 5: Mongo seed extension

**Files:**

- Modify: `database/mongo/init.js`

Append a new block to `init.js` that inserts 5 narrative tickets + 15 routine tickets, keyed by `customer_id` (matches Oracle Free `customers.id`). Existing 4 tickets stay untouched. Idempotent via `countDocuments({ ticket_id: { $gte: 871 } }) === 0`.

- [ ] **Step 5.1: Append the seed block**

Append to `database/mongo/init.js` (after the existing `if (...countDocuments({}) === 0)` block):

```javascript

// Agent-demo seed: rich narratives + routine tickets keyed by customer_id
// (matches Oracle Free customers.id). Idempotent.
if (bank.support_tickets.countDocuments({ ticket_id: { $gte: 871 } }) === 0) {
  bank.support_tickets.insertMany([
    // --- narrative tickets ---
    { ticket_id: 871,  customer_id: 2, customer: 'Bob Chen',     subject: 'Duplicate charge dispute (resolved)',           body: 'Refund issued for duplicate $129.00 post at Best Buy. Provisional credit applied within Reg E window.', channel: 'EMAIL', status: 'RESOLVED',    priority: 'MED',  created_at: ISODate('2025-08-19T14:22:00Z'), updated_at: ISODate('2025-08-22T09:00:00Z') },
    { ticket_id: 1042, customer_id: 3, customer: 'Carol Diaz',   subject: 'What is the daily wire limit?',                  body: 'Customer asking what the daily outbound wire limit is on her checking account. Asked specifically about international wires.', channel: 'CHAT', status: 'RESOLVED', priority: 'LOW', created_at: ISODate('2026-03-12T15:48:00Z'), updated_at: ISODate('2026-03-12T16:05:00Z') },
    { ticket_id: 1051, customer_id: 2, customer: 'Bob Chen',     subject: 'Duplicate $230 charge from Acme Hardware',       body: 'I see two identical $230 charges from Acme Hardware four minutes apart on April 13. I only made one purchase. Please investigate and refund the duplicate.', channel: 'EMAIL', status: 'OPEN', priority: 'HIGH', created_at: ISODate('2026-04-15T10:14:00Z'), updated_at: ISODate('2026-04-15T10:14:00Z') },
    { ticket_id: 1056, customer_id: 1, customer: 'Alice Morgan', subject: 'Address change request',                         body: 'I moved last week. Please update my address on file. New: 412 Elm St, Brooklyn, NY 11215. Note: my driver''s license shows the old address until I renew next month.', channel: 'CHAT', status: 'OPEN', priority: 'MED', created_at: ISODate('2026-04-11T11:30:00Z'), updated_at: ISODate('2026-04-11T11:30:00Z') },
    { ticket_id: 1063, customer_id: 4, customer: 'Jamal Reed',   subject: 'Lost card — please reissue',                     body: 'I cannot find my debit card and I think it was stolen at the gas station yesterday. Please cancel it and send a replacement to my address on file.', channel: 'PHONE', status: 'IN_PROGRESS', priority: 'HIGH', created_at: ISODate('2026-04-15T09:02:00Z'), updated_at: ISODate('2026-04-15T09:35:00Z') },
    // --- routine tickets, one per customer 6-20 ---
    { ticket_id: 1101, customer_id: 6,  customer: 'Marco Russo',   subject: 'Statement download not working',         body: 'PDF download fails on March statement.',         channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-03-28T10:00:00Z'), updated_at: ISODate('2026-03-28T15:00:00Z') },
    { ticket_id: 1102, customer_id: 7,  customer: 'Yuki Tanaka',   subject: 'Add payee to bill pay',                  body: 'Please add Tokyo Gas to bill pay.',              channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-03-30T08:00:00Z'), updated_at: ISODate('2026-03-30T11:00:00Z') },
    { ticket_id: 1103, customer_id: 8,  customer: 'Sara Cohen',    subject: 'Travel notice — Israel May 5-19',         body: 'Going to Israel May 5-19, please flag.',         channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-02T14:00:00Z'), updated_at: ISODate('2026-04-02T14:30:00Z') },
    { ticket_id: 1104, customer_id: 9,  customer: 'Liam Walsh',    subject: 'ATM withdrawal limit increase',          body: 'Requesting daily ATM limit raise to £800.',      channel: 'PHONE', status: 'IN_PROGRESS', priority: 'MED',  created_at: ISODate('2026-04-04T09:00:00Z'), updated_at: ISODate('2026-04-04T16:00:00Z') },
    { ticket_id: 1105, customer_id: 10, customer: 'Aisha Khan',    subject: 'Wire transfer fee inquiry',              body: 'What is the fee for international wires?',       channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-05T11:00:00Z'), updated_at: ISODate('2026-04-05T13:00:00Z') },
    { ticket_id: 1106, customer_id: 11, customer: 'Diego Vargas',  subject: 'Direct deposit setup',                   body: 'New employer; please send direct-deposit form.', channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-06T10:00:00Z'), updated_at: ISODate('2026-04-06T11:00:00Z') },
    { ticket_id: 1107, customer_id: 12, customer: 'Mei Lin',       subject: 'Mobile deposit not posting',             body: 'Check from 04/02 still not in account.',         channel: 'CHAT',  status: 'OPEN',        priority: 'MED',  created_at: ISODate('2026-04-09T09:00:00Z'), updated_at: ISODate('2026-04-09T09:00:00Z') },
    { ticket_id: 1108, customer_id: 13, customer: 'Olu Adebayo',   subject: 'KYC document submission',                body: 'Submitting passport scan to complete KYC.',      channel: 'EMAIL', status: 'IN_PROGRESS', priority: 'HIGH', created_at: ISODate('2026-04-09T16:00:00Z'), updated_at: ISODate('2026-04-10T08:00:00Z') },
    { ticket_id: 1109, customer_id: 14, customer: 'Tomas Herrera', subject: 'Joint account holder addition',          body: 'Add my partner Lucia to checking account.',      channel: 'PHONE', status: 'IN_PROGRESS', priority: 'LOW',  created_at: ISODate('2026-04-10T13:00:00Z'), updated_at: ISODate('2026-04-11T10:00:00Z') },
    { ticket_id: 1110, customer_id: 15, customer: 'Hannah Berg',   subject: 'Overdraft protection enrollment',         body: 'Please enroll savings as overdraft buffer.',     channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-11T11:00:00Z'), updated_at: ISODate('2026-04-11T13:00:00Z') },
    { ticket_id: 1111, customer_id: 16, customer: 'Ravi Menon',    subject: 'International transfer rate question',    body: 'INR transfer to Mumbai — current FX rate?',      channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-12T09:00:00Z'), updated_at: ISODate('2026-04-12T10:30:00Z') },
    { ticket_id: 1112, customer_id: 17, customer: 'Elena Petrova', subject: 'Statement archive request',              body: 'Need 2024 statements for tax filing.',           channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-12T14:00:00Z'), updated_at: ISODate('2026-04-12T14:20:00Z') },
    { ticket_id: 1113, customer_id: 18, customer: 'Jonas Lind',    subject: 'New checking account inquiry',           body: 'Want to open second checking for business.',     channel: 'EMAIL', status: 'OPEN',        priority: 'LOW',  created_at: ISODate('2026-04-13T10:00:00Z'), updated_at: ISODate('2026-04-13T10:00:00Z') },
    { ticket_id: 1114, customer_id: 19, customer: 'Fatima Hassan', subject: 'Travel notice — Egypt April 20-30',      body: 'Travelling to Egypt next week.',                 channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-14T08:00:00Z'), updated_at: ISODate('2026-04-14T08:15:00Z') },
    { ticket_id: 1115, customer_id: 20, customer: 'Ben Wright',    subject: 'Credit card credit-limit increase',      body: 'Requesting CL increase from $5K to $10K.',       channel: 'PHONE', status: 'IN_PROGRESS', priority: 'LOW',  created_at: ISODate('2026-04-14T15:00:00Z'), updated_at: ISODate('2026-04-15T09:00:00Z') },
  ]);
}

print('Mongo init.js extended seed complete: 5 narrative + 15 routine tickets inserted (if absent).');
```

- [ ] **Step 5.2: Lint passes**

Run: `node --check database/mongo/init.js`
Expected: silent exit (no syntax errors).

- [ ] **Step 5.3: Commit**

```bash
git add database/mongo/init.js
git commit -m "feat(mongo): extend support_tickets with 5 narrative + 15 routine tickets"
```

---

## Task 6: ADB additive `V_BNK_*` views

**Files:**

- Create: `database/liquibase/adb/004-banking-views-extended.yaml`
- Modify: `database/liquibase/adb/db.changelog-master.yaml`

Add **only new** `V_BNK_*` views over `ORAFREE_LINK` and `PG_LINK`. Existing `V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES` are not touched. Mongo-backed `V_BNK_SUPPORT_TICKETS` is shipped commented-out.

- [ ] **Step 6.1: Create the changelog**

Create `database/liquibase/adb/004-banking-views-extended.yaml`:

```yaml
databaseChangeLog:
  # ----- Oracle Free side (ORAFREE_LINK exists from 002-db-links.yaml) -----
  - changeSet:
      id: adb-004-view-bnk-customers
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_CUSTOMERS AS
              SELECT id, name, email, country_code, kyc_status, risk_tier, joined_at
              FROM customers@ORAFREE_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_CUSTOMERS }

  - changeSet:
      id: adb-004-view-bnk-branches
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_BRANCHES AS
              SELECT id, branch_code, city, country_code, manager_name
              FROM branches@ORAFREE_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_BRANCHES }

  - changeSet:
      id: adb-004-view-bnk-accounts
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_ACCOUNTS AS
              SELECT id, customer_id, branch_id, account_type, currency, balance, opened_at, status, customer_name
              FROM accounts@ORAFREE_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_ACCOUNTS }

  - changeSet:
      id: adb-004-view-bnk-transactions
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_TRANSACTIONS AS
              SELECT id, account_id, txn_type, currency, amount, channel, merchant, merchant_country,
                     counterparty_account, status, occurred_at, tx_date
              FROM transactions@ORAFREE_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_TRANSACTIONS }

  # ----- Postgres side (PG_LINK exists; "public" quoting per 002 workaround) -----
  - changeSet:
      id: adb-004-view-bnk-policies
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_POLICIES AS
              SELECT "id" AS id, "code" AS code, "name" AS name, "description" AS description,
                     "category" AS category, "effective_at" AS effective_at
              FROM "public"."policies"@PG_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_POLICIES }

  - changeSet:
      id: adb-004-view-bnk-rules
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              CREATE OR REPLACE VIEW V_BNK_RULES AS
              SELECT "id" AS id, "code" AS code, "name" AS name, "description" AS description,
                     "policy_id" AS policy_id, "policy_code" AS policy_code,
                     "expression" AS expression,
                     "threshold_amount" AS threshold_amount,
                     "threshold_count" AS threshold_count,
                     "threshold_window" AS threshold_window,
                     "severity" AS severity
              FROM "public"."rules"@PG_LINK
              /
      rollback:
        - sql: { sql: DROP VIEW V_BNK_RULES }

  # ----- MongoDB side — DEFERRED -----
  # Uncomment the changeset below once
  # docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md is resolved
  # AND simultaneously add V_BNK_SUPPORT_TICKETS to BANKING_NL2SQL_CARE's
  # object_list (see 005-select-ai-agents.yaml, changeset adb-005-profile-care).
  #
  # - changeSet:
  #     id: adb-004-view-support-tickets-deferred
  #     author: adbsidecar
  #     changes:
  #       - sql:
  #           endDelimiter: "/"
  #           splitStatements: false
  #           sql: |
  #             CREATE OR REPLACE VIEW V_BNK_SUPPORT_TICKETS AS
  #             SELECT "ticket_id"   AS id,
  #                    "customer_id" AS customer_id,
  #                    "customer"    AS customer,
  #                    "subject"     AS subject,
  #                    "body"        AS body,
  #                    "channel"     AS channel,
  #                    "status"      AS status,
  #                    "priority"    AS priority,
  #                    "created_at"  AS created_at,
  #                    "updated_at"  AS updated_at
  #             FROM "support_tickets"@MONGO_LINK
  #             /
  #     rollback:
  #       - sql: { sql: DROP VIEW V_BNK_SUPPORT_TICKETS }
```

- [ ] **Step 6.2: Wire master changelog**

Replace `database/liquibase/adb/db.changelog-master.yaml` with:

```yaml
databaseChangeLog:
  - include: { file: 001-init.yaml, relativeToChangelogFile: true }
  - include: { file: 002-db-links.yaml, relativeToChangelogFile: true }
  - include: { file: 003-measurements.yaml, relativeToChangelogFile: true }
  - include:
      { file: 004-banking-views-extended.yaml, relativeToChangelogFile: true }
```

(The fifth — `005-select-ai-agents.yaml` — gets added in Task 11 once the file exists.)

- [ ] **Step 6.3: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/adb/004-banking-views-extended.yaml'))" && python -c "import yaml; yaml.safe_load(open('database/liquibase/adb/db.changelog-master.yaml'))"`
Expected: silent exit.

- [ ] **Step 6.4: Smoke check (manual, after deploy)**

After running Liquibase against ADB, the demo-runner verifies via SQLcl that the existing slim views still match their original contracts and the new rich views resolve:

```sql
-- Existing slim views unchanged.
SELECT id, customer_name, balance        FROM V_ACCOUNTS     WHERE ROWNUM <= 3;
SELECT id, account_id, amount, tx_date   FROM V_TRANSACTIONS WHERE ROWNUM <= 3;
SELECT id, name, description             FROM V_POLICIES     WHERE ROWNUM <= 3;
SELECT id, policy_id, expression         FROM V_RULES        WHERE ROWNUM <= 3;
-- New rich views resolve.
SELECT id, name, kyc_status, risk_tier   FROM V_BNK_CUSTOMERS WHERE ROWNUM <= 3;
SELECT id, txn_type, amount, status      FROM V_BNK_TRANSACTIONS WHERE id IN (101,102,103,104);
SELECT code, name, severity              FROM V_BNK_RULES     WHERE code = 'R-AML-005';
```

Expected: each query returns rows; existing views show same shape they showed before this task.

- [ ] **Step 6.5: Commit**

```bash
git add database/liquibase/adb/004-banking-views-extended.yaml database/liquibase/adb/db.changelog-master.yaml
git commit -m "feat(adb): add V_BNK_* views over Oracle Free + Postgres (Mongo deferred)"
```

---

## Task 7: Five RAG policy markdown documents

**Files:** create 5 files under `database/banking-policy-docs/`.

Each doc is hand-written (~300–500 words) so RAG quotes are predictable. Markdown headings are written so the chunker can split cleanly. Domain content tracks standard US bank practice (BSA/AML, Reg E, OFAC) without real institutional branding.

- [ ] **Step 7.1: Create `aml-and-ctr-procedures.md`**

```markdown
# AML and CTR Procedures (P-AML-01, P-CTR-01)

This document defines the bank's anti-money-laundering (AML) and Currency Transaction Reporting (CTR) procedures. It governs detection, escalation, and filing for transactions that meet or exceed Bank Secrecy Act (BSA) thresholds.

## 1. Purpose

The bank is required under the Bank Secrecy Act to file a Currency Transaction Report (CTR) for any cash transaction equal to or greater than $10,000. This document also covers structuring patterns — multiple sub-threshold transactions designed to evade CTR reporting — which trigger Suspicious Activity Report (SAR) review under policy P-SAR-01.

## 2. Scope

Applies to all customer-facing branches, ATM channels, electronic deposit channels, and wire-transfer counters. AML monitoring extends to outbound wire transfers irrespective of cash basis.

## 3. Detection rules

### 3.1 Single-transaction CTR threshold

Rule `R-AML-001` (severity `VIOLATION`): a single cash transaction of $10,000 or more triggers an automatic CTR filing within 15 calendar days.

### 3.2 Aggregated cash deposits

Rule `R-AML-002` (severity `VIOLATION`): when the sum of cash deposits to a single customer's accounts exceeds $40,000 within any 30-day rolling window, a CTR aggregation review is required and a SAR must be considered. Branch managers are notified by the AML monitoring system the same business day.

### 3.3 Structuring under threshold

Rule `R-AML-005` (severity `VIOLATION`): three or more transactions where the amount is in the band [$9,000, $10,000) within a 7-day rolling window indicates structuring. The account is flagged for SAR review and outbound wire activity is held pending compliance officer approval.

## 4. SAR filing window

Suspicious Activity Reports must be filed within 30 calendar days of the date of initial detection. A 30-day extension is permitted if no suspect has been identified, not to exceed 60 days from initial detection.

## 5. Cross-references

- See `kyc-and-cip-requirements.md` for KYC refresh triggers driven by AML detections.
- See `ofac-sanctions-screening.md` for jurisdictional risk overlays on AML monitoring.
- See `wire-transfer-sop.md` for hold-and-release procedures during open AML reviews.
```

- [ ] **Step 7.2: Create `kyc-and-cip-requirements.md`**

```markdown
# KYC and CIP Requirements (P-KYC-01)

This document specifies the bank's Customer Identification Programme (CIP) and Know-Your-Customer (KYC) periodic refresh obligations.

## 1. Purpose

To verify customer identity at onboarding and refresh due-diligence information at risk-tiered intervals, in line with the FinCEN CIP rule and the bank's Customer Due Diligence (CDD) policy.

## 2. Onboarding (CIP)

Every new customer must provide: full legal name, date of birth, residential address, and a government-issued identification number. Documentary verification is required for all foreign nationals.

## 3. KYC refresh cadence

| Risk tier | Refresh interval |
| --------- | ---------------- |
| LOW       | Every 5 years    |
| MEDIUM    | Every 3 years    |
| HIGH      | Every 1 year     |

A KYC document set is considered EXPIRED when the most recent refresh date is older than the tier-appropriate interval.

## 4. Detection rules

### 4.1 Expired KYC

Rule `R-KYC-001` (severity `WARNING`): customers whose KYC documents have lapsed must complete refresh within 30 days.

### 4.2 Refresh overdue (HIGH risk)

Rule `R-KYC-002` (severity `VIOLATION`): HIGH-risk customers with documents older than 3 years must refresh KYC before the next outbound wire is permitted.

### 4.3 Re-verify on suspicious activity

Rule `R-KYC-003` (severity `WARNING`): when AML monitoring (P-AML-01) flags suspicious activity on an account, KYC refresh is initiated automatically as part of Enhanced Due Diligence (EDD).

## 5. EDD triggers

EDD applies whenever:

- A SAR has been filed against the customer in the previous 12 months.
- The customer's risk tier has been escalated within the last 90 days.
- The customer originates or receives wires to/from a jurisdiction on the OFAC blocked-country list.

## 6. Cross-references

- `aml-and-ctr-procedures.md` — Section 3 (rules driving R-KYC-003).
- `ofac-sanctions-screening.md` — EDD jurisdictional overlay.
```

- [ ] **Step 7.3: Create `reg-e-dispute-handling.md`**

```markdown
# Reg E Dispute Handling (P-REGE-01)

This document defines the bank's procedure for handling consumer-initiated disputes of unauthorised electronic funds transfers under Regulation E (12 CFR §1005).

## 1. Purpose

To ensure timely investigation, provisional credit, and resolution of unauthorised electronic transfer disputes within Reg E timelines.

## 2. Dispute window

Rule `R-REGE-002`: Reg E disputes must be initiated by the consumer **within 60 days** of the statement date on which the unauthorised transfer first appears. Disputes outside this window may be considered as a courtesy but are not subject to Reg E protections.

## 3. Provisional credit

Rule `R-REGE-001`: when an investigation cannot be completed within 10 business days of receiving the dispute, the bank must issue provisional credit for the disputed amount within those 10 business days. Provisional credit is reversible if the investigation finds no error.

## 4. Investigation timelines

- **10 business days** — initial investigation deadline.
- **45 calendar days** — extended investigation window when provisional credit has been issued.
- **90 calendar days** — maximum extension applicable to point-of-sale or international transfer disputes.

## 5. Common dispute scenarios

### 5.1 Duplicate post

Two or more identical card-present transactions from the same merchant within a short window (typically under 5 minutes) at the same amount strongly suggest a duplicate authorisation. Such cases are resolved by reversing the duplicate and applying provisional credit immediately.

### 5.2 Unauthorised transfer

A consumer reports a transfer they did not initiate. Investigation includes IP address review, device fingerprinting, geolocation, and contact with the merchant. If proven unauthorised, the bank credits the full amount and reissues the affected card.

### 5.3 Incorrect amount

A merchant authorised an amount different from the agreed price. The bank obtains the original receipt and contacts the merchant for adjustment.

## 6. Documentation

A dispute file must contain: original dispute statement, date received, investigation timeline, provisional credit ledger entry (if any), final resolution, and consumer notification.

## 7. Cross-references

- `wire-transfer-sop.md` — note that wires are _not_ covered by Reg E (UCC 4A timelines instead).
```

- [ ] **Step 7.4: Create `ofac-sanctions-screening.md`**

```markdown
# OFAC Sanctions Screening (P-OFAC-01)

This document defines the bank's screening procedures against the U.S. Treasury Office of Foreign Assets Control (OFAC) Specially Designated Nationals (SDN) list and the blocked-country list.

## 1. Purpose

To prevent processing of transactions that involve sanctioned individuals, entities, or jurisdictions, in compliance with OFAC obligations.

## 2. Blocked-country list

The current blocked-country ISO codes (illustrative; consult OFAC for live list): **BY** (Belarus), **IR** (Iran), **KP** (North Korea), **RU** (Russia, partial), **SY** (Syria), and others as updated. Outbound wires, account openings, and beneficial-owner relationships involving these countries require senior officer approval and may be blocked outright.

## 3. Screening points

Rule `R-OFAC-001` (severity `VIOLATION`): a counterparty country code on the blocked list automatically holds the transaction for compliance review.

Rule `R-OFAC-002` (severity `VIOLATION`): all outbound wires must screen against the OFAC SDN list at the time of authorisation. A match results in immediate hold and notification of the compliance officer.

## 4. Screening cadence

- **At onboarding**: every new customer is screened against SDN.
- **At each transaction**: outbound wires and high-value internal transfers are screened in real time.
- **Daily**: all active customers are re-screened against the most recent SDN delta.

## 5. Hit handling

A potential SDN hit triggers:

1. Transaction held; funds not released to the counterparty.
2. Compliance officer reviews within 1 business day.
3. If confirmed, funds are blocked and a report is filed with OFAC within 10 business days.
4. If the hit is a false positive, the transaction is released and the customer record annotated.

## 6. Cross-references

- `wire-transfer-sop.md` — wire-screening protocol.
- `aml-and-ctr-procedures.md` — interaction with structuring and SAR processes.
- `kyc-and-cip-requirements.md` — EDD trigger when blocked-country activity is detected.
```

- [ ] **Step 7.5: Create `wire-transfer-sop.md`**

```markdown
# Wire Transfer SOP (P-WIRE-01)

This document is the standard operating procedure for wire transfer authorisation, screening, hold, and release.

## 1. Purpose

To define limits, jurisdictional reviews, and the hold-and-release procedure for outbound and inbound wire transfers.

## 2. Outbound wire limits

| Wire type             | Limit                       | Approval required         |
| --------------------- | --------------------------- | ------------------------- |
| Domestic, single      | $25,000 self-service        | None below threshold      |
| Domestic, single      | > $25,000                   | Branch manager            |
| International, single | > $10,000 (Rule R-WIRE-001) | Senior compliance officer |
| Same-day aggregate    | > $50,000 (Rule R-WIRE-002) | Hold-and-release review   |

## 3. Mandatory screening

Every outbound wire must:

1. Pass OFAC SDN screening (Rule R-OFAC-002, see `ofac-sanctions-screening.md`).
2. Pass blocked-country screening (Rule R-OFAC-001).
3. Pass AML aggregation review (Rule R-AML-002 from `aml-and-ctr-procedures.md`).

A failure on any screen places the wire on **hold** until reviewed.

## 4. Hold-and-release procedure

When a wire is held:

1. The compliance officer is notified within 30 minutes.
2. The customer is contacted to confirm intent (when permitted by the underlying alert).
3. If approved, the wire is released the same business day where possible.
4. If declined, funds are returned to the originating account and the customer is notified in writing within 1 business day.

## 5. International wires

International wires require:

- Beneficiary full legal name, complete address, beneficiary bank SWIFT/BIC, and account number.
- Purpose-of-payment narrative (mandatory for amounts > $10,000).
- Senior officer approval for amounts > $10,000 (Rule R-WIRE-001).

Wires to blocked-country jurisdictions are blocked outright and not subject to release.

## 6. Cross-references

- `aml-and-ctr-procedures.md` — cash-aggregation interaction.
- `kyc-and-cip-requirements.md` — KYC refresh on HIGH-risk wire activity.
- `reg-e-dispute-handling.md` — note: wires are not covered by Reg E (UCC 4A timelines).
```

- [ ] **Step 7.6: Smoke check**

Run: `for f in database/banking-policy-docs/*.md; do echo "=== $f"; wc -w "$f"; head -1 "$f"; done`
Expected: 5 files, each 250+ words, each starts with `# `.

- [ ] **Step 7.7: Commit**

```bash
git add database/banking-policy-docs/
git commit -m "docs(rag): add 5 banking policy markdown docs for vector index"
```

---

## Task 8: Terraform RAG bucket + outputs

**Files:**

- Modify: `deploy/tf/app/storage.tf` (add bucket resource)
- Modify: `deploy/tf/app/outputs.tf` (export bucket name + namespace)
- Modify: `deploy/tf/app/main.tf` or wherever the Ansible inventory rendering lives — pass the two new vars into Ansible group_vars.

A single OCI Object Storage bucket holds the 5 policy docs that ADB ingests as a vector index.

- [ ] **Step 8.1: Add bucket resource**

In `deploy/tf/app/storage.tf`, alongside the existing artifacts bucket, add:

```hcl
resource "oci_objectstorage_bucket" "banking_rag_docs" {
  compartment_id = var.compartment_ocid
  namespace      = data.oci_objectstorage_namespace.this.namespace
  name           = "banking-rag-docs"
  access_type    = "NoPublicAccess"
  storage_tier   = "Standard"
  versioning     = "Disabled"
}
```

(Reuse the existing `data.oci_objectstorage_namespace.this` if it's already declared in this file; if not, add it: `data "oci_objectstorage_namespace" "this" { compartment_id = var.tenancy_ocid }`.)

- [ ] **Step 8.2: Export outputs**

Append to `deploy/tf/app/outputs.tf`:

```hcl
output "rag_bucket_name" {
  value = oci_objectstorage_bucket.banking_rag_docs.name
}

output "rag_bucket_namespace" {
  value = oci_objectstorage_bucket.banking_rag_docs.namespace
}
```

- [ ] **Step 8.3: Pass into Ansible group_vars**

The repo already renders an Ansible inventory template. Wherever `databases` group_vars are populated from Terraform output (look for a `templatefile(...)` call referencing `databases.yaml.tftpl` or similar), add:

```
rag_bucket_name:      "${rag_bucket_name}"
rag_bucket_namespace: "${rag_bucket_namespace}"
```

- [ ] **Step 8.4: `terraform validate` passes**

Run: `cd deploy/tf/app && terraform init -backend=false && terraform validate && cd ../../..`
Expected: `Success! The configuration is valid.`

- [ ] **Step 8.5: Commit**

```bash
git add deploy/tf/app/storage.tf deploy/tf/app/outputs.tf deploy/tf/app/main.tf
git commit -m "feat(tf): provision banking-rag-docs bucket and export name/namespace"
```

---

## Task 9: Liquibase properties + manage.py wiring (OCI vars)

**Files:**

- Modify: `database/liquibase/adb/liquibase.properties.j2` (add OCI vars)
- Modify: `manage.py` — surface six new OCI\_\* env vars from `.env`
- Modify: Ansible `databases/server.yaml` and/or `vars/main.yaml` — pass them through

The OCI key material flows: user's `.env` → `manage.py` → Terraform variables (for Ansible inventory) → Ansible group_vars → Liquibase property substitution at run time.

- [ ] **Step 9.1: Extend `liquibase.properties.j2`**

Append to `database/liquibase/adb/liquibase.properties.j2` (after the existing properties):

```
oci_user_ocid:        {{ oci_user_ocid }}
oci_tenancy_ocid:     {{ oci_tenancy_ocid }}
oci_fingerprint:      {{ oci_fingerprint }}
oci_private_api_key:  {{ oci_private_api_key | replace('\n', '\\n') }}
genai_region:         {{ oci_genai_region }}
oci_genai_compartment_id: {{ oci_genai_compartment_id }}
rag_bucket_name:      {{ rag_bucket_name }}
rag_bucket_namespace: {{ rag_bucket_namespace }}
```

(Liquibase properties don't support multi-line values; the `replace('\n', '\\n')` yields a single-line PEM. The PL/SQL `CREATE_CREDENTIAL` call accepts `\n` escapes inside the private_key string when SQL\*Plus is run with `set sqlblanklines on`. If Liquibase's JDBC driver doesn't honour the escape, fall back to the SQLcl-Jinja path noted in spec §14, "Liquibase property substitution and multi-line PEM".)

- [ ] **Step 9.2: Surface env vars from `.env` through `manage.py`**

Read `manage.py` to locate where `.env` is parsed and Terraform tfvars are written. Add the six new variables to the parsing list and to the tfvars output:

- `OCI_USER_OCID`
- `OCI_TENANCY_OCID`
- `OCI_API_KEY_PATH` (path to PEM)
- `OCI_FINGERPRINT`
- `OCI_GENAI_REGION` (default `us-chicago-1`)
- `OCI_GENAI_COMPARTMENT_ID`

`manage.py setup` should prompt for each on first run and persist them; `manage.py tf` should pass them as `-var` inputs.

The PEM file is read at template-render time and inlined as `oci_private_api_key`.

- [ ] **Step 9.3: Pass into Ansible group_vars**

In the Terraform-rendered Ansible inventory or `deploy/ansible/group_vars/databases.yaml`, surface the six values + bucket vars from Task 8.

- [ ] **Step 9.4: Lint / dry-run**

Run: `python -c "from jinja2 import Template; Template(open('database/liquibase/adb/liquibase.properties.j2').read()).render(oci_user_ocid='X', oci_tenancy_ocid='X', oci_fingerprint='X', oci_private_api_key='X\nY', oci_genai_region='X', oci_genai_compartment_id='X', rag_bucket_name='X', rag_bucket_namespace='X', databases_fqdn='x', oracle_db_password='x', postgres_db_password='x', mongo_db_password='x')"`
Expected: silent exit (template renders).

- [ ] **Step 9.5: Commit**

```bash
git add database/liquibase/adb/liquibase.properties.j2 manage.py deploy/ansible/
git commit -m "feat(deploy): surface OCI GenAI credentials and RAG bucket vars to Liquibase"
```

---

## Task 10: ADB 005 — ACL + credential + 5 profiles + vector index

**Files:**

- Create: `database/liquibase/adb/005-select-ai-agents.yaml` (first half — through vector index)

Every PL/SQL block uses `endDelimiter: "/"`, `splitStatements: false`, and a guarded `DROP` before each `CREATE` per the idempotency convention.

- [ ] **Step 10.1: Create the changelog file with ACL + credential**

Create `database/liquibase/adb/005-select-ai-agents.yaml`:

```yaml
databaseChangeLog:
  # ----- 1. Network ACL: allow ADB to reach OCI GenAI + Object Storage -----
  - changeSet:
      id: adb-005-network-acl
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              BEGIN
                DBMS_NETWORK_ACL_ADMIN.APPEND_HOST_ACE(
                  host => '*.oci.oraclecloud.com',
                  ace  => xs$ace_type(privilege_list => xs$name_list('http'),
                                      principal_name => 'ADMIN',
                                      principal_type => xs_acl.ptype_db));
              END;
              /

  # ----- 2. OCI API key credential (drop+create for idempotency) -----
  - changeSet:
      id: adb-005-cred-oci-genai
      author: adbsidecar
      runOnChange: true
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              BEGIN
                BEGIN DBMS_CLOUD.DROP_CREDENTIAL('OCI_API_KEY_CRED'); EXCEPTION WHEN OTHERS THEN NULL; END;
                DBMS_CLOUD.CREATE_CREDENTIAL(
                  credential_name => 'OCI_API_KEY_CRED',
                  user_ocid       => '${oci_user_ocid}',
                  tenancy_ocid    => '${oci_tenancy_ocid}',
                  private_key     => '${oci_private_api_key}',
                  fingerprint     => '${oci_fingerprint}'
                );
              END;
              /
```

- [ ] **Step 10.2: Append the 5 profiles**

Append to the same file:

```yaml
# ----- 3-7. Profiles -----
- changeSet:
    id: adb-005-profile-txn
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_NL2SQL_TXN', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI.CREATE_PROFILE(
                profile_name => 'BANKING_NL2SQL_TXN',
                attributes   =>
                '{
                  "provider": "oci",
                  "credential_name": "OCI_API_KEY_CRED",
                  "object_list": [
                    {"owner": "ADMIN", "name": "V_BNK_CUSTOMERS"},
                    {"owner": "ADMIN", "name": "V_BNK_ACCOUNTS"},
                    {"owner": "ADMIN", "name": "V_BNK_TRANSACTIONS"},
                    {"owner": "ADMIN", "name": "V_BNK_BRANCHES"}
                  ],
                  "region": "${genai_region}",
                  "oci_compartment_id": "${oci_genai_compartment_id}",
                  "oci_apiformat": "GENERIC"
                }');
            END;
            /

- changeSet:
    id: adb-005-profile-compliance
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_NL2SQL_COMPLIANCE', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI.CREATE_PROFILE(
                profile_name => 'BANKING_NL2SQL_COMPLIANCE',
                attributes   =>
                '{
                  "provider": "oci",
                  "credential_name": "OCI_API_KEY_CRED",
                  "object_list": [
                    {"owner": "ADMIN", "name": "V_BNK_POLICIES"},
                    {"owner": "ADMIN", "name": "V_BNK_RULES"}
                  ],
                  "region": "${genai_region}",
                  "oci_compartment_id": "${oci_genai_compartment_id}",
                  "oci_apiformat": "GENERIC"
                }');
            END;
            /

- changeSet:
    id: adb-005-profile-care
    author: adbsidecar
    runOnChange: true
    changes:
      # Initial object_list excludes V_BNK_SUPPORT_TICKETS until the
      # ADB heterogeneous-MongoDB gateway is fixed. See spec §14
      # "Mongo flip-the-switch" for the one-line follow-up.
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_NL2SQL_CARE', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI.CREATE_PROFILE(
                profile_name => 'BANKING_NL2SQL_CARE',
                attributes   =>
                '{
                  "provider": "oci",
                  "credential_name": "OCI_API_KEY_CRED",
                  "object_list": [
                    {"owner": "ADMIN", "name": "V_BNK_CUSTOMERS"}
                  ],
                  "region": "${genai_region}",
                  "oci_compartment_id": "${oci_genai_compartment_id}",
                  "oci_apiformat": "GENERIC"
                }');
            END;
            /

- changeSet:
    id: adb-005-profile-rag
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_RAG', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI.CREATE_PROFILE(
                profile_name => 'BANKING_RAG',
                attributes   =>
                '{
                  "provider": "oci",
                  "credential_name": "OCI_API_KEY_CRED",
                  "vector_index_name": "BANKING_POLICY_INDEX",
                  "region": "${genai_region}",
                  "oci_compartment_id": "${oci_genai_compartment_id}",
                  "oci_apiformat": "GENERIC"
                }');
            END;
            /

- changeSet:
    id: adb-005-profile-chat
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_CHAT', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI.CREATE_PROFILE(
                profile_name => 'BANKING_CHAT',
                attributes   =>
                '{
                  "provider": "oci",
                  "credential_name": "OCI_API_KEY_CRED",
                  "region": "${genai_region}",
                  "oci_compartment_id": "${oci_genai_compartment_id}",
                  "oci_apiformat": "GENERIC"
                }');
            END;
            /
```

- [ ] **Step 10.3: Append the vector index**

Append:

```yaml
# ----- 8. Vector index over the policy markdown bucket -----
- changeSet:
    id: adb-005-vector-index
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI.DROP_VECTOR_INDEX(index_name => 'BANKING_POLICY_INDEX', force => true);
                EXCEPTION WHEN OTHERS THEN IF SQLCODE != -20000 THEN RAISE; END IF;
              END;
              DBMS_CLOUD_AI.CREATE_VECTOR_INDEX(
                index_name => 'BANKING_POLICY_INDEX',
                attributes => '{"vector_db_provider":"oracle",
                                "location":"https://objectstorage.${genai_region}.oraclecloud.com/n/${rag_bucket_namespace}/b/${rag_bucket_name}/o/",
                                "object_storage_credential_name":"OCI_API_KEY_CRED",
                                "profile_name":"BANKING_RAG",
                                "chunk_size":1500,
                                "chunk_overlap":300}');
            END;
            /
```

- [ ] **Step 10.4: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/adb/005-select-ai-agents.yaml'))"`
Expected: silent exit.

- [ ] **Step 10.5: Commit**

```bash
git add database/liquibase/adb/005-select-ai-agents.yaml
git commit -m "feat(adb): add OCI GenAI credential, 5 Select AI profiles, and vector index"
```

---

## Task 11: ADB 005 — tools + agents + tasks + team

**Files:**

- Modify: `database/liquibase/adb/005-select-ai-agents.yaml` (append the agent layer)
- Modify: `database/liquibase/adb/db.changelog-master.yaml` (include 005)

- [ ] **Step 11.1: Append the 4 tools**

Append to `database/liquibase/adb/005-select-ai-agents.yaml`:

```yaml
# ----- 9. Tools (4): SQL × 3, RAG × 1 -----
- changeSet:
    id: adb-005-tools
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TOOL(tool_name => 'TXN_SQL_TOOL',         force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TOOL(tool_name => 'COMPLIANCE_SQL_TOOL',  force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TOOL(tool_name => 'COMPLIANCE_RAG_TOOL',  force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TOOL(tool_name => 'CARE_SQL_TOOL',        force => true); EXCEPTION WHEN OTHERS THEN NULL; END;

              DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
                tool_name  => 'TXN_SQL_TOOL',
                attributes => '{"tool_type":"SQL","tool_params":{"profile_name":"BANKING_NL2SQL_TXN"}}'
              );
              DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
                tool_name  => 'COMPLIANCE_SQL_TOOL',
                attributes => '{"tool_type":"SQL","tool_params":{"profile_name":"BANKING_NL2SQL_COMPLIANCE"}}'
              );
              DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
                tool_name  => 'COMPLIANCE_RAG_TOOL',
                attributes => '{"tool_type":"RAG","tool_params":{"profile_name":"BANKING_RAG"}}'
              );
              DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
                tool_name  => 'CARE_SQL_TOOL',
                attributes => '{"tool_type":"SQL","tool_params":{"profile_name":"BANKING_NL2SQL_CARE"}}'
              );
            END;
            /
```

- [ ] **Step 11.2: Append the 4 agents**

Append:

```yaml
# ----- 10. Agents (4) -----
- changeSet:
    id: adb-005-agents
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_AGENT(agent_name => 'TRANSACTION_ANALYST',    force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_AGENT(agent_name => 'COMPLIANCE_OFFICER',     force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_AGENT(agent_name => 'CUSTOMER_CARE_LIAISON',  force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_AGENT(agent_name => 'CASE_SYNTHESIZER',       force => true); EXCEPTION WHEN OTHERS THEN NULL; END;

              DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
                agent_name => 'TRANSACTION_ANALYST',
                attributes => '{
                  "profile_name": "BANKING_NL2SQL_TXN",
                  "role": "You are a bank transaction analyst. When given a customer or transaction question, retrieve concrete facts only: which accounts, which transactions, amounts, dates, counterparties, geo, status. Do not interpret, recommend, or speculate. Return findings as a bullet list with one fact per line.",
                  "enable_human_tool": "false"
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
                agent_name => 'COMPLIANCE_OFFICER',
                attributes => '{
                  "profile_name": "BANKING_NL2SQL_COMPLIANCE",
                  "role": "You are a bank compliance officer. Given transaction facts from the analyst, identify which AML, KYC, Reg E, OFAC, or fraud rules and policies apply. Use the SQL tool for rule data and the RAG tool for policy text. Cite rule codes (e.g. R-AML-005) and quote relevant policy sections. Classify each finding as INFO, WARNING, or VIOLATION.",
                  "enable_human_tool": "false"
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
                agent_name => 'CUSTOMER_CARE_LIAISON',
                attributes => '{
                  "profile_name": "BANKING_NL2SQL_CARE",
                  "role": "You are a customer care liaison. Given a customer context, retrieve customer KYC status, risk tier, country, and join date from V_BNK_CUSTOMERS. Note: support-ticket lookups are temporarily unavailable; do not fabricate ticket data. If the request requires ticket history, say so explicitly.",
                  "enable_human_tool": "false"
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
                agent_name => 'CASE_SYNTHESIZER',
                attributes => '{
                  "profile_name": "BANKING_CHAT",
                  "role": "You are a senior banking case manager. Given transaction facts, a compliance assessment, and customer-care context, produce a final case file with three sections: Findings, Risk rating (LOW/MEDIUM/HIGH), Recommended next actions. Maximum 200 words. Be concrete; do not invent facts.",
                  "enable_human_tool": "false"
                }'
              );
            END;
            /
```

- [ ] **Step 11.3: Append the 4 tasks**

Append:

```yaml
# ----- 11. Tasks (4) -----
- changeSet:
    id: adb-005-tasks
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TASK(task_name => 'PULL_TXN_FACTS',      force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TASK(task_name => 'ASSESS_COMPLIANCE',   force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TASK(task_name => 'GATHER_CARE_CONTEXT', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TASK(task_name => 'SYNTHESIZE_CASE',     force => true); EXCEPTION WHEN OTHERS THEN NULL; END;

              DBMS_CLOUD_AI_AGENT.CREATE_TASK(
                task_name  => 'PULL_TXN_FACTS',
                attributes => '{
                  "instruction": "Retrieve transactional facts relevant to the user request: {query}. Use the SQL tool. Return only facts; do not analyse.",
                  "tools": ["TXN_SQL_TOOL"]
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_TASK(
                task_name  => 'ASSESS_COMPLIANCE',
                attributes => '{
                  "instruction": "Given the user request {query} and the transactional facts in your input, identify all applicable rules and policies. Use the SQL tool for rule lookups and the RAG tool for policy citations. List each finding with rule code, severity, and a short explanation.",
                  "tools": ["COMPLIANCE_SQL_TOOL", "COMPLIANCE_RAG_TOOL"],
                  "input": "PULL_TXN_FACTS"
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_TASK(
                task_name  => 'GATHER_CARE_CONTEXT',
                attributes => '{
                  "instruction": "Given the user request {query} and the transactional facts in your input, retrieve relevant customer KYC status from V_BNK_CUSTOMERS. Support-ticket lookups are unavailable in this build.",
                  "tools": ["CARE_SQL_TOOL"],
                  "input": "PULL_TXN_FACTS"
                }'
              );

              DBMS_CLOUD_AI_AGENT.CREATE_TASK(
                task_name  => 'SYNTHESIZE_CASE',
                attributes => '{
                  "instruction": "Compose the final case file for the user request: {query}. Combine the compliance assessment and the customer-care context provided in your input. Output sections: Findings, Risk rating, Recommended next actions.",
                  "input": "ASSESS_COMPLIANCE,GATHER_CARE_CONTEXT"
                }'
              );
            END;
            /
```

- [ ] **Step 11.4: Append the team**

Append:

```yaml
# ----- 12. Team -----
- changeSet:
    id: adb-005-team
    author: adbsidecar
    runOnChange: true
    changes:
      - sql:
          endDelimiter: "/"
          splitStatements: false
          sql: |
            BEGIN
              BEGIN DBMS_CLOUD_AI_AGENT.DROP_TEAM(team_name => 'BANKING_INVESTIGATION_TEAM', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
              DBMS_CLOUD_AI_AGENT.CREATE_TEAM(
                team_name  => 'BANKING_INVESTIGATION_TEAM',
                attributes => '{
                  "agents": [
                    {"name":"TRANSACTION_ANALYST",    "task":"PULL_TXN_FACTS"},
                    {"name":"COMPLIANCE_OFFICER",     "task":"ASSESS_COMPLIANCE"},
                    {"name":"CUSTOMER_CARE_LIAISON",  "task":"GATHER_CARE_CONTEXT"},
                    {"name":"CASE_SYNTHESIZER",       "task":"SYNTHESIZE_CASE"}
                  ],
                  "process": "sequential"
                }'
              );
            END;
            /
```

- [ ] **Step 11.5: Wire master changelog**

Replace `database/liquibase/adb/db.changelog-master.yaml` with:

```yaml
databaseChangeLog:
  - include: { file: 001-init.yaml, relativeToChangelogFile: true }
  - include: { file: 002-db-links.yaml, relativeToChangelogFile: true }
  - include: { file: 003-measurements.yaml, relativeToChangelogFile: true }
  - include:
      { file: 004-banking-views-extended.yaml, relativeToChangelogFile: true }
  - include: { file: 005-select-ai-agents.yaml, relativeToChangelogFile: true }
```

- [ ] **Step 11.6: YAML lint passes**

Run: `python -c "import yaml; yaml.safe_load(open('database/liquibase/adb/005-select-ai-agents.yaml'))" && python -c "import yaml; yaml.safe_load(open('database/liquibase/adb/db.changelog-master.yaml'))"`
Expected: silent exit.

- [ ] **Step 11.7: Commit**

```bash
git add database/liquibase/adb/005-select-ai-agents.yaml database/liquibase/adb/db.changelog-master.yaml
git commit -m "feat(adb): add 4 tools, 4 agents, 4 tasks, BANKING_INVESTIGATION_TEAM"
```

---

## Task 12: Ansible — upload RAG docs + run new Liquibase changelogs

**Files:**

- Modify: `deploy/ansible/databases/server.yaml` and/or roles under `deploy/ansible/databases/roles/`

The existing playbook already runs Liquibase against ADB and does Mongo init. Two new responsibilities:

1. Upload the 5 markdown docs from `database/banking-policy-docs/` to the `banking-rag-docs` bucket.
2. Make sure the Liquibase invocation picks up the new master changelog entries (Tasks 6 + 11 already updated the master; this step is mostly making sure the file ships to the runner and that the substituted properties from Task 9 are passed via `--changelog-parameters` or `liquibase.properties`).

- [ ] **Step 12.1: Add the upload task**

Add a task in `deploy/ansible/databases/server.yaml` (before the Liquibase step that runs against ADB):

```yaml
- name: Upload banking policy docs to RAG bucket
  ansible.builtin.command:
    cmd: >
      oci os object put
      --bucket-name "{{ rag_bucket_name }}"
      --namespace "{{ rag_bucket_namespace }}"
      --file "{{ item }}"
      --force
  loop: "{{ lookup('fileglob', '../../database/banking-policy-docs/*.md', wantlist=True) }}"
  changed_when: true
```

(If the OCI Ansible collection is preferred and installed, swap for `community.general.oci_object_storage_object`.)

- [ ] **Step 12.2: Smoke check — `ansible-playbook --syntax-check`**

Run: `ansible-playbook --syntax-check deploy/ansible/databases/server.yaml`
Expected: `playbook: deploy/ansible/databases/server.yaml`, no errors.

- [ ] **Step 12.3: Smoke check — agent team responds (post-deploy, manual)**

After a successful `terraform apply` + Ansible run, SSH to the ops bastion and run:

```bash
sql -name admin -s <<'SQL'
SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(
         'BANKING_INVESTIGATION_TEAM',
         'List Carol Diaz outbound wire transactions in the last 30 days.',
         '{"conversation_id":"smoke-1"}') AS result FROM DUAL;
SQL
```

Expected: a non-empty `result` CLOB; subsequent
`SELECT TASK_NAME, STATE FROM USER_AI_AGENT_TASK_HISTORY WHERE TEAM_EXEC_ID = (SELECT MAX(TEAM_EXEC_ID) FROM USER_AI_AGENT_TASK_HISTORY)`
shows 4 tasks.

- [ ] **Step 12.4: Commit**

```bash
git add deploy/ansible/databases/
git commit -m "feat(ansible): upload RAG docs and run Select AI Agent Liquibase changelog"
```

---

## Task 13: Backend DTOs

**Files:**

- Create: `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentRunRequest.java`
- Create: `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentRunResponse.java`
- Create: `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/AgentTrace.java`

Java records — concise immutable carriers, JSON-serialised by Jackson at the controller boundary.

- [ ] **Step 13.1: Write `AgentRunRequest`**

```java
package dev.victormartin.adbsidecar.back.agents.dto;

public record AgentRunRequest(String prompt, String conversationId) {}
```

- [ ] **Step 13.2: Write `AgentTrace` (with nested records)**

```java
package dev.victormartin.adbsidecar.back.agents.dto;

import java.util.List;

public record AgentTrace(
        String teamExecId,
        String teamName,
        String state,
        List<TaskTrace> tasks,
        List<ToolTrace> tools) {

    public record TaskTrace(
            String agentName,
            String taskName,
            int taskOrder,
            String input,
            String result,
            String state,
            long durationMillis) {}

    public record ToolTrace(
            String agentName,
            String toolName,
            String taskName,
            int taskOrder,
            String input,
            String output,
            String toolOutput,
            long durationMillis) {}
}
```

- [ ] **Step 13.3: Write `AgentRunResponse`**

```java
package dev.victormartin.adbsidecar.back.agents.dto;

public record AgentRunResponse(
        String prompt,
        String answer,
        String conversationId,
        long elapsedMillis,
        AgentTrace trace) {}
```

- [ ] **Step 13.4: Compile passes**

Run: `cd src/backend && ./gradlew compileJava && cd ../..`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13.5: Commit**

```bash
git add src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/dto/
git commit -m "feat(back): add agent run DTOs (AgentRunRequest, AgentRunResponse, AgentTrace)"
```

---

## Task 14: Backend `AgentsService` (TDD)

**Files:**

- Create: `src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsServiceTest.java`
- Create: `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsService.java`

The service issues `RUN_TEAM`, then assembles a trace by querying the `USER_AI_AGENT_*` catalog views by `team_exec_id`. Note Oracle's typo `COVERSATION_PARAM` (sic) on `USER_AI_AGENT_TASK_HISTORY` — used verbatim in the SQL.

- [ ] **Step 14.1: Write the failing test**

Create `src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsServiceTest.java`:

```java
package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import dev.victormartin.adbsidecar.back.agents.dto.AgentTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentsServiceTest {

    private JdbcTemplate jdbc;
    private AgentsService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new AgentsService(jdbc, "BANKING_INVESTIGATION_TEAM");
    }

    @Test
    void runTeam_calls_run_team_with_team_prompt_and_conversation_id() {
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenReturn("Final answer.");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenReturn("EXEC-42");
        when(jdbc.queryForMap(contains("USER_AI_AGENT_TEAM_HISTORY"), any()))
                .thenReturn(Map.of("TEAM_NAME", "BANKING_INVESTIGATION_TEAM", "STATE", "SUCCEEDED"));
        when(jdbc.query(contains("USER_AI_AGENT_TASK_HISTORY"), any(RowMapper.class), any()))
                .thenReturn(List.<AgentTrace.TaskTrace>of());
        when(jdbc.query(contains("USER_AI_AGENT_TOOL_HISTORY"), any(RowMapper.class), any()))
                .thenReturn(List.<AgentTrace.ToolTrace>of());

        AgentRunResponse resp = service.runTeam("Hello", "conv-1");

        assertThat(resp.answer()).isEqualTo("Final answer.");
        assertThat(resp.conversationId()).isEqualTo("conv-1");
        assertThat(resp.trace()).isNotNull();
        assertThat(resp.trace().teamExecId()).isEqualTo("EXEC-42");

        verify(jdbc).queryForObject(
                contains("DBMS_CLOUD_AI_AGENT.RUN_TEAM"),
                eq(String.class),
                eq("BANKING_INVESTIGATION_TEAM"),
                eq("Hello"),
                eq("{\"conversation_id\":\"conv-1\"}"));
    }

    @Test
    void runTeam_generates_conversation_id_when_absent() {
        when(jdbc.queryForObject(anyString(), eq(String.class), any(), any(), any()))
                .thenReturn("ok");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenReturn(null);
        AgentRunResponse resp = service.runTeam("Hi", null);
        assertThat(resp.conversationId()).matches("[0-9a-f-]{36}");
        assertThat(resp.trace()).isNull();
    }

    @Test
    void runTeam_returns_null_trace_when_catalog_query_fails() {
        when(jdbc.queryForObject(contains("RUN_TEAM"), eq(String.class), any(), any(), any()))
                .thenReturn("ok");
        when(jdbc.queryForObject(contains("TEAM_EXEC_ID"), eq(String.class), any()))
                .thenThrow(new RuntimeException("ORA-00942"));
        AgentRunResponse resp = service.runTeam("Hi", "conv-2");
        assertThat(resp.trace()).isNull();
        assertThat(resp.answer()).isEqualTo("ok");
    }
}
```

- [ ] **Step 14.2: Run the test — should fail to compile**

Run: `cd src/backend && ./gradlew test --tests AgentsServiceTest`
Expected: compile error — `AgentsService` does not exist.

- [ ] **Step 14.3: Implement `AgentsService`**

Create `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsService.java`:

```java
package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import dev.victormartin.adbsidecar.back.agents.dto.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentsService {

    private static final Logger log = LoggerFactory.getLogger(AgentsService.class);

    private static final String RUN_TEAM_SQL =
            "SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(?, ?, ?) FROM DUAL";

    // NOTE: Oracle typo on the catalog column name — COVERSATION_PARAM (sic).
    // Do not "fix" — the catalog itself spells it that way.
    private static final String RESOLVE_EXEC_ID_SQL = """
            SELECT TEAM_EXEC_ID FROM (
                SELECT DISTINCT TEAM_EXEC_ID, START_DATE
                FROM USER_AI_AGENT_TASK_HISTORY
                WHERE JSON_VALUE(COVERSATION_PARAM, '$.conversation_id') = ?
                ORDER BY START_DATE DESC
            ) WHERE ROWNUM = 1
            """;

    private static final String TEAM_HISTORY_SQL =
            "SELECT TEAM_NAME, STATE FROM USER_AI_AGENT_TEAM_HISTORY WHERE TEAM_EXEC_ID = ?";

    private static final String TASK_HISTORY_SQL = """
            SELECT AGENT_NAME, TASK_NAME, TASK_ORDER, INPUT, RESULT, STATE,
                   EXTRACT(DAY FROM (END_DATE - START_DATE)) * 86400000 +
                   EXTRACT(HOUR FROM (END_DATE - START_DATE)) * 3600000 +
                   EXTRACT(MINUTE FROM (END_DATE - START_DATE)) * 60000 +
                   ROUND(EXTRACT(SECOND FROM (END_DATE - START_DATE)) * 1000) AS DURATION_MS
            FROM USER_AI_AGENT_TASK_HISTORY
            WHERE TEAM_EXEC_ID = ?
            ORDER BY TASK_ORDER
            """;

    private static final String TOOL_HISTORY_SQL = """
            SELECT AGENT_NAME, TOOL_NAME, TASK_NAME, TASK_ORDER, INPUT, OUTPUT, TOOL_OUTPUT,
                   EXTRACT(DAY FROM (END_DATE - START_DATE)) * 86400000 +
                   EXTRACT(HOUR FROM (END_DATE - START_DATE)) * 3600000 +
                   EXTRACT(MINUTE FROM (END_DATE - START_DATE)) * 60000 +
                   ROUND(EXTRACT(SECOND FROM (END_DATE - START_DATE)) * 1000) AS DURATION_MS
            FROM USER_AI_AGENT_TOOL_HISTORY
            WHERE TEAM_EXEC_ID = ?
            ORDER BY TASK_ORDER, START_DATE
            """;

    private final JdbcTemplate jdbc;
    private final String teamName;

    public AgentsService(JdbcTemplate jdbc,
                         @Value("${selectai.agents.team:BANKING_INVESTIGATION_TEAM}") String teamName) {
        this.jdbc = jdbc;
        this.teamName = teamName;
    }

    public AgentRunResponse runTeam(String prompt, String conversationIdOrNull) {
        String conversationId = conversationIdOrNull != null
                ? conversationIdOrNull
                : UUID.randomUUID().toString();
        String paramsJson = "{\"conversation_id\":\"" + conversationId + "\"}";

        long t0 = System.currentTimeMillis();
        String answer = jdbc.queryForObject(RUN_TEAM_SQL, String.class, teamName, prompt, paramsJson);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("RUN_TEAM completed in {}ms (conversation={}, team={})", elapsed, conversationId, teamName);

        AgentTrace trace = null;
        try {
            String execId = jdbc.queryForObject(RESOLVE_EXEC_ID_SQL, String.class, conversationId);
            if (execId != null) {
                trace = buildTrace(execId);
            }
        } catch (Exception e) {
            log.warn("Trace assembly failed for conversation {}: {}", conversationId, e.getMessage());
        }

        return new AgentRunResponse(prompt, answer, conversationId, elapsed, trace);
    }

    private AgentTrace buildTrace(String execId) {
        Map<String, Object> team = jdbc.queryForMap(TEAM_HISTORY_SQL, execId);
        List<AgentTrace.TaskTrace> tasks = jdbc.query(TASK_HISTORY_SQL, (rs, n) -> new AgentTrace.TaskTrace(
                rs.getString("AGENT_NAME"),
                rs.getString("TASK_NAME"),
                rs.getInt("TASK_ORDER"),
                rs.getString("INPUT"),
                rs.getString("RESULT"),
                rs.getString("STATE"),
                rs.getLong("DURATION_MS")), execId);
        List<AgentTrace.ToolTrace> tools = jdbc.query(TOOL_HISTORY_SQL, (rs, n) -> new AgentTrace.ToolTrace(
                rs.getString("AGENT_NAME"),
                rs.getString("TOOL_NAME"),
                rs.getString("TASK_NAME"),
                rs.getInt("TASK_ORDER"),
                rs.getString("INPUT"),
                rs.getString("OUTPUT"),
                rs.getString("TOOL_OUTPUT"),
                rs.getLong("DURATION_MS")), execId);
        return new AgentTrace(execId, (String) team.get("TEAM_NAME"), (String) team.get("STATE"), tasks, tools);
    }
}
```

- [ ] **Step 14.4: Run the test — should pass**

Run: `cd src/backend && ./gradlew test --tests AgentsServiceTest`
Expected: 3 tests PASS.

- [ ] **Step 14.5: Commit**

```bash
git add src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsService.java src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsServiceTest.java
git commit -m "feat(back): add AgentsService with RUN_TEAM and trace assembly"
```

---

## Task 15: Backend `AgentsController` (TDD)

**Files:**

- Create: `src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsControllerTest.java`
- Create: `src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsController.java`

Thin REST layer over `AgentsService`. Validates prompt (length ≤ 1000, allowed character set). 400 on validation failure, 502 on `JdbcTemplate` failure.

- [ ] **Step 15.1: Write the failing controller test**

```java
package dev.victormartin.adbsidecar.back.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunRequest;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentsController.class)
class AgentsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AgentsService service;

    @Test
    void post_agents_returns_200_with_run_response() throws Exception {
        when(service.runTeam(any(), any()))
                .thenReturn(new AgentRunResponse("hi", "answer", "conv-1", 100L, null));
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("hi", "conv-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.elapsedMillis").value(100));
    }

    @Test
    void post_agents_returns_400_on_blank_prompt() throws Exception {
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("   ", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_agents_returns_400_on_too_long_prompt() throws Exception {
        String tooLong = "a".repeat(1001);
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest(tooLong, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_agents_returns_502_on_service_exception() throws Exception {
        when(service.runTeam(any(), any())).thenThrow(new RuntimeException("ORA-29024"));
        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new AgentRunRequest("hi", null))))
                .andExpect(status().isBadGateway());
    }
}
```

- [ ] **Step 15.2: Run — fails to compile (controller missing)**

Run: `cd src/backend && ./gradlew test --tests AgentsControllerTest`
Expected: compile error.

- [ ] **Step 15.3: Implement `AgentsController`**

```java
package dev.victormartin.adbsidecar.back.agents;

import dev.victormartin.adbsidecar.back.agents.dto.AgentRunRequest;
import dev.victormartin.adbsidecar.back.agents.dto.AgentRunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentsController {

    private static final Logger log = LoggerFactory.getLogger(AgentsController.class);
    private static final int MAX_PROMPT_LEN = 1000;
    private static final Pattern ALLOWED_PROMPT =
            Pattern.compile("^[\\p{L}\\p{N}\\s,\\.\\-()\\?!':;\"/&#%$]+$");

    private final AgentsService service;

    public AgentsController(AgentsService service) {
        this.service = service;
    }

    @PostMapping
    public AgentRunResponse run(@RequestBody AgentRunRequest req) {
        String prompt = validatePrompt(req.prompt());
        log.info("Agent run: prompt='{}' conversation={}", prompt, req.conversationId());
        return service.runTeam(prompt, req.conversationId());
    }

    private String validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt cannot be empty");
        String trimmed = prompt.trim();
        if (trimmed.length() > MAX_PROMPT_LEN) throw new IllegalArgumentException("Prompt too long (max " + MAX_PROMPT_LEN + " characters)");
        if (!ALLOWED_PROMPT.matcher(trimmed).matches()) throw new IllegalArgumentException("Prompt contains invalid characters");
        return trimmed;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> upstream(RuntimeException e) {
        log.error("Agent run failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 15.4: Run — passes**

Run: `cd src/backend && ./gradlew test --tests AgentsControllerTest`
Expected: 4 tests PASS.

- [ ] **Step 15.5: Commit**

```bash
git add src/backend/src/main/java/dev/victormartin/adbsidecar/back/agents/AgentsController.java src/backend/src/test/java/dev/victormartin/adbsidecar/back/agents/AgentsControllerTest.java
git commit -m "feat(back): add AgentsController POST /api/v1/agents with prompt validation"
```

---

## Task 16: Backend application config

**Files:** modify `src/backend/src/main/resources/application.yaml` (or its `.j2` if present).

- [ ] **Step 16.1: Add the `selectai` block**

```yaml
selectai:
  agents:
    team: BANKING_INVESTIGATION_TEAM
```

- [ ] **Step 16.2: Build passes**

Run: `cd src/backend && ./gradlew build && cd ../..`
Expected: `BUILD SUCCESSFUL` (all tests including AgentsServiceTest + AgentsControllerTest).

- [ ] **Step 16.3: Commit**

```bash
git add src/backend/src/main/resources/
git commit -m "feat(back): wire selectai.agents.team into application config"
```

---

## Task 17: Frontend route rename + delete `future-page` + nav update

**Files:**

- Delete: `src/frontend/src/app/pages/future-page.component.ts`
- Modify: `src/frontend/src/app/app.routes.ts`
- Modify: `src/frontend/src/app/nav.component.ts`

- [ ] **Step 17.1: Update `app.routes.ts`** — replace the `future` entry:

```ts
{
  path: 'agents',
  loadComponent: () =>
    import('./pages/agents-page.component').then((m) => m.AgentsPageComponent),
},
```

- [ ] **Step 17.2: Update `nav.component.ts`** — change the AI features entry to `{ path: '/agents', label: 'Select AI Agents' }` (match the existing entries' shape — see how `/sidecar` is declared).

- [ ] **Step 17.3: Delete the placeholder**

```bash
rm src/frontend/src/app/pages/future-page.component.ts
```

- [ ] **Step 17.4: Defer build check**

Build will fail until Task 19 creates the new component. Do not run `npm run build` here; the green build comes at the end of Task 19.

- [ ] **Step 17.5: Commit**

```bash
git add src/frontend/src/app/app.routes.ts src/frontend/src/app/nav.component.ts
git rm src/frontend/src/app/pages/future-page.component.ts
git commit -m "refactor(front): rename /future route to /agents and remove placeholder"
```

---

## Task 18: Frontend `AgentsService`

**Files:** create `src/frontend/src/app/services/agents.service.ts`.

- [ ] **Step 18.1: Write the service**

```typescript
import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";

export interface AgentRunRequest {
  prompt: string;
  conversationId?: string;
}

export interface AgentTaskTrace {
  agentName: string;
  taskName: string;
  taskOrder: number;
  input: string;
  result: string;
  state: string;
  durationMillis: number;
}

export interface AgentToolTrace {
  agentName: string;
  toolName: string;
  taskName: string;
  taskOrder: number;
  input: string;
  output: string;
  toolOutput: string;
  durationMillis: number;
}

export interface AgentTrace {
  teamExecId: string;
  teamName: string;
  state: string;
  tasks: AgentTaskTrace[];
  tools: AgentToolTrace[];
}

export interface AgentRunResponse {
  prompt: string;
  answer: string;
  conversationId: string;
  elapsedMillis: number;
  trace: AgentTrace | null;
}

@Injectable({ providedIn: "root" })
export class AgentsService {
  private http = inject(HttpClient);

  run(req: AgentRunRequest): Observable<AgentRunResponse> {
    return this.http.post<AgentRunResponse>("/api/v1/agents", req);
  }
}
```

- [ ] **Step 18.2: Commit**

```bash
git add src/frontend/src/app/services/agents.service.ts
git commit -m "feat(front): add AgentsService HTTP client with typed Trace interfaces"
```

---

## Task 19: Frontend `AgentsPageComponent`

**Files:** create `src/frontend/src/app/pages/agents-page.component.ts`.

- [ ] **Step 19.1: Write the component**

```typescript
import { Component, signal } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { AgentsService, AgentRunResponse } from "../services/agents.service";

interface ChatTurn {
  role: "user" | "assistant" | "error";
  text: string;
  trace?: AgentRunResponse["trace"];
  elapsedMillis?: number;
  showTrace?: boolean;
}

const CHIPS: string[] = [
  "Are there any suspicious patterns on Carol Diaz's accounts this month?",
  "Bob Chen disputed a $230 charge — what should we do?",
  "Summarise Alice Morgan's risk profile.",
  "Why is Jamal Reed's checking account frozen?",
  "What policies apply to international wires above $10K?",
];

@Component({
  selector: "app-agents-page",
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <h2>Select AI Agents</h2>
    <p class="subtitle">
      A four-agent banking investigation team running entirely inside ADB. One
      prompt fans out to a Transaction Analyst, a Compliance Officer (SQL +
      RAG), a Customer Care Liaison, and a Case Synthesiser.
    </p>
    <div class="chips">
      <button *ngFor="let c of chips" (click)="setPrompt(c)">{{ c }}</button>
    </div>
    <div class="conversation">
      <div *ngFor="let turn of turns()" [class]="'bubble ' + turn.role">
        <div class="text">{{ turn.text }}</div>
        <div
          *ngIf="turn.role === 'assistant' && turn.elapsedMillis"
          class="badge"
        >
          {{ turn.elapsedMillis }} ms
        </div>
        <button
          *ngIf="turn.trace"
          (click)="turn.showTrace = !turn.showTrace"
          class="trace-toggle"
        >
          {{ turn.showTrace ? "Hide" : "Show" }} execution trace ({{
            turn.trace.tasks.length
          }}
          tasks, {{ turn.trace.tools.length }} tool calls)
        </button>
        <div *ngIf="turn.showTrace && turn.trace" class="trace">
          <div *ngFor="let t of turn.trace.tasks" class="task">
            <div class="task-header">
              Task #{{ t.taskOrder }} {{ t.agentName }} ·
              {{ t.durationMillis }} ms · {{ t.state }}
            </div>
            <details>
              <summary>Input</summary>
              <pre>{{ t.input }}</pre>
            </details>
            <ng-container
              *ngFor="let tool of toolsFor(turn.trace, t.taskOrder)"
            >
              <details>
                <summary>
                  Tool: {{ tool.toolName }} ({{ tool.durationMillis }} ms)
                </summary>
                <pre>{{ tool.input }}</pre>
                <pre>{{ tool.output }}</pre>
              </details>
            </ng-container>
            <details>
              <summary>Result</summary>
              <pre>{{ t.result }}</pre>
            </details>
          </div>
        </div>
      </div>
      <div *ngIf="loading()" class="bubble assistant"><em>Thinking…</em></div>
    </div>
    <div class="composer">
      <textarea
        [(ngModel)]="promptModel"
        rows="3"
        placeholder="Ask the team..."
      ></textarea>
      <button (click)="send()" [disabled]="loading() || !promptModel.trim()">
        Send →
      </button>
      <button (click)="newConversation()" class="secondary">
        New conversation
      </button>
    </div>
  `,
  styles: [
    `
      .subtitle {
        color: #555;
        margin-bottom: 16px;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 16px;
      }
      .chips button {
        padding: 6px 12px;
        border-radius: 16px;
        border: 1px solid #ccc;
        background: #f7f7f9;
        cursor: pointer;
      }
      .chips button:hover {
        background: #eef;
      }
      .conversation {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-bottom: 16px;
      }
      .bubble {
        padding: 12px 16px;
        border-radius: 8px;
        max-width: 80%;
      }
      .bubble.user {
        background: #e7f0ff;
        align-self: flex-end;
      }
      .bubble.assistant {
        background: #f4f4f6;
        align-self: flex-start;
      }
      .bubble.error {
        background: #fde7e7;
        align-self: flex-start;
      }
      .text {
        white-space: pre-wrap;
      }
      .badge {
        display: inline-block;
        margin-top: 6px;
        padding: 2px 8px;
        background: #ddd;
        border-radius: 4px;
        font-size: 0.85em;
      }
      .trace-toggle {
        margin-top: 8px;
        background: none;
        border: 1px dashed #888;
        padding: 4px 10px;
        cursor: pointer;
      }
      .trace {
        margin-top: 8px;
        border-top: 1px solid #ccc;
        padding-top: 8px;
      }
      .task {
        margin: 8px 0;
      }
      .task-header {
        font-weight: 600;
      }
      pre {
        background: #fafafa;
        padding: 8px;
        overflow-x: auto;
        max-height: 200px;
      }
      .composer {
        display: flex;
        gap: 8px;
        align-items: stretch;
      }
      .composer textarea {
        flex: 1;
        padding: 8px;
      }
      .composer button {
        padding: 8px 16px;
      }
      .composer button.secondary {
        background: none;
        color: #666;
        border: 1px solid #ccc;
      }
    `,
  ],
})
export class AgentsPageComponent {
  chips = CHIPS;
  promptModel = "";
  conversationId = signal<string | undefined>(undefined);
  turns = signal<ChatTurn[]>([]);
  loading = signal(false);

  constructor(private agents: AgentsService) {}

  setPrompt(text: string) {
    this.promptModel = text;
  }

  send() {
    const text = this.promptModel.trim();
    if (!text) return;
    this.turns.update((t) => [...t, { role: "user", text }]);
    this.promptModel = "";
    this.loading.set(true);

    this.agents
      .run({ prompt: text, conversationId: this.conversationId() })
      .subscribe({
        next: (resp) => {
          this.conversationId.set(resp.conversationId);
          this.turns.update((t) => [
            ...t,
            {
              role: "assistant",
              text: resp.answer,
              trace: resp.trace,
              elapsedMillis: resp.elapsedMillis,
              showTrace: false,
            },
          ]);
          this.loading.set(false);
        },
        error: (err) => {
          const msg = err?.error?.error ?? err?.message ?? "Agent run failed";
          this.turns.update((t) => [...t, { role: "error", text: msg }]);
          this.loading.set(false);
        },
      });
  }

  newConversation() {
    this.conversationId.set(undefined);
    this.turns.set([]);
  }

  toolsFor(trace: AgentRunResponse["trace"], taskOrder: number) {
    return trace ? trace.tools.filter((t) => t.taskOrder === taskOrder) : [];
  }
}
```

- [ ] **Step 19.2: Build passes**

Run: `cd src/frontend && npm run build && cd ../..`
Expected: Angular `BUILD SUCCESSFUL` (Tasks 17 + 18 + 19 now form a complete chain).

- [ ] **Step 19.3: Manual smoke check (post-deploy)**

After full deploy:

1. Open `https://<lb_public_ip>/agents`.
2. Click the first chip — _"Are there any suspicious patterns on Carol Diaz's accounts this month?"_.
3. Wait for the assistant bubble (20–60 s).
4. Click "Show execution trace" — verify 4 task cards with non-zero durations and visible tool input/output for `TXN_SQL_TOOL` and `COMPLIANCE_RAG_TOOL`.
5. Type a follow-up: _"focus on the OFAC angle"_. Verify same `conversationId` is reused (network panel shows the value the previous turn returned).

- [ ] **Step 19.4: Commit**

```bash
git add src/frontend/src/app/pages/agents-page.component.ts
git commit -m "feat(front): add agents page with chat + execution-trace UI"
```

---

## Task 20: README updates

**Files:** modify `README.md` per spec §16.1, §16.2, §16.3.

- [ ] **Step 20.1: Replace the `/future` bullet** — apply spec §16.1 verbatim.

- [ ] **Step 20.2: Update the existing mermaid** — apply spec §16.2 verbatim (front node label + adb node label).

- [ ] **Step 20.3: Insert the new "Select AI Agents" section** — copy the rendered content from spec §16.3 between the existing `/sidecar` screenshot section and the `## Architecture` heading. Includes 2-3 prose paragraphs, the team table, the sequence-diagram mermaid, the data-flow mermaid, the demo question list, and the deferred-Mongo paragraph.

- [ ] **Step 20.4: Smoke check — markdown renders**

Run: `python -m markdown README.md > /tmp/README.html && wc -c /tmp/README.html`
Expected: non-zero output, no errors.

- [ ] **Step 20.5: Commit**

```bash
git add README.md
git commit -m "docs(readme): describe Select AI Agents page with sequence and data-flow diagrams"
```

---

## Task 21: `docs/AGENTS_DEMO.md`

**Files:** create `docs/AGENTS_DEMO.md`.

- [ ] **Step 21.1: Write the runbook**

```markdown
# Select AI Agents — demo runbook

After a successful deploy, open the LB IP and click `/agents` in the nav.
Each of the five chips below maps to a deterministic narrative seeded into
the banking dataset. Expected high-level findings are listed so a runner
can spot-check that the trace is producing the right shape.

## Quick checks

- `curl http://<lb_public_ip>/api/v1/health` returns 200.
- The `/agents` page renders with 5 chips visible and an empty conversation.
- A new conversation gets a fresh `conversationId` on first response.

## Narrative 1 — Carol Diaz, structuring

**Chip:** _Are there any suspicious patterns on Carol Diaz's accounts this month?_

Expected:

- TxAnalyst surfaces 4 wires of $9,500–$9,800 to BY between 2026-04-09 and 2026-04-14.
- Compliance cites `R-AML-005` (structuring) and `R-OFAC-001` (BY high-risk),
  with at least one quoted line from `aml-and-ctr-procedures.md` §3.3.
- CARE returns Carol's KYC `VERIFIED`, risk `LOW`, joined 2017-11-04.
- Synthesiser concludes HIGH risk; recommends SAR review within 30 days.

## Narrative 2 — Bob Chen, Reg E dispute

**Chip:** _Bob Chen disputed a $230 charge — what should we do?_

Expected:

- TxAnalyst returns the two $230 Acme Hardware charges 4 minutes apart.
- Compliance cites `R-REGE-002` (60-day window) and quotes
  `reg-e-dispute-handling.md` §2 / §3.
- CARE returns Bob's KYC `VERIFIED`, risk `MEDIUM`.
- Synthesiser recommends provisional credit within 10 business days.

## Narrative 3 — Alice Morgan, risk profile

**Chip:** _Summarise Alice Morgan's risk profile._

Expected:

- TxAnalyst surfaces 5 cash deposits $9,000–$9,400 over 10 days.
- Compliance cites `R-AML-002` and `R-KYC-003`.
- CARE flags KYC `EXPIRED`, risk `HIGH`.
- Synthesiser recommends KYC refresh + EDD before next outbound activity.

## Narrative 4 — Jamal Reed, frozen account

**Chip:** _Why is Jamal Reed's checking account frozen?_

Expected:

- TxAnalyst returns 3 declined card auth in 1 hour, account `FROZEN`.
- Compliance cites `R-FRAUD-007` (velocity).
- CARE returns Jamal KYC `VERIFIED`, risk `LOW`.
- Synthesiser recommends card reissue + freeze release on identity confirm.

## Narrative 5 — policy-only

**Chip:** _What policies apply to international wires above $10K?_

Expected:

- TxAnalyst returns "no transaction context required."
- Compliance cites `R-WIRE-001` and quotes `wire-transfer-sop.md` §2 and `ofac-sanctions-screening.md` §3.
- CARE returns "no relevant context."
- Synthesiser composes a 1-paragraph guidance answer.

## Multi-turn check

After narrative 1, type _"focus on the OFAC angle"_. The next response
should reuse the same `conversationId` (visible in the network panel)
and produce a focused follow-up about Belarus.

## Deferred state — Mongo support tickets

Until `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md` is
resolved, the CARE agent does **not** see support tickets and answers
about ticket history return "support-ticket lookups are temporarily
unavailable." This is by design; flipping the switch is documented in
`docs/superpowers/specs/2026-04-28-select-ai-agents-design.md` §14.
```

- [ ] **Step 21.2: Commit**

```bash
git add docs/AGENTS_DEMO.md
git commit -m "docs(agents): add manual demo runbook for the five embedded narratives"
```

---

## Self-review

Spec coverage (against `docs/superpowers/specs/2026-04-28-select-ai-agents-design.md`):

- §1 Goal — Tasks 17/19/14/15 (page + endpoint) and Tasks 1–11 (data + ADB layer).
- §2 In-scope: route rename — Task 17. Backend endpoint — Tasks 13–16. ADB DDL — Tasks 10–11. Banking schema upgrade — Tasks 1–4. Mongo seed — Task 5. RAG markdown docs — Task 7. Demo gallery — Task 19. README updates — Task 20.
- §3 Architecture path: backend → JdbcTemplate.queryForObject(RUN*TEAM…) → trace assembly via `USER_AI_AGENT*\*` — Task 14.
- §4 Agent topology (4 agents, 4 tasks, 1 team, sequential, `input` chaining) — Task 11.
- §5 Five profiles, scoped object_lists — Task 10.
- §6 Vector index over 5 markdown docs — Task 7 + Task 10 vector-index changeset + Task 12 upload step.
- §7 Banking schema upgrade with narrative seeds — Tasks 1–5.
- §8 Idempotent guarded DROP-then-CREATE — applied throughout Tasks 10–11.
- §9 Backend AgentsController + AgentsService + DTOs + validation — Tasks 13–16.
- §10 Frontend route rename + chips + trace toggle + multi-turn — Tasks 17–19.
- §11 Terraform bucket + Ansible upload + Liquibase property substitution — Tasks 8, 9, 12.
- §12 Error handling — Tasks 14 (trace fallback), 15 (4xx/502).
- §13 Tests — Tasks 14, 15; manual demo plan — Task 21.
- §14 "Mongo flip-the-switch" — embedded as commented-out changeset (Task 6) + excluded `object_list` entry (Tasks 10–11).
- §15 Out-of-scope cleanups — left unchanged by design.
- §16 README updates — Task 20.

Type names align across tasks: `AgentsService`, `AgentsController`, `AgentRunRequest`, `AgentRunResponse`, `AgentTrace.TaskTrace`, `AgentTrace.ToolTrace` introduced in Tasks 13–15 with consistent naming. TypeScript interfaces in Task 18 mirror the Java DTOs field-for-field. No "TBD"/"TODO" strings.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-28-select-ai-agents.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Uses `superpowers:subagent-driven-development`.
2. **Inline Execution** — execute tasks in this session in batched checkpoints. Uses `superpowers:executing-plans`.

Which approach?
