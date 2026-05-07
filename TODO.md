# Pending

## bootstrap.tftpl — `ansible_params.json` is appended on cloud-init re-run

`deploy/tf/modules/ops/userdata/bootstrap.tftpl` line 80 uses `cat <<EOT >>`,
so a re-run concatenates a second JSON object onto the file and Ansible
fails to parse it. Change `>>` to `>` so the file is overwritten.

Workaround until then (run on ops):

```bash
python3 -c "import json; raw=open('/home/opc/ansible_params.json').read().lstrip(); obj,_=json.JSONDecoder().raw_decode(raw); open('/home/opc/ansible_params.json','w').write(json.dumps(obj,indent=2))"
```

## Optional — Layer 4 RAG track

Add `BANKING_RAG` profile + `BANKING_POLICY_INDEX` vector index +
`COMPLIANCE_RAG_TOOL`, wired into `COMPLIANCE_OFFICER` alongside the
SQL tool. Only the `005-select-ai-agents.yaml` changeset needs the
additions.

## Swap HikariCP for Oracle UCP via the Spring Boot starter

The backend currently runs on Spring Boot's default pool (HikariCP).
Switch to Oracle's Universal Connection Pool through the official
`ucp-spring-boot-starter` so the ADB session pool gets UCP-native
features: Fast Connection Failover, Application Continuity, label-based
borrowing, and built-in instrumentation. Steps:

- Add `com.oracle.database.spring:oracle-spring-boot-starter-ucp`
  (matching the JDBC driver version) to `src/backend/build.gradle`.
- Exclude `com.zaxxer:HikariCP` from the `spring-boot-starter-jdbc`
  transitive deps so only one pool is on the classpath.
- Replace `spring.datasource.*` with `spring.datasource.oracleucp.*`
  in `application.yaml`; keep the wallet-based JDBC URL.
- Verify `/actuator/metrics` exposes `oracle.ucp.*` gauges and that
  `/api/v1/diag/agents/sanity` still completes within the same envelope.

## Add a blockchain-style ledger for `transactions`

The current `transactions` table on Oracle Free is mutable. Add an
append-only, hash-chained ledger so the demo can show tamper-evident
history alongside the federated query path. Two viable shapes — pick
one:

- **Oracle Blockchain Table** (`CREATE BLOCKCHAIN TABLE
transactions_ledger ... NO DROP UNTIL ...`): native, hash chain
  managed by the DB, rows are insert-only, supports row-level
  cryptographic signing. Lives on Oracle Free 26ai next to the
  source `transactions` table.
- **Immutable table fallback** (`CREATE IMMUTABLE TABLE`) if the
  blockchain feature is unavailable on the chosen edition.

Wire a Liquibase changeset under `database/liquibase/oracle/` that
creates the ledger and a trigger / scheduled job that copies new
`transactions` rows into it. Surface the ledger through a new view
on ADB (`V_TRANSACTIONS_LEDGER` via `ORAFREE_LINK`) so the existing
federated path can read it unchanged.

## Fraud detection with SQL Property Graph

Build a property graph in Oracle 26ai over `accounts` (vertices) and
`transactions` (edges) to express counterparty relationships and run
fraud-detection patterns the relational queries can't express
cheaply. Scope:

- Add a Liquibase changeset that issues `CREATE PROPERTY GRAPH
banking_graph VERTEX TABLES (accounts ...) EDGE TABLES
(transactions SOURCE KEY (src_account_id) REFERENCES accounts ...)`.
- Seed a handful of fraud-shaped patterns into the demo data: a
  cycle (A→B→C→A) of round-tripping transfers, a fan-out from one
  account to many in a short window, a structuring pattern (many
  sub-threshold transfers).
- Expose a backend endpoint that runs `SELECT ... FROM GRAPH_TABLE
(banking_graph MATCH ...)` for each pattern and returns the
  matched account IDs + a risk score.
- Decide where the graph lives — Oracle Free 26ai (next to the
  source tables, simplest) vs ADB (needs the data shipped over
  `ORAFREE_LINK`, but keeps all SELECT-AI/agent surfaces in one
  place). Default to Oracle Free unless the agent layer needs to
  query it directly.

## Fraud-detection dashboard

Add a second dashboard page in the Angular frontend that visualises
the property-graph fraud results — one card per pattern (cycles,
fan-out, structuring) with the matched account IDs and risk scores
from the new endpoint, plus a small force-directed graph view of
the suspect subgraph. Reorganise the existing Risk Dashboard:
items that are really "fraud signals" (large international wires,
high-risk counterparties) belong on the new fraud page; the Risk
Dashboard keeps prudential/compliance signals (limits, breaches,
policy violations).
