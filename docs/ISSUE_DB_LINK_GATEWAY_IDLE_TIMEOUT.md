# ADB heterogeneous-gateway idle drop kills `DBMS_CLOUD_AI_AGENT.RUN_TEAM`

Filed: 2026-04-30

## Summary

`DBMS_CLOUD_AI_AGENT.RUN_TEAM('BANKING_INVESTIGATION_TEAM', ...)` fails with
`ORA-20053: Job BANKING_INVESTIGATION_TEAM_TASK_0 failed: ORA-01010: invalid OCI
operation` once the heterogeneous-gateway session backing `PG_LINK` has been
idle long enough to be recycled. The failure happens on `TASK_0`
(`TRANSACTION_ANALYST`) even though that agent only reads Oracle Free views —
the AI-agent task framework enumerates metadata across **every** configured
`DB_LINK` during task warm-up, so one dropped link takes down the entire team.

The Postgres daemon, the network path to it, and the link metadata in
`USER_DB_LINKS` are all healthy. Only the gateway's TCP/JDBC session has been
recycled. The next live query through the link re-establishes the session and
the issue clears until the next idle window.

## Symptoms

- `/agents` chip prompts return a stack trace ending in:
  ```
  ORA-20053: Job BANKING_INVESTIGATION_TEAM_TASK_0 failed: ORA-01010: invalid OCI operation
  ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD$PDBCS_<…>", line 2263
  ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_AI_AGENT", line 12079
  ```
- The status pill in the frontend reports `Agents team` **green** (the team
  object is enabled — the shallow probe doesn't actually run a team execution).
- `/risk` and `/app` continue to work — they query the production databases
  directly and never traverse a `DB_LINK`.
- A single ad-hoc `SELECT COUNT(*) FROM "public"."policies"@PG_LINK` on the
  `adb` SQLcl shortcut succeeds and temporarily restores agent runs.
- `USER_AI_AGENT_TASK_HISTORY` rows for failed runs show `STATE='FAILED'` and
  empty `RESULT` — the failure happens at the PL/SQL layer before the task
  framework writes any result back.

## Confirming it's a `DB_LINK` gateway drop

The error text is **not** in the agent task-history views. It only appears in
`USER_SCHEDULER_JOB_RUN_DETAILS.ADDITIONAL_INFO`:

```sql
adb <<'SQL'
SELECT job_name,
       TO_CHAR(log_date AT TIME ZONE 'UTC', 'HH24:MI:SS') AS at_t,
       status,
       SUBSTR(additional_info, 1, 240) AS additional_info
FROM   user_scheduler_job_run_details
WHERE  job_name LIKE 'BANKING%' OR job_name LIKE '%TEAM_TASK%'
ORDER BY log_date DESC
FETCH FIRST 10 ROWS ONLY;
EXIT;
SQL
```

A run that hit this issue shows:

```
ORA-01010: invalid OCI operation
ORA-02063: preceding line from PG_LINK
```

`ORA-02063: preceding line from <DBLINK>` is Oracle's explicit "the prior error
happened on the remote side via this link." That is the smoking gun.

To prove the gateway specifically is the broken layer (not Postgres, not the
network):

```bash
# Postgres daemon — should succeed
pg -c "SELECT 1"

# ADB → PG via gateway — also re-warms the session as a side effect
adb <<'SQL'
SELECT COUNT(*) FROM "public"."policies"@PG_LINK;
SELECT COUNT(*) FROM v_bnk_policies;
SQL
```

If the daemon is happy and the gateway query now returns rows after agent
calls were failing, you've reproduced the idle-drop pattern.

## Root cause

The `DBMS_CLOUD` heterogeneous-services gateway maintains a long-lived
ODBC/JDBC session per link. That session is subject to several independent
timeouts:

- OCI-side NAT idle eviction (varies by network path)
- Postgres `tcp_keepalives_idle` (default ~2 hours, often tuned shorter)
- Gateway-side idle recycling on the ADB-managed worker
- Any of those firing on whichever worker holds the session

When the session drops, the next call through the link gets
`ORA-01010 / ORA-02063` until a fresh query re-opens a session. This is general
heterogeneous-gateway behaviour, not specific to the AI agent path.

It only became visible here because of two `RUN_TEAM` traits:

1. **The task framework enumerates metadata across every configured `DB_LINK`
   during task warm-up**, regardless of which agent profile is active. A dead
   `PG_LINK` therefore breaks `TASK_0` even though `TRANSACTION_ANALYST` only
   reads Oracle Free views.
2. **The error surfaces inside the scheduler job before the task framework
   writes to `USER_AI_AGENT_TASK_HISTORY.RESULT`.** The task history view shows
   `STATE='FAILED'` with `RESULT` empty, which obscures diagnosis. The real
   error text is only in `USER_SCHEDULER_JOB_RUN_DETAILS.ADDITIONAL_INFO`.

## Mitigation

Three layers, each closing a different vulnerability window. All three ship in
this repo.

### Layer 1 — frontend readiness probe touches both gateways

`ReadinessService.agentsTeam` now runs (in addition to the `USER_AI_AGENT_TEAMS`
check):

```java
adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_customers", Integer.class); // ORAFREE_LINK
adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_policies",  Integer.class); // PG_LINK
```

The frontend polls `/api/v1/ready` every 5 seconds, so while a browser is open
both gateways get queried well below any plausible idle timeout. Bonus: the
status pill turns red the moment a link genuinely dies, instead of staying
green while agent calls fail.

Limitation: this only protects while a browser is connected.

### Layer 2 — server-side keep-warm

`GatewayKeepAliveService` is a Spring `@Scheduled` task that runs the same two
warming queries every **90 seconds**, independent of any frontend session:

```java
@Scheduled(initialDelay = 60_000L, fixedDelay = 90_000L)
public void keepLinksWarm() {
    adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_customers", Integer.class);
    adbJdbc.queryForObject("SELECT COUNT(*) FROM v_bnk_policies",  Integer.class);
}
```

`@EnableScheduling` lives on `AsyncConfig`. 90 seconds is comfortably below the
typical 5+ minute idle eviction. The 60 second initial delay avoids piling on
top of the boot-time `RUN_TEAM` warm-up that `AgentsService` already performs.

This closes the no-browser gap.

### Layer 3 — backend retry-with-backoff

If both warmers somehow miss (network blip, simultaneous restart, OCI
maintenance window), `AgentsService.runTeamWithRetry` does up to **3 retries
with exponential-ish backoff (2 s → 5 s → 10 s, total worst case 17 s)** on
the transient `ORA-*` family:

- `ORA-28511` — lost RPC connection to heterogeneous remote agent
- `ORA-01010` — invalid OCI operation
- `ORA-02063` — remote-side error reached via `DB_LINK`

Real failures (`ORA-20051` task validation, `ORA-00942` missing view,
`ORA-01017` wrong password) are explicitly **not** retried — they surface
immediately so the operator sees the actual problem.

## What is _not_ a fix

- **Re-creating the link and credential.** Drop and `CREATE_DATABASE_LINK`
  works once. The session drops again on the next idle window. Useful for
  manual recovery, useless as a steady state.
- **Tuning Postgres `tcp_keepalives_idle / _interval`.** Helps with the
  TCP-level idle eviction but not with OCI-side or gateway-side recycling.
  And the keepalives need to be small enough to be meaningful (sub-minute),
  which is heavier on the daemon than the keep-warm queries.
- **`gateway_params` on `CREATE_DATABASE_LINK`.** The
  `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK` API exposes a `gateway_params`
  JSON for type-specific settings, but not a session-keepalive option.

## Architectural lesson

Whatever monitoring or readiness story you build, "all `DB_LINK`s healthy" is
a precondition for "agents work" — not just the link the agent's profile
names. A broken `PG_LINK` takes down agents that only read Oracle Free
because the task framework warms up across every link. Plan readiness probes
and any keep-alive accordingly.

## Related

- [`docs/FEDERATED_QUERIES.md`](FEDERATED_QUERIES.md) — how ADB reaches each
  remote engine through `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`, plus the
  `ORA-17008` mid-run recovery path (a related but distinct gateway flake).
- [`docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`](ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md) —
  the third heterogeneous engine (`MONGO_LINK`) is intentionally not used by
  the agents team because of an unrelated DataDirect ODBC bug.
- [`docs/TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — day-two diagnostics for
  each tier.
