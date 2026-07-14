# `RUN_TEAM` requires a catalog-registered conversation id

## Symptom

Every `DBMS_CLOUD_AI_AGENT.RUN_TEAM` call fails in **under 100 ms** with:

```
ORA-20050: Conversation id=<uuid> does not exist.
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD$PDBCS_260617_0", line 2291
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_AI_AGENT", line 14004
```

The sub-100-ms elapsed time is diagnostic: the call dies in parameter
validation before any scheduler job, LLM round-trip, or `PG_LINK`
traffic happens. This failure has nothing to do with the heterogeneous
gateway (see the elapsed-time table in
[`ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md`](ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md)).

## Contract

`RUN_TEAM` validates the `conversation_id` passed in `params` against
the conversation catalog (`USER_CLOUD_AI_CONVERSATIONS`, per DB user)
and rejects ids it has never seen. Conversation ids must come from
`DBMS_CLOUD_AI.CREATE_CONVERSATION`:

```sql
-- adb
SELECT DBMS_CLOUD_AI.CREATE_CONVERSATION('{"title":"probe"}') FROM DUAL;
-- returns e.g. 5681FF28-EB97-E770-E063-8EF6000A4861

SELECT DBMS_CLOUD_AI_AGENT.RUN_TEAM(
         'BANKING_INVESTIGATION_TEAM',
         'reply with OK.',
         '{"conversation_id":"5681FF28-EB97-E770-E063-8EF6000A4861"}')
FROM DUAL;
```

Omitting `params` is not a workaround — `RUN_TEAM` logs every prompt
into the conversation-prompt table and dies with
`ORA-01400: cannot insert NULL into ...CONVERSATION_PROMPT$.CONVERSATION_ID#`.

The conversation must be created by the **same DB user** that calls
`RUN_TEAM` (the catalog is per-schema). Reusing one id across calls is
supported and carries multi-turn context; a paused team resumes when
called again with the same id.

The caller's conversation id is recorded in
`USER_AI_AGENT_TEAM_HISTORY.CONVERSATION_ID` — that column is the join
key for finding a run's `TEAM_EXEC_ID`. The task-level
`CONVERSATION_PARAMS` on `USER_AI_AGENT_TASK_HISTORY` holds internally
generated per-task conversation ids and does not match the caller's id.

## How the backend complies

`AgentsService`:

- `runTeam(prompt, null)` creates a conversation via
  `DBMS_CLOUD_AI.CREATE_CONVERSATION` and returns the id to the client;
  follow-up requests send that id back.
- A client-supplied id that raises `ORA-20050` (conversation dropped or
  past retention) triggers one recovery attempt on a freshly created
  conversation — the answer loses prior context instead of failing.
- Warm-up and keep-warm share a single cached conversation id,
  recreated on demand if the catalog row disappears.

## Diagnosing

```bash
# keep-warm health — keepwarm_ok lines every ~60-150 s means healthy
ssh opc@$BACK "sudo journalctl -u back --since '-30 min' --no-pager | grep -E 'keepwarm|run_team'"
```

```sql
-- adb: conversations the backend user can pass to RUN_TEAM
SELECT conversation_id, title, created FROM user_cloud_ai_conversations ORDER BY created DESC;

-- adb: the package build currently deployed by Oracle's rolling patch
SELECT object_name, last_ddl_time FROM all_objects
WHERE  owner = 'C##CLOUD$SERVICE'
AND    object_name = 'DBMS_CLOUD_AI_AGENT' AND object_type = 'PACKAGE';
```

If ORA-20050 reappears with sub-100-ms failures, compare
`last_ddl_time` against the date of the last known-good run — the
`DBMS_CLOUD_AI_AGENT` package is patched in place by Oracle and its
validation rules can change without any deploy on our side.
