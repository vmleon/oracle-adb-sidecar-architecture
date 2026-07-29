# Heterogeneous gateway failure modes on `PG_LINK`

## Current state

`PG_LINK` is deployed (Liquibase 002) and serves both the federated demo
(`route=federated` for Postgres-backed tables) and the AI compliance
agent (`COMPLIANCE_OFFICER` referencing `V_BNK_POLICIES` / `V_BNK_RULES`).

Two failure modes exist on the Oracle-managed Heterogeneous Connectivity
gateway. Both are mitigated:

- **Mode A** — `HS_IDLE_TIMEOUT = 5 min` cycle. Foreground only.
  Recoverable per call: catch and retry once.
- **Mode B** — `DBMS_CLOUD_AI_AGENT` enumeration wedge. Mitigated by an
  always-on keep-warm on the agent path
  (`AgentsService.keepWarm()` runs `RUN_TEAM` every 60 s). Without the
  keep-warm, mode B fires when the agent path goes idle for ~30+ minutes
  and then a real request lands on a poisoned scheduler-worker session.

## Architecture

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

The gateway is OCI-managed: no SSH, no init parameters, no process
table. The only signal we get is the `ORA-2851x / ORA-02063` message it
returns. `HS_IDLE_TIMEOUT` is fixed at 5 minutes and not customer-tunable.

## Mode A — 5-minute idle drop

Foreground `SELECT @PG_LINK` (or any `V_*` view that resolves through
the link) succeeds, then sits idle for ~5 minutes. The next call fails:

```
ORA-28511: lost RPC connection to heterogeneous remote agent
ORA-28509: unable to establish a connection to non-Oracle system
ORA-02063: preceding line from PG_LINK
```

The very next call after the failure succeeds — the gateway spawns a
fresh worker. Mitigations: a probe query on a sub-5-minute cadence in
the same session pool; `ALTER SESSION CLOSE DATABASE LINK PG_LINK`
before each batch; or catch `ORA-28511 / ORA-02063` and retry once.

## Mode B — agent-path enumeration wedge

### Symptom

`DBMS_CLOUD_AI_AGENT.RUN_TEAM(...)` fast-fails on `TASK_0` in **300–450 ms**
(vs the normal 30–70 s):

```
ORA-20053: Job <TEAM_NAME>_TASK_0 failed: ORA-01010: invalid OCI operation
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD$PDBCS_<...>", line 2263
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_AI_AGENT", line 12079
```

with `ORA-02063: preceding line from PG_LINK` in
`USER_SCHEDULER_JOB_RUN_DETAILS.ADDITIONAL_INFO`. The wedge persists
across consecutive `RUN_TEAM` calls until the scheduler reaps the
poisoned worker (~10 min of agent-path idle).

### Mechanism

`RUN_TEAM` runs `TASK_0` in a `DBMS_SCHEDULER` worker session. That
session caches an OCI handle to the gateway connection for `PG_LINK`.
After ~5 min idle on the gateway side (`HS_IDLE_TIMEOUT`), the gateway
closes the connection but the worker keeps the dead OCI handle. The
next `TASK_0` served by that worker hits the dead handle and fast-fails
during `USER_DB_LINKS` enumeration. The 300-ms response time is
diagnostic — it is far too short for any real LLM round-trip.

### Triggers (verified)

- Agent-path idle for ~30+ minutes while UI activity continues on
  unrelated paths (Risk Dashboard, dashboards backed by `direct` routes,
  Measurements load button which fires `forkJoin` parallel queries
  through `route=federated` for `V_POLICIES` / `V_RULES`).
- Specifically not triggered by 30 min pure idle in our experiments —
  the trigger involves something past the 5-minute mark with the worker
  pool aged but not yet reaped.

### Recovery (when the wedge fires without keep-warm)

~10 minutes of agent-path idle clears it. The scheduler reaps the
poisoned worker; the next `RUN_TEAM` lands on a fresh worker and works.

These actions do NOT recover the wedge:

- Foreground retry on `ORA-01010 / ORA-02063 / ORA-28511`.
- Foreground keep-warm queries (different session pool from the
  scheduler worker).
- `DBMS_CLOUD_AI_AGENT.DROP_TEAM(force => true)` + `CREATE_TEAM`.
- `DBMS_CLOUD_ADMIN.DROP_DATABASE_LINK('PG_LINK')` + `CREATE_DATABASE_LINK`.
- A full ADB instance stop/start in the OCI Console.

### Mitigation (always on)

`AgentsService.keepWarm()` is a Spring `@Scheduled(fixedDelay=60000,
initialDelay=90000)` method that calls `RUN_TEAM` with
`"keep-warm; reply with OK."` once a minute. It exercises the agent
path through the same scheduler-worker pool that real requests use, so
no worker session can sit idle past the 5-minute gateway window.

Configurable via `application.yaml`:

```yaml
selectai:
  agents:
    keepwarm:
      enabled: true
      interval-ms: 60000
      initial-delay-ms: 90000
```

LLM cost is bounded: ~1440 `RUN_TEAM` calls/day × 4 agents per call.
A cheaper `DBMS_SCHEDULER`-side `SELECT 1 FROM dual@PG_LINK` keep-warm
might also work but has not been validated against this wedge.

## Diagnosing live state

Distinguish wedge from cold reconnect by elapsed time:

| Elapsed          | Meaning                                                                                                                                                |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| < 100 ms, fail   | Parameter validation, not the gateway — e.g. ORA-20050, see [`run-team-conversation-contract.md`](run-team-conversation-contract.md) |
| 300–450 ms, fail | Mode B wedge                                                                                                                                           |
| 70–150 s, ok     | Cold reconnect after long idle (mode A surfacing through the agent path)                                                                               |
| 30–70 s, ok      | Normal warm path                                                                                                                                       |

Operational endpoints:

```
GET /api/v1/diag/agents/sanity                 # active end-to-end smoke (RUN_TEAM)
GET /api/v1/diag/agents/scheduler-failures?sinceMinutes=15&limit=20
GET /api/v1/diag/links                         # USER_DB_LINKS rows
GET /api/v1/diag/links/probe                   # foreground SELECT 1 FROM dual@<LINK>
GET /api/v1/diag/links/scheduler-probe         # one-shot DBMS_SCHEDULER job
GET /actuator/logfile  (Range: bytes=-50000)   # search for `event=keepwarm_ok` / `event=keepwarm_failed`
```

If `scheduler-failures` is empty and the keep-warm log lines appear at
~60–150 s intervals, the agent path is healthy.

## Lessons

1. **The agent path runs in a separate session pool from foreground
   SQL.** Foreground link probes can be green while `RUN_TEAM` is
   wedged. The only meaningful health signal for the agent feature is
   an actual `RUN_TEAM` invocation.
2. **`HS_IDLE_TIMEOUT` is fixed at 5 minutes and not tunable.** Any
   keep-warm has to exercise the link more often than that, and through
   the same session pool the consumer uses. JDBC keep-warm doesn't help
   the agent path; foreground `DBMS_SCHEDULER` probes might or might
   not, depending on worker affinity.
3. **Latency variance is normal, not a wedge signal.** `RUN_TEAM` after
   long idle can take 70–150 s on cold reconnect; that's mode A's cost
   showing through the agent path. A single slow call is not the wedge.
4. **The wedge is a cached-dead-connection bug, not a presence bug.**
   Earlier diagnoses claimed `PG_LINK` in `USER_DB_LINKS` would wedge
   `RUN_TEAM` regardless of usage. Continuous traffic at a sub-5-minute
   cadence keeps the path healthy across `PG_LINK` only, with views,
   and with the full compliance agent referencing PG-backed views. The
   fix is to keep the path warm, not to remove the link.
5. **Fast-fail times are diagnostic.** A 300-ms `RUN_TEAM` failure is a
   wedge; a 30+ s failure is something else (LLM error, timeout,
   network). Always check the elapsed time on
   `USER_SCHEDULER_JOB_RUN_DETAILS` and the back log.

## Related

- [`docs/federated-queries.md`](federated-queries.md) — gateway link
  setup for all three engines, plus the `ORA-17008` mid-Liquibase
  recovery path.
- [`docs/known-limitation-mongodb-federation.md`](known-limitation-mongodb-federation.md)
  — `MONGO_LINK` SELECTs fail with an unrelated DataDirect ODBC bug;
  `MONGO_CRED` and `MONGO_LINK` exist but no view consumes them.
- [`docs/troubleshooting.md`](troubleshooting.md) — day-two diagnostics.
