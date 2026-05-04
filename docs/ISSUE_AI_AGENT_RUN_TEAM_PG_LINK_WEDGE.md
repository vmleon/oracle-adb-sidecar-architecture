# Heterogeneous gateway failure modes on `PG_LINK`

Last updated: 2026-05-04

## Summary

The Oracle-Managed Heterogeneous Connectivity gateway used by
`PG_LINK` (Postgres) on this ADB build exhibits two distinct failure
modes. One is well-documented and mitigatable; the other is durable
and cannot be worked around from the application or `ADMIN`-schema
SQL. Together they are the reason `PG_LINK` is not deployed in this
repository.

## Architecture context

ADB → Postgres goes:

```
ADMIN session ──┐
                ├── HS RPC ──► gateway worker on
DBMS_SCHEDULER ─┤             pvtnlb.adbs-private.oraclevcn.com:1523
worker session  │             (per HS_SERVICE_ALIAS)
                │                       │
DBMS_CLOUD_AI ──┘                       └── network ──► Postgres host:5432
AGENT path
(C##CLOUD$SERVICE)
```

The gateway is OCI-managed: there is no SSH onto it, no access to its
init parameters, no view of its process table. The only signal we get
from the database side is whichever `ORA-2851x / ORA-02063` message
the gateway returns.

## Failure mode A — 5-minute `HS_IDLE_TIMEOUT` cycle

### Symptom

A foreground `SELECT ... @PG_LINK` (or a `V_*` view that resolves
through the link) succeeds, the session sits idle for ~5 minutes,
the next call fails with:

```
ORA-28511: lost RPC connection to heterogeneous remote agent
ORA-28509: unable to establish a connection to non-Oracle system
ORA-02063: preceding line from PG_LINK
```

The very next call after the failure succeeds again.

### Mechanism

The OCI-managed gateway is configured with `HS_IDLE_TIMEOUT = 5`
(minutes). The gateway worker process is reaped after 5 minutes of
idle on the HS RPC channel. The first request after the reap finds
the TCP gone and surfaces `ORA-28511`; the request after that
spawns a fresh worker and continues normally.

This timeout is fixed and not increasable from the customer side.

### Mitigations (any one of these works for foreground use)

- Run a probe query (`SELECT 1 FROM dual@PG_LINK`) on a cadence
  shorter than 5 minutes from the same session pool.
- Catch `ORA-28511 / ORA-02063` and retry once — the retry hits a
  freshly spawned worker.
- `ALTER SESSION CLOSE DATABASE LINK PG_LINK` before each batch of
  remote queries, so a fresh worker is spawned every time.

## Failure mode B — durable AI-agent enumeration wedge

### Symptom

`DBMS_CLOUD_AI_AGENT.RUN_TEAM(...)` fails on `TASK_0` with:

```
ORA-20053: Job <TEAM_NAME>_TASK_0 failed: ORA-01010: invalid OCI operation
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD$PDBCS_<...>", line 2263
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_AI_AGENT", line 12079
```

with `ORA-02063: preceding line from PG_LINK` in
`USER_SCHEDULER_JOB_RUN_DETAILS.ADDITIONAL_INFO`. The failure is
**durable** — it persists for hours across many consecutive calls
and does not respond to any of the foreground-side mitigations from
mode A.

### What is happening

`RUN_TEAM` enqueues a `DBMS_SCHEDULER` job (`<TEAM>_TASK_0`) which
runs in a `C##CLOUD$SERVICE`-context session under the per-PDB
package `DBMS_CLOUD$PDBCS_<...>`. During task warm-up that path
enumerates **every** entry in `USER_DB_LINKS`, irrespective of
which agents, profiles, or tools the team actually contains.

On this build, that enumeration cannot complete against `PG_LINK`
even when the link is functional for every other consumer. The
failure does not self-heal.

### Diagnostic — confirm you are in mode B, not mode A

```sql
-- 1. Pull the most recent failures and read the FULL additional_info.
SELECT job_name,
       TO_CHAR(log_date AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS') AS at_utc,
       additional_info
FROM   user_scheduler_job_run_details
WHERE  log_date > SYSTIMESTAMP - INTERVAL '15' MINUTE
  AND  status <> 'SUCCEEDED'
ORDER  BY log_date DESC
FETCH  FIRST 10 ROWS ONLY;

-- 2. Foreground: should succeed.
SELECT COUNT(*) FROM "public"."policies"@PG_LINK;

-- 3. Plain user-owned scheduler job: should also succeed.
BEGIN
  DBMS_SCHEDULER.CREATE_JOB(
    job_name   => 'PG_LINK_PROBE_' || TO_CHAR(SYSTIMESTAMP,'HH24MISS'),
    job_type   => 'PLSQL_BLOCK',
    job_action => q'[DECLARE n NUMBER; BEGIN
                       SELECT COUNT(*) INTO n FROM dual@PG_LINK;
                     END;]',
    enabled    => TRUE,
    auto_drop  => TRUE);
END;
/
SELECT job_name, status, additional_info
FROM   user_scheduler_job_run_details
WHERE  job_name LIKE 'PG_LINK_PROBE_%'
ORDER  BY log_date DESC FETCH FIRST 5 ROWS ONLY;
```

If (2) and (3) both succeed but `RUN_TEAM` keeps failing on
`PG_LINK`, this is mode B.

### What does NOT recover mode B

Verified during incident response:

- Foreground keep-warm queries on a tight cadence (mitigates mode A,
  irrelevant to mode B — different session pool).
- Application-level retry-with-backoff on `ORA-01010 / ORA-02063 /
ORA-28511` (the wedged scheduler-worker session keeps the same
  state across retries; backoff windows are too short).
- `DBMS_CLOUD_AI_AGENT.DROP_TEAM(force => true)` followed by
  `CREATE_TEAM` — a brand-new team object hits the same error on
  its very first call.
- `DBMS_CLOUD_ADMIN.DROP_DATABASE_LINK('PG_LINK')` followed by
  `CREATE_DATABASE_LINK` with the original parameters — the
  recreated link works for foreground and for plain scheduler
  jobs, but the agent path keeps failing.
- Restructuring the team to contain only agents/tools/profiles
  that do not reference any PG-backed object — the warm-up still
  enumerates `USER_DB_LINKS` and trips on `PG_LINK`.
- A full ADB instance stop/start in the OCI Console.

### What does recover mode B

The **only** confirmed recovery: remove `PG_LINK` from
`USER_DB_LINKS`. With no `PG_LINK` row in the dictionary, the
agent's warm-up enumeration cannot trip on it, and `RUN_TEAM`
runs end-to-end.

## Decision for this repository

`PG_LINK` is **not deployed**. The Postgres engine is reachable
directly from the backend (`postgresJdbc`) and from the demo
front-end via `route=direct`, but it is not available through the
ADB sidecar.

Concretely:

- `database/liquibase/adb/002-db-links.yaml` does not include
  `PG_CRED`, `PG_LINK`, `V_POLICIES`, or `V_RULES`.
- `database/liquibase/adb/004-banking-views-extended.yaml` does not
  include `V_BNK_POLICIES` or `V_BNK_RULES`.
- `database/liquibase/adb/005-select-ai-agents.yaml` does not
  include the `BANKING_NL2SQL_COMPLIANCE` profile, the
  `COMPLIANCE_SQL_TOOL` / `COMPLIANCE_RAG_TOOL` tools, the
  `COMPLIANCE_OFFICER` agent, the `ASSESS_COMPLIANCE` task, the
  `BANKING_RAG` profile, or the `BANKING_POLICY_INDEX` vector
  index. The `BANKING_INVESTIGATION_TEAM` consists of the txn,
  care, and synthesis agents only.

## What works

- `/api/v1/agents` (`DBMS_CLOUD_AI_AGENT.RUN_TEAM` against
  `BANKING_INVESTIGATION_TEAM`) — txn analyst → customer-care
  liaison → case synthesizer.
- `route=direct` for every engine (Oracle Free, Postgres, Mongo).
- `route=federated` for Oracle-Free-backed tables
  (`accounts`, `transactions`, `customers`, `branches`) via
  `ORAFREE_LINK` and the `V_BNK_*` views over it.
- `/api/v1/risk` — direct Oracle Free + direct Postgres queries,
  no sidecar link.
- `/api/v1/ready` — bootstraps and reports per-engine reachability.

## What does not work

- `route=federated` for Postgres-backed tables (`policies`,
  `rules`) — the `V_POLICIES` / `V_RULES` / `V_BNK_POLICIES` /
  `V_BNK_RULES` views are not provisioned because their `@PG_LINK`
  reference cannot exist while the agent feature is required.
- The compliance and RAG tracks of the agent team — removed for
  the same reason.
- `MONGO_LINK` federation for `support_tickets` — separate
  unrelated DataDirect ODBC bug, see
  [`docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`](ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md).

## Architectural lesson

For the AI agent path, "the link is healthy from foreground / from
`DBMS_SCHEDULER`" is **not** equivalent to "the link is healthy
from the agent." The agent runs as `C##CLOUD$SERVICE` through a
per-PDB package and enumerates `USER_DB_LINKS` at warm-up; a link
that is unenumerable on that path will break every team in the
schema, regardless of team membership. Operational health probes
that only test foreground link queries can report green while
`RUN_TEAM` is dead. Until the underlying behavior is fixed
upstream, the only meaningful health signal for the agent feature
is an actual `RUN_TEAM` invocation, and the only way to keep the
feature working is to keep any wedged link out of `USER_DB_LINKS`.

## Related

- [`docs/FEDERATED_QUERIES.md`](FEDERATED_QUERIES.md) — how ADB
  reaches each remote engine through
  `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`, plus the `ORA-17008`
  mid-run recovery path.
- [`docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`](ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md) —
  the third heterogeneous engine (`MONGO_LINK`) is intentionally
  not used because of an unrelated DataDirect ODBC bug.
- [`docs/TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — day-two
  diagnostics for each tier.
