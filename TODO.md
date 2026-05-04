# PG_LINK reintroduction — work-in-progress handoff

Last paused: 2026-05-04. Self-contained — read top to bottom. The goal of
this document is that a fresh assistant session, with **no prior context**,
can resume the work tomorrow.

## TL;DR — where we are

1. **Root cause of the original outage is documented** in
   [`docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md`](docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md).
   Two distinct failure modes on the heterogeneous gateway: 5-min
   `HS_IDLE_TIMEOUT` (mode A, mitigatable) and a durable AI-agent
   enumeration wedge (mode B, only recovers by removing `PG_LINK` from
   `USER_DB_LINKS`).
2. **The current repo state has `PG_LINK` REMOVED** from Liquibase + the
   compliance/RAG agent track removed. Result: agents work end-to-end,
   federated PG demo paths are dark.
3. **A diagnostic endpoint layer was added** (`/api/v1/diag/*`) so we can
   curl the symptoms instead of ssh-ing to ops and joining views in
   sqlcl by hand.
4. **The next step is the PG_LINK reintroduction experiment** — bring
   PG_LINK and the compliance pieces back in 4 layers, observe with the
   diag endpoints, and either confirm mode B reproduces (so the doc is
   right) or find a recovery that lets us keep PG_LINK without breaking
   agents.

## What is uncommitted on disk (DO NOT lose)

```
M README.md
M database/liquibase/adb/002-db-links.yaml          # PG_CRED/LINK + V_POLICIES/V_RULES removed
M database/liquibase/adb/004-banking-views-extended.yaml  # V_BNK_POLICIES/V_BNK_RULES removed
M database/liquibase/adb/005-select-ai-agents.yaml  # COMPLIANCE/RAG removed; team = txn -> care -> synth
M deploy/ansible/back/roles/java/files/application.yaml.j2  # Actuator + logging
M deploy/ansible/back/roles/java/tasks/main.yaml    # logs/ dir
M deploy/ansible/ops/roles/base/tasks/main.yaml     # FIXED conn-save until clause
D docs/ISSUE_DB_LINK_GATEWAY_IDLE_TIMEOUT.md        # superseded
?? docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md    # corrected ISSUE doc
M src/backend/src/main/java/.../agents/AgentsService.java
       # CONVERSATION_PARAMS (no typo), prompt-history join, scheduler-error enrichment, structured logging
M src/backend/src/main/java/.../agents/dto/AgentTrace.java
       # added prompts list + per-task schedulerAdditionalInfo
M src/backend/src/main/java/.../config/AsyncConfig.java       # @EnableScheduling removed
M src/backend/src/main/java/.../readiness/ReadinessService.java
       # extra warm queries removed; agentsTeam probe = team STATUS only
D src/backend/src/main/java/.../readiness/GatewayKeepAliveService.java  # removed
M src/backend/src/main/resources/application.yaml   # Actuator + logging (local dev)
?? src/backend/src/main/java/.../diag/                # NEW DiagController
```

**Strongly recommended before destroying the deployment:** commit this as
a single checkpoint so an accidental `git checkout .` doesn't lose it.

```bash
git add -A
git commit -m "checkpoint: drop PG_LINK + compliance, add diag endpoints, fix ops conn-save retry"
```

## Why PG_LINK is gone right now (1-paragraph version)

Adding any link to `USER_DB_LINKS` that the AI agent's TASK_0 warm-up can't
enumerate makes every `RUN_TEAM` call fail with
`ORA-01010 / ORA-02063 from PG_LINK`, durably, surviving link drop+create,
team drop+create, and a full ADB stop/start. The only confirmed recovery
is dropping the link from `USER_DB_LINKS`. We removed PG_LINK and the
compliance/RAG agent pieces that depended on it. Read
`docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md` for the full evidence,
verified-non-recoveries list, and architecture context.

## Diagnostic surface that already exists

All under `http://<FRONT_IP>/` (public LB → nginx → back).

```
POST /api/v1/agents                              # run team; on failure, response includes scheduler additional_info
GET  /api/v1/diag/links                          # USER_DB_LINKS rows
GET  /api/v1/diag/links/probe                    # foreground SELECT 1 FROM dual@<LINK> per link
GET  /api/v1/diag/links/scheduler-probe          # one-shot DBMS_SCHEDULER job per link
GET  /api/v1/diag/profiles                       # USER_CLOUD_AI_PROFILE_ATTRIBUTES
GET  /api/v1/diag/agents/inventory               # teams + agents + tasks + tools + their attrs
GET  /api/v1/diag/agents/runs?limit=20&sinceMinutes=60
GET  /api/v1/diag/agents/runs/{conversationId}   # full trace + LLM prompts + scheduler errors per failed task
GET  /api/v1/diag/agents/scheduler-failures?sinceMinutes=15&limit=20
GET  /api/v1/diag/agents/sanity                  # active end-to-end smoke (RUN_TEAM "reply with OK")
GET  /api/v1/diag/columns?view=USER_AI_AGENT_TEAMS  # introspect ALL_TAB_COLUMNS for any view
GET  /actuator/logfile  (use Range: bytes=-50000)
GET  /actuator/loggers/<name>   (POST to set level live)
```

Structured RUN_TEAM logs go to `{back_dest_directory}/logs/app.log` —
key=value format, grep-friendly. On failure the line includes
`scheduler_additional_info=...`.

## The PG_LINK reintroduction experiment (the real next step)

Use the diag endpoints to test layered hypotheses. After each layer, run
`/diag/agents/sanity` immediately, then a second time after **5+ minutes
idle** (to clear the `HS_IDLE_TIMEOUT` window). Soak with a curl loop for
30 min before declaring a layer "stable".

### Layer 1 — `PG_CRED` + `PG_LINK` only

Tests the strongest doc claim: "presence of `PG_LINK` in `USER_DB_LINKS`
is enough to break `RUN_TEAM`, regardless of which agents reference it."

If sanity stays green for 30 min after adding L1, that claim is overstated
and the rest of the experiment becomes much easier.

```sql
-- Run via `adb` on ops, OR via a temporary DiagController endpoint if you build one.
BEGIN
  BEGIN DBMS_CLOUD.DROP_CREDENTIAL('PG_CRED'); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD.CREATE_CREDENTIAL(
    credential_name => 'PG_CRED',
    username        => 'postgres',
    password        => '<postgres_db_password from ansible_params.json>'
  );
END;
/

BEGIN
  BEGIN DBMS_CLOUD_ADMIN.DROP_DATABASE_LINK('PG_LINK'); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name       => 'PG_LINK',
    hostname           => '<databases_fqdn from ansible_params.json>',
    port               => 5432,
    service_name       => 'postgres',
    ssl_server_cert_dn => NULL,
    credential_name    => 'PG_CRED',
    gateway_params     => JSON_OBJECT('db_type' VALUE 'postgres'),
    public_link        => FALSE,
    private_target     => TRUE
  );
END;
/
```

Verify and soak:

```bash
F=<front_ip>
curl -sS http://$F/api/v1/diag/links | jq .
curl -sS http://$F/api/v1/diag/links/probe | jq .
curl -sS http://$F/api/v1/diag/links/scheduler-probe | jq .

# Soak — 30 min, sanity every 60 s
for i in $(seq 1 30); do
  date -u +%H:%M:%S
  curl -sS --max-time 90 http://$F/api/v1/diag/agents/sanity | jq -c '{ok,elapsedMs}'
  sleep 60
done

# After the soak
curl -sS "http://$F/api/v1/diag/agents/scheduler-failures?sinceMinutes=60" | jq .
```

**Decision after L1:**

- All sanity ok=true, no scheduler-failures → mode B claim is overstated.
  Continue to L2.
- Any sanity ok=false with `ORA-02063 from PG_LINK` → mode B reproduced.
  Capture the exact `additional_info`, the time, the elapsed since L1
  was applied. Note whether the wedge clears with a manual probe (L1
  test 1) or persists across multiple sanity calls (mode B). Move to
  the "investigation" section below.

### Layer 2 — add the views (no agent changes)

Tests if view existence (without agent reference) is the trigger.

```sql
CREATE OR REPLACE VIEW V_POLICIES AS
SELECT "id" AS id, "name" AS name, "description" AS description
FROM "public"."policies"@PG_LINK
/
CREATE OR REPLACE VIEW V_RULES AS
SELECT "id" AS id, "policy_id" AS policy_id, "expression" AS expression
FROM "public"."rules"@PG_LINK
/
CREATE OR REPLACE VIEW V_BNK_POLICIES AS
SELECT "id" AS id, "code" AS code, "name" AS name, "description" AS description,
       "category" AS category, "effective_at" AS effective_at
FROM "public"."policies"@PG_LINK
/
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
```

Re-soak.

### Layer 3 — add compliance profile + tool + agent + task + put it in the team

Full agent pieces. Pull the exact PL/SQL from
`database/liquibase/adb/005-select-ai-agents.yaml` history (commit prior
to the cleanup). Or use the version embedded below for convenience —
matches the pre-removal shape minus the RAG tool.

```sql
BEGIN
  BEGIN DBMS_CLOUD_AI.DROP_PROFILE(profile_name => 'BANKING_NL2SQL_COMPLIANCE', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_AI.CREATE_PROFILE(
    profile_name => 'BANKING_NL2SQL_COMPLIANCE',
    attributes   => '{
      "provider": "oci",
      "credential_name": "OCI_API_KEY_CRED",
      "object_list": [
        {"owner": "ADMIN", "name": "V_BNK_POLICIES"},
        {"owner": "ADMIN", "name": "V_BNK_RULES"}
      ],
      "region": "<genai_region>",
      "oci_compartment_id": "<oci_genai_compartment_id>",
      "oci_apiformat": "GENERIC"
    }');
END;
/

BEGIN
  BEGIN DBMS_CLOUD_AI_AGENT.DROP_TOOL(tool_name => 'COMPLIANCE_SQL_TOOL', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_AI_AGENT.CREATE_TOOL(
    tool_name  => 'COMPLIANCE_SQL_TOOL',
    attributes => '{"tool_type":"SQL","tool_params":{"profile_name":"BANKING_NL2SQL_COMPLIANCE"}}'
  );

  BEGIN DBMS_CLOUD_AI_AGENT.DROP_AGENT(agent_name => 'COMPLIANCE_OFFICER', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_AI_AGENT.CREATE_AGENT(
    agent_name => 'COMPLIANCE_OFFICER',
    attributes => '{
      "profile_name": "BANKING_NL2SQL_COMPLIANCE",
      "role": "You are a bank compliance officer. Given transaction facts from the analyst, identify which AML, KYC, Reg E, OFAC, or fraud rules and policies apply. Cite rule codes and quote relevant policy sections. Classify each finding as INFO, WARNING, or VIOLATION.",
      "enable_human_tool": "false"
    }'
  );

  BEGIN DBMS_CLOUD_AI_AGENT.DROP_TASK(task_name => 'ASSESS_COMPLIANCE', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_AI_AGENT.CREATE_TASK(
    task_name  => 'ASSESS_COMPLIANCE',
    attributes => '{
      "instruction": "Given the user request {query} and the transactional facts in your input, identify all applicable rules and policies. Use the SQL tool for rule lookups. List each finding with rule code, severity, and a short explanation.",
      "tools": ["COMPLIANCE_SQL_TOOL"],
      "input": "PULL_TXN_FACTS"
    }'
  );

  BEGIN DBMS_CLOUD_AI_AGENT.DROP_TEAM(team_name => 'BANKING_INVESTIGATION_TEAM', force => true); EXCEPTION WHEN OTHERS THEN NULL; END;
  DBMS_CLOUD_AI_AGENT.CREATE_TEAM(
    team_name  => 'BANKING_INVESTIGATION_TEAM',
    attributes => '{
      "agents": [
        {"name":"TRANSACTION_ANALYST",   "task":"PULL_TXN_FACTS"},
        {"name":"COMPLIANCE_OFFICER",    "task":"ASSESS_COMPLIANCE"},
        {"name":"CUSTOMER_CARE_LIAISON", "task":"GATHER_CARE_CONTEXT"},
        {"name":"CASE_SYNTHESIZER",      "task":"SYNTHESIZE_CASE"}
      ],
      "process": "sequential"
    }'
  );
END;
/
```

Then in the UI: ask **"What policies apply to international wires above $10K?"**.
Re-soak.

### Layer 4 (optional) — RAG

Adds `BANKING_RAG` profile + `BANKING_POLICY_INDEX` vector index +
`COMPLIANCE_RAG_TOOL`. Only attempt if L3 is stable. Same recipe as the
old `005-select-ai-agents.yaml`.

### If mode B reproduces — investigation hooks to try

In rough order of "least invasive":

1. Capture the **first** failure timing. Was it:
   - First call after L1 applied? → wedge is immediate on link create
   - First call **after a 5-min idle window**? → wedge correlates with `HS_IDLE_TIMEOUT`; suggests the agent path's session inherits something from the idle drop that foreground sessions don't
   - Some delay after L1? → time-based gateway state
2. Probe whether the wedge clears after `ALTER SESSION CLOSE DATABASE LINK PG_LINK`
   in **all** ADMIN sessions (note: this is per-session; the agent's
   scheduler-worker session is unreachable from us).
3. Probe whether a **DBMS_SCHEDULER-side keep-warm** job (not a JDBC
   keep-warm — we know that doesn't help) prevents the wedge. Submit a
   `SELECT 1 FROM dual@PG_LINK` job every 60 s, observe.
4. File an Oracle SR with the reproducer. The Slack-thread evidence I
   saw points to `HS_IDLE_TIMEOUT = 5` for the ordinary case; the
   durable-wedge case appears to be a separate bug not yet documented
   by Oracle.

## Resume instructions for tomorrow (or for a fresh assistant)

### 1) Sanity-check the local repo

```bash
git status            # confirm the M/D/?? list above is intact
git log --oneline -3  # last commit should be 79f7988 docs(readme) ...  OR your checkpoint commit
```

If you committed the checkpoint as suggested above, the working tree
will be clean and the changes will be in HEAD. If not, they're still
modifications.

### 2) Fresh deploy

```bash
python manage.py setup       # if needed
python manage.py build       # bundles backend jar + ansible/wallet/database zips
python manage.py tf          # terraform apply
python manage.py info        # prints the new IPs
```

The conn-save bug is patched in
`deploy/ansible/ops/roles/base/tasks/main.yaml`, so the cloud-init
ansible run should complete on first try. Expected runtime: ~10 min
ansible + ~5 min Liquibase passes.

### 3) Verify the deploy is healthy

```bash
F=<front_ip from python manage.py info>

curl -sS http://$F/api/v1/ready                     | jq .
# expected: overall=ready, all 6 components ready

curl -sS http://$F/api/v1/diag/links                | jq '.[] | .DB_LINK'
# expected: ORAFREE_LINK, MONGO_LINK   (NO PG_LINK — that's the experiment subject)

curl -sS http://$F/api/v1/diag/agents/inventory \
  | jq 'with_entries(.value |= (if type=="array" then ("rows="+(length|tostring)) else . end))'
# expected: teams=1, teamAttrs=2, agents=3, agentAttrs=9, tasks=3,
#           taskAttrs=6, tools=2, toolAttrs=6, profileAttrs=17
# (no errors anywhere)

curl -sS http://$F/api/v1/diag/agents/sanity        | jq .
# expected: ok=true, elapsedMs in tens of seconds
```

### 4) Pull the secrets you need for the experiment

```bash
ssh -A -i ~/.ssh/id_ed25519 opc@<ops_ip>
jq -r '.postgres_db_password, .databases_fqdn, .oci_genai_compartment_id, .genai_region' \
   /home/opc/ansible_params.json
```

Substitute these into the L1 / L3 SQL blocks above.

### 5) Run the experiment, layer by layer

Apply L1 via `adb` (sqlcl on ops). Soak. Then L2. Then L3. After each
layer, **always** check `/diag/agents/scheduler-failures` and the back
log via `/actuator/logfile`.

Whichever layer (if any) breaks the agent path is the answer — capture
the exact `additional_info`, `links/probe` vs `links/scheduler-probe`
asymmetry, and timing relative to layer apply.

### 6) Codify the result

Whatever survives the soak goes back into Liquibase
(`002-db-links.yaml`, `004-banking-views-extended.yaml`,
`005-select-ai-agents.yaml`). Whatever doesn't survive gets a new
section added to `docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md`
documenting the exact reproducer.

## Known gotchas (do not relearn these)

- **`ansible_params.json` gets duplicated when you re-run the cloud-init
  user-data script** (`/var/lib/cloud/instance/scripts/part-001`).
  `bootstrap.tftpl` appends instead of overwriting. If you rerun, dedupe
  with: `python3 -c "import json; raw=open('/home/opc/ansible_params.json').read().lstrip(); obj,_=json.JSONDecoder().raw_decode(raw); open('/home/opc/ansible_params.json','w').write(json.dumps(obj,indent=2))"`. Permanent fix is on the TODO list (see "Deferred fixes").
- **`script -q /dev/null -c "sql /nolog @..."` exits 0 even when SQLcl
  prints `Connection Failed`.** SQLcl's `WHENEVER SQLERROR EXIT
SQL.SQLCODE` does not catch `conn` failures. The Ansible `until:`
  clause for connection retries must inspect stdout for `ORA-*`, not
  rely on `rc`. Already fixed in
  `deploy/ansible/ops/roles/base/tasks/main.yaml`.
- **`liquibase.properties` is a `.j2` template** — Ansible renders it.
  Trying to run `liquibase --defaults-file=liquibase.properties status`
  before Ansible has rendered the file gives a useless error.
- **`USER_AI_AGENT_TEAMS.TEAM_NAME` does NOT exist** — the column is
  `AGENT_TEAM_NAME`. The official Oracle docs page got this wrong.
  Always `/diag/columns?view=USER_AI_AGENT_TEAMS` before writing SQL.
- **`USER_AI_AGENT_TASK_HISTORY.COVERSATION_PARAM` (typo) was renamed
  to `CONVERSATION_PARAMS` (no typo, plural) in 23.26.** Old code
  comments still reference the typo — ignore them.
- **`USER_AI_AGENT_TASKS` is plural, not singular**, despite docs.
- **`USER_AI_AGENT_*` views are SYS-owned with public synonyms** — they
  do not appear in `USER_TAB_COLUMNS`. Use `ALL_TAB_COLUMNS` for
  introspection (already done in `/diag/columns`).
- **AI agent error attribution is misleading.** `RUN_TEAM` failures
  surface `ORA-20053: Job ... failed: ORA-01010 invalid OCI operation`.
  The actual `ORA-02063 from PG_LINK` lives in
  `USER_SCHEDULER_JOB_RUN_DETAILS.ADDITIONAL_INFO`, which the agent
  history views do NOT carry. The `/api/v1/agents` POST handler in
  `AgentsService.runTeam` joins this in on failure — keep that join.
- **OCI ADB instance stop/start does NOT recover mode B.** Verified
  during incident response.
- **Dropping and recreating PG_LINK does NOT recover mode B.** Same.
- **Dropping and recreating the team does NOT recover mode B.** Same.

## Deferred fixes (small, not blocking the experiment)

- `deploy/tf/modules/ops/userdata/bootstrap.tftpl` — make
  `ansible_params.json` writes overwrite, not append.
- `docs/TROUBLESHOOTING.md` — add a one-liner for "if cloud-init fails
  partway, dedupe ansible_params.json before re-running".
- The frontend demo card for federated-PG (`policies` / `rules` cards
  on `/app`) shows the raw `ORA-00942: V_POLICIES does not exist`
  message. Either hide those cards or render a "feature unavailable"
  state. Cosmetic.

## Useful pointers

- `docs/ISSUE_AI_AGENT_RUN_TEAM_PG_LINK_WEDGE.md` — the corrected diagnosis.
- `docs/FEDERATED_QUERIES.md` — gateway architecture, related ORA-17008
  recovery path.
- `docs/ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md` — separate
  Mongo gateway bug; intentionally not used by the agent team.
- `docs/TROUBLESHOOTING.md` — day-two diagnostics for each tier.
- `src/backend/src/main/java/.../diag/DiagController.java` — the diag
  endpoints in one file.
- `src/backend/src/main/java/.../agents/AgentsService.java` — the
  RUN_TEAM call, structured logging, trace assembly with prompts and
  scheduler error.

## Will this resume work tomorrow with a fresh assistant?

Honest answer: **mostly yes, with two caveats.**

What works:

- All committed-or-on-disk file changes are preserved if you commit
  the checkpoint.
- This TODO is self-sufficient for the experimental plan, the diag
  endpoints, the gotchas, and the resume sequence.
- The corrected diagnosis lives in the ISSUE doc and is referenced
  from here.

What I'd flag:

1. The fresh assistant won't have memory of the **conversation** that
   led here (e.g. specific SQL output snippets, which Slack-thread
   sentence convinced me of `HS_IDLE_TIMEOUT = 5`). Anything that
   matters for the experiment is in this TODO or the ISSUE doc — but
   if you want to argue with the diagnosis, point them at those two
   files specifically.
2. The fresh assistant will only know the ADB column names by reading
   them from `/diag/columns` against a live deployment. If they try
   to write SQL against `USER_AI_AGENT_TEAMS.TEAM_NAME` they will get
   the same `ORA-00904` we got. The "Known gotchas" section above
   should prevent that.
