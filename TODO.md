# Pending

## 1. Validate the keep-warm on the running deploy

After `python manage.py tf` + apply finishes, on the public LB:

```bash
F=$(python manage.py info | awk -F'http://' '/Frontend:/ {print $2; exit}')

curl -sS http://$F/api/v1/ready | jq .
curl -sS http://$F/api/v1/diag/links | jq '[.[] | .DB_LINK]'
# expect: PG_LINK + ORAFREE_LINK + MONGO_LINK

curl -sS http://$F/api/v1/diag/agents/inventory \
  | jq 'with_entries(.value |= (if type=="array" then ("rows="+(length|tostring)) else . end))'
# expect: teams=1, agents=4, tasks=4, tools=3, profileAttrs=23

curl -sS --max-time 120 http://$F/api/v1/diag/agents/sanity | jq .
# expect: ok=true

curl -sS -H 'Range: bytes=-50000' http://$F/actuator/logfile | grep -E "keepwarm_(ok|failed)" | tail -5
# expect: event=keepwarm_ok lines every ~60-150 s
```

If `/diag/links` shows only `ORAFREE_LINK` + `MONGO_LINK` (no
`PG_LINK`), the in-flight deploy used the pre-edit Liquibase. Run
`python manage.py tf && (cd deploy/tf/app && terraform apply)` once
more so it picks up the Liquibase changes; ansible-rerun is idempotent.

## 2. Stress-test the keep-warm against the original failure pattern

The wedge originally fired after ~50 min of agent-path idle while the
UI exercised other paths (Risk Dashboard refresh, Measurements load
button hitting `forkJoin` parallel federated queries). With the
keep-warm running, repeat that flow:

1. Open the UI, click around for ~50 min on Risk Dashboard / Current
   System / ADB Sidecar / Measurements (3-4 load presses), but **do
   not** open AI Assistant.
2. Then ask the AI Assistant a real compliance question (e.g.
   "What policies apply to international wires above $10K?").
3. First call should succeed (slow, ~70-150 s on cold reconnect is
   fine; 300-450 ms fast-fail is the wedge).
4. Confirm `/diag/agents/scheduler-failures?sinceMinutes=60` is empty.

If a wedge does fire under this flow, the 60 s keep-warm is too
infrequent (likely a worker-pool affinity issue). Drop the interval to
30 s in `selectai.agents.keepwarm.interval-ms` and retry.

## 3. Optional — Layer 4 RAG track

Add `BANKING_RAG` profile + `BANKING_POLICY_INDEX` vector index +
`COMPLIANCE_RAG_TOOL`, wired into `COMPLIANCE_OFFICER` alongside the
SQL tool. Only the `005-select-ai-agents.yaml` changeset needs the
additions. Re-validate per (2) after.

## 4. Deferred housekeeping (small, independent)

- `deploy/tf/modules/ops/userdata/bootstrap.tftpl` — `ansible_params.json`
  is appended on cloud-init re-run instead of overwritten. Dedupe
  workaround: `python3 -c "import json; raw=open('/home/opc/ansible_params.json').read().lstrip(); obj,_=json.JSONDecoder().raw_decode(raw); open('/home/opc/ansible_params.json','w').write(json.dumps(obj,indent=2))"`.
  Permanent fix: change the `cat <<EOT >>` to `cat <<EOT >`.
- The frontend demo cards for `policies` / `rules` (`/app` page) under
  `route=federated` previously rendered the raw `ORA-00942 V_POLICIES does
not exist` error. With the views back, this should resolve on its own —
  spot-check after deploy.
