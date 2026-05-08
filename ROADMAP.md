# Roadmap

Ordered low-hanging-fruit → most complex. Each item is self-contained
unless flagged otherwise; sequencing reflects effort and blast radius,
not dependency. The fraud dashboard (#7) is the one hard ordering
constraint — it consumes the property graph (#6).

---

## 1. Fix `bootstrap.tftpl` cloud-init re-run bug

`deploy/tf/modules/ops/userdata/bootstrap.tftpl` line 80 uses
`cat <<EOT >>`, so a cloud-init re-run concatenates a second JSON
object onto `/home/opc/ansible_params.json` and the Ansible
parser dies. Change `>>` to `>` so the file is overwritten.

Smallest possible task — one character — but it bites every time
the ops bastion is reprovisioned.

- Edit `deploy/tf/modules/ops/userdata/bootstrap.tftpl` line 80:
  `cat <<EOT >>` → `cat <<EOT >`.
- Verify on a fresh `terraform apply` of the ops module that the
  resulting `ansible_params.json` parses cleanly on first and
  second cloud-init pass.

Workaround until the fix lands (run on ops):

```bash
python3 -c "import json; raw=open('/home/opc/ansible_params.json').read().lstrip(); obj,_=json.JSONDecoder().raw_decode(raw); open('/home/opc/ansible_params.json','w').write(json.dumps(obj,indent=2))"
```

## 2. Relabel simulated production engine as Oracle 19c in docs/diagrams

The `databases` compute runs Oracle Database Free 26ai as the
container for the "production" Oracle. There is no Oracle free-tier
binary at 19c — Oracle's free lineage skipped from XE 21c straight
to Free 23c+. To keep the demo narrative aligned with a realistic
enterprise scenario, relabel the simulated production engine as
**Oracle 19c** in README diagrams, the architecture table, and
prose mentions; keep one discreet footnote noting that the actual
container is 26ai Free because no free 19c image exists.

Doc-only — no code, no infra, no Liquibase. Bubbled up because the
mismatch between the demo narrative and the diagrams is paper-cut
visible the moment anyone opens the README.

- Edit the four diagram / table references in `README.md`
  (current-system flowchart, agents flowchart, architecture
  table, network diagram).
- Update prose mentions of "Oracle Free 26ai" that refer to the
  simulated production engine — but **not** mentions of
  Autonomous Database 26ai (the AI sidecar), which stays 26ai.
- Add one discreet footnote / italic note explaining the cut-corner.

## 3. Blockchain tables for `transactions`

`transactions` is the OLTP source of truth on the simulated
production Oracle (see #2 for what "production" maps to in this
repo). The ledger belongs there, next to the source — not on ADB.
Convert the table to a blockchain table so it becomes tamper-evident
by configuration, with no app changes.

**Why blockchain over immutable.** Blockchain adds row-level hash
chaining (SHA2-512) and `DBMS_BLOCKCHAIN_TABLE.VERIFY_ROWS`, which
gives the cryptographic-tamper-evidence story the audit / compliance
demo needs. Immutable tables only block app-layer DML and have no
verification mechanism. The throughput overhead of chaining is
irrelevant at demo data volumes.

- Liquibase changeset under `database/liquibase/oracle/`:
  `CREATE BLOCKCHAIN TABLE transactions (...) NO DROP UNTIL n DAYS
IDLE NO DELETE UNTIL 16 DAYS AFTER INSERT HASHING USING
"SHA2_512" VERSION "v1"`.
- ADB side unchanged: `ORAFREE_LINK` reads a blockchain table the
  same as any normal table, so the existing federated views need
  no edits.
- Caveats inherited from the feature: distributed transactions and
  direct-path inserts are blocked (single-row INSERT only); chain
  verification (`DBMS_BLOCKCHAIN_TABLE.VERIFY_ROWS`) must run on
  the production Oracle, not from ADB.

**Dev iteration model.** The 16-day minimum on
`NO DELETE UNTIL n DAYS AFTER INSERT` is fixed by Oracle — once a
row is inserted, it cannot be deleted for 16 days, and most
`ALTER TABLE` shape changes are blocked. To keep Liquibase
iteration viable, split the changeset by Liquibase context:

- `context: dev` — plain `CREATE TABLE transactions (...)`. Iterate
  freely.
- `context: prod` — `CREATE BLOCKCHAIN TABLE ... NO DROP UNTIL 0
DAYS IDLE ...` initially, so the table can be dropped and
  recreated between deploys while empty. Once the POC schema
  stabilises, raise `NO DROP UNTIL` to **2–3 days** for a more
  realistic posture.

Fallback: `CREATE IMMUTABLE TABLE` only if the chosen Oracle binary
ships without blockchain support (rare on modern 19c / 26ai).

## 4. Layer-4 RAG track for `COMPLIANCE_OFFICER`

Add a `BANKING_RAG` profile + `BANKING_POLICY_INDEX` vector index +
`COMPLIANCE_RAG_TOOL`, wired into `COMPLIANCE_OFFICER` alongside
the existing SQL tool. Only the `005-select-ai-agents.yaml`
changeset needs the additions. Stays inside the Select AI agent
surface — no new endpoints, no separate semantic-search API.

- Decide embedding source: ADB built-in models vs an external
  call. Default to in-database to keep the demo offline-capable.

## 5. Swap HikariCP for Oracle UCP via the Spring Boot starter

Backend currently runs on Spring Boot's default pool (HikariCP).
Switch to Oracle's Universal Connection Pool through the official
`ucp-spring-boot-starter` so the ADB session pool gets UCP-native
features: Fast Connection Failover, Application Continuity,
label-based borrowing, built-in instrumentation.

- Add `com.oracle.database.spring:oracle-spring-boot-starter-ucp`
  (matching the JDBC driver version) to `src/backend/build.gradle`.
- Exclude `com.zaxxer:HikariCP` from `spring-boot-starter-jdbc`
  transitive deps so only one pool is on the classpath.
- Replace `spring.datasource.*` with `spring.datasource.oracleucp.*`
  in `application.yaml`; keep the wallet-based JDBC URL.
- Verify `/actuator/metrics` exposes `oracle.ucp.*` gauges and that
  `/api/v1/diag/agents/sanity` still completes within the same envelope.

## 6. Fraud detection with SQL Property Graph

New domain on top of what's there. Build a property graph in 26ai
over `accounts` (vertices) and `transactions` (edges) to express
counterparty relationships and run fraud-detection patterns the
relational queries can't express cheaply.

- Liquibase changeset issuing `CREATE PROPERTY GRAPH banking_graph
VERTEX TABLES (accounts ...) EDGE TABLES (transactions SOURCE KEY
(src_account_id) REFERENCES accounts ...)`.
- Seed fraud-shaped patterns into the demo data: a cycle (A→B→C→A)
  of round-tripping transfers, a fan-out from one account to many
  in a short window, a structuring pattern (many sub-threshold
  transfers).
- Expose a backend endpoint that runs `SELECT ... FROM GRAPH_TABLE
(banking_graph MATCH ...)` for each pattern and returns matched
  account IDs + a risk score.
- Where the graph lives: the simulated production Oracle (next to
  the source tables, simplest) vs ADB (needs the data shipped over
  `ORAFREE_LINK`, but keeps all SELECT-AI / agent surfaces in one
  place). Default to the production side unless the agent layer
  needs to query it directly.

## 7. Fraud-detection dashboard

Companion to `/risk`, sequenced **after #6** since this is the
consumption surface for the graph queries. Add a second dashboard
page in the Angular frontend that visualises property-graph fraud
results — one card per pattern (cycles, fan-out, structuring) with
matched account IDs and risk scores from the new endpoint, plus a
small force-directed graph view of the suspect subgraph.

Also reorganise the existing Risk Dashboard: items that are really
"fraud signals" (large international wires, high-risk counterparties)
move to the new fraud page; the Risk Dashboard keeps prudential /
compliance signals (limits, breaches, policy violations).

## 8. Active Data Guard standby as federation source

Architecturally significant — real Oracle infra work. Stand up an
ADG standby of the production Oracle, then point the federation
layer's DB_LINKs at the active standby instead of the primary so
read-heavy demo traffic doesn't load the primary.

- Provision the standby (Terraform module, networking, redo
  transport, broker config).
- Rewrite `database/liquibase/adb/002-db-links.yaml` so
  `ORAFREE_LINK` targets the standby service.
- Confirm the heterogeneous gateway (`PG_LINK`) is unaffected — it
  has nothing to do with ADG.
- Validate failover: standby promotion shouldn't break the
  federated path.
