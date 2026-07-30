# `RUN_TEAM` reliability on the AI Data Gateway

`DBMS_CLOUD_AI_AGENT.RUN_TEAM` occasionally fails on ADB 23.26.x even though the
agent team completed its work. This file records what the failure actually is,
how to tell the modes apart, and the explanations that were tried and abandoned.

## Architecture

```
ADMIN session ──► DBMS_CLOUD_AI_AGENT.RUN_TEAM
                        │
                        ├── OCI Generative AI over UTL_HTTP (per agent)
                        │
                        └── SQL tools ──► V_BNK_* views
                                            ├── ORAFREE_LINK  (Oracle-to-Oracle)
                                            └── PG_LINK       (heterogeneous gateway)
```

The gateway is OCI-managed: no SSH, no init parameters, no process table. Its
`HS_IDLE_TIMEOUT` is fixed at 5 minutes and is not customer-tunable.

## Mode A — heterogeneous gateway idle drop

A query through `PG_LINK` succeeds, sits idle ~5 minutes, and the next fails:

```
ORA-28511: lost RPC connection to heterogeneous remote agent
ORA-28509: unable to establish a connection to non-Oracle system
ORA-02063: preceding line from PG_LINK
```

The call straight after succeeds — the gateway spawns a fresh worker.

**Mitigation, in place:** `AgentsService.runTeamWithGatewayRetry()` retries once
on `ORA-28511 / ORA-28509 / ORA-02063`.

## Mode B — failure on the return path

The user-visible failure:

```
ORA-20000: ORA-01010: invalid OCI operation
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD$PDBCS_<build>", line 2305
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_AI_AGENT", line 14677
```

**Every task has already SUCCEEDED when this fires.** The catalog shows all
agents green and the synthesised answer present:

```sql
SELECT task_order, agent_name, state,
       ROUND((CAST(end_date AS DATE) - CAST(start_date AS DATE)) * 86400) AS secs
FROM   user_ai_agent_task_history
WHERE  team_exec_id = '<exec>'
ORDER  BY task_order;
```

Only the value's trip back to the caller fails, inside the DBMS_CLOUD HTTP
helper, after a full-length run (50–90 s). The same `DBMS_CLOUD$PDBCS` package
on this build also emits an unsubstituted `my$cloud_domain` in the Generative AI
endpoint. Both look like Oracle-side defects in one package; nothing on the
client side prevents them.

**Mitigation, in place:** `AgentsService.recoverAnswer()` reads the last
SUCCEEDED task's `RESULT` for the conversation and returns it, logging
`event=run_team_recovered`. The caller gets the answer instead of a stack trace.
A rising rate of that event is the evidence to attach to an Oracle SR.

## Telling the modes apart

| Elapsed          | Meaning                                                                                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------------------- |
| < 100 ms, fail   | Parameter validation — e.g. ORA-20050, see [`run-team-conversation-contract.md`](run-team-conversation-contract.md) |
| 300–450 ms, fail | Gateway error surfacing through the agent path (mode A)                                                             |
| 50–90 s, fail    | Mode B — check `user_ai_agent_task_history`; the answer is probably there                                           |
| 50–150 s, ok     | Normal. Latency varies widely with LLM load; a slow call is not a fault                                             |

On ADB 23.26.x, `RUN_TEAM` creates **no user-visible `DBMS_SCHEDULER` jobs**.
`USER_SCHEDULER_JOB_RUN_DETAILS` holds no `<TEAM_NAME>_TASK_%` rows, so any
diagnosis depending on `additional_info` from that view returns nothing.
Per-task truth lives in `USER_AI_AGENT_TASK_HISTORY`, joined to
`USER_AI_AGENT_TEAM_HISTORY` on `TEAM_EXEC_ID`.

## Abandoned explanations

Recorded so they are not re-tried. Each was measured, not assumed.

| Theory                                                                                                 | Why it was dropped                                                                                                                                                                                                                |
| ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A scheduler-worker "wedge" caches a dead `PG_LINK` OCI handle**, fast-failing `TASK_0` in 300–450 ms | This build runs no per-task scheduler jobs, so the mechanism cannot apply. Observed failures take 50–90 s with every task SUCCEEDED.                                                                                              |
| **A 60 s keep-warm `RUN_TEAM` prevents that wedge**                                                    | It never completed once. Sharing one conversation across ticks made each run slower until it passed the 300 s query timeout; every tick was then cancelled, leaving `RUNNING` rows and leaked UTL_HTTP handles. Removed entirely. |
| **Re-using a conversation across turns hangs `RUN_TEAM`**                                              | Measured: first run 8.2 s, second run on the same conversation 18.2 s. It degrades with history length but does not hang. Each turn still gets a fresh conversation, because the degradation is real.                             |
| **Two `RUN_TEAM` calls starting together collide**                                                     | Three simultaneous pairs, 6/6 succeeded.                                                                                                                                                                                          |
| **The keep-warm caused the failures and the latency spikes**                                           | With it disabled, 10/10 succeeded but latency was unchanged — mean 74 s vs 80 s, max 144 s vs 152 s. The spikes are LLM variance.                                                                                                 |

The durable lesson: **latency variance is not a fault signal**, and a failing
`RUN_TEAM` is not evidence that the agents failed. Read
`user_ai_agent_task_history` before theorising.

## Related

- [`federated-queries.md`](federated-queries.md) — gateway link setup for all engines.
- [`known-limitation-mongodb-federation.md`](known-limitation-mongodb-federation.md) — MongoDB is not federated.
- [`troubleshooting.md`](troubleshooting.md) — day-two diagnostics and the log guide.
