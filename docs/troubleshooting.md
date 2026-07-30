# Troubleshooting

Diagnostic toolkit for the four tiers — ops, databases, backend, frontend.
Discovery commands first; recipes for non-obvious operations last.
When something breaks, work top-down: probe from ops, hop to the
relevant host, read the logs, inspect the rendered configs.

## Getting onto ops

```bash
./manage.py status        # prints ops public IP + the SSH command
ssh -i ~/.ssh/id_rsa opc@<ops_public_ip>
```

The rest of this doc assumes a shell on ops. The `opc` shell sources
`~/endpoints.env` on login, exporting the private IPs of the other
tiers as `$BACKEND`, `$FRONTEND`, `$DATABASES`.

## Probe everything from ops (no extra ssh)

```bash
echo "back=$BACKEND front=$FRONTEND db=$DATABASES"

# backend tier
curl -i http://$BACKEND:8080/actuator/health
curl -i http://$BACKEND:8080/api/v1/health
curl -i "http://$BACKEND:8080/api/v1/query?table=accounts&route=direct&runId=manual"
curl -i "http://$BACKEND:8080/api/v1/query?table=accounts&route=federated&runId=manual"

# frontend tier
curl -sI http://$FRONTEND
curl -s  http://$FRONTEND | head -40

# databases tier (port-only — no auth needed to confirm reachability)
nc -zv $DATABASES 1521          # Oracle Free
nc -zv $DATABASES 5432          # Postgres
nc -zv $DATABASES 27017         # Mongo

# load balancer (from your laptop, not ops)
curl -i http://$(terraform -chdir=deploy/terraform output -raw lb_public_ip)/api/v1/health
```

`nc` failing with "no route to host" → firewall or routing.
`nc` succeeding but the SQL/curl call failing → the service is up but
something inside is wrong; move on to logs.

## Connect to the four databases from ops

The ops Ansible role pre-installs SQLcl, `psql`, and `mongosh`, and
saves a working connection for each engine:

| Database                   | Command   | Behind the scenes                                          |
| -------------------------- | --------- | ---------------------------------------------------------- |
| AI Data Gateway (ADB 26ai) | `adb`     | `sql -name adb` (SQLcl saved connection via wallet)        |
| Oracle Free 26ai           | `orafree` | `sql -name orafree` (SQLcl saved connection over JDBC URL) |
| PostgreSQL 18              | `pg`      | `psql -h $DATABASES -U postgres -d postgres` (reads `~/.pgpass`)  |
| MongoDB 8                  | `mg`      | `mongosh mongodb://admin:…@$DATABASES:27017/admin`                |

All four shortcuts live in `/home/opc/bin/` and are on `PATH` via
`.bashrc`. Sanity queries once connected:

```sql
-- adb
SELECT * FROM USER_DB_LINKS;
SELECT * FROM V_ACCOUNTS;

-- orafree
SELECT table_name FROM user_tables;
SELECT * FROM accounts;
```

```bash
# pg
pg -c 'SELECT * FROM policies;'

# mg
mg --eval 'db.support_tickets.find().toArray()'
```

### Raw credentials (ad-hoc URIs / GUI clients)

Cloud-init wrote the passwords to `/home/opc/ansible_params.json`
(mode 0600). Pull them out with `jq`:

```bash
jq -r .mongo_db_password    /home/opc/ansible_params.json
jq -r .postgres_db_password /home/opc/ansible_params.json
jq -r .oracle_db_password   /home/opc/ansible_params.json   # SYSTEM on Oracle Free
jq -r .adb_admin_password   /home/opc/ansible_params.json   # ADMIN on the AI Data Gateway (ADB)
```

Mongo passwords often contain `#` / `@` / `/` — all reserved in a URI.
Percent-encode with `jq`'s `@uri` filter, otherwise mongosh fails with
`MongoParseError: Password contains unescaped characters`:

```bash
MONGO_PWD=$(jq -r '.mongo_db_password | @uri' /home/opc/ansible_params.json)
echo "mongodb://admin:${MONGO_PWD}@${DB}:27017/admin"
```

You can also just `cat /home/opc/bin/mg` — the password is rendered
into the wrapper verbatim.

## Hop to a private-tier host

ops is the only tier with a public IP; backend/frontend/databases are
private. Hop via ops with agent forwarding:

```bash
# from your laptop
ssh -A opc@<ops_public_ip>

# then on ops
ssh -o StrictHostKeyChecking=accept-new opc@$DATABASES        # databases
ssh -o StrictHostKeyChecking=accept-new opc@$BACKEND      # backend
ssh -o StrictHostKeyChecking=accept-new opc@$FRONTEND     # frontend

# or single-hop from your laptop
ssh -J opc@<ops_public_ip> opc@<private_ip>
```

The same key is authorized on all hosts (see
`deploy/terraform/modules/*/compute.tf`), so no extra key copy is needed.

## Read logs

| What                         | Where                                                 |
| ---------------------------- | ----------------------------------------------------- |
| cloud-init (per host)        | `sudo tail -200 /var/log/cloud-init-output.log`       |
| ansible run (per host)       | `tail -200 /home/opc/ansible-playbook.log`            |
| liquibase + mongosh seed     | `sudo cat /home/opc/ops/liquibase.log` (ops only)     |
| systemd unit (any tier)      | `sudo journalctl -u <unit> -n 200 --no-pager`         |
| systemd unit (live)          | `sudo journalctl -u <unit> -f`                        |
| podman container (databases) | `sudo podman logs --tail 200 <oracle/postgres/mongo>` |

Unit names per tier:

- **databases**: `oracle`, `postgres`, `mongo`
- **backend**: `backend`
- **frontend**: `nginx`
- **ops**: no long-running service — ops just bootstraps and exits

Quick service status:

```bash
sudo systemctl status oracle postgres mongo --no-pager   # databases
sudo systemctl status backend --no-pager                 # backend
sudo systemctl status nginx --no-pager                   # frontend
```

## Mining the backend log

Every backend line is `key=value` after a `rid=` request id, so the log is
greppable without a parser. The application log is
`/home/opc/backend/logs/app.log`, also reachable without SSH:

```bash
curl -s -H 'Range: bytes=-200000' http://<lb_public_ip>/actuator/logfile
```

### Follow one request end to end

Every HTTP response carries `X-Request-Id`. That id tags every line the request
produced — controller, agents, Select AI tools and each datasource:

```bash
curl -si http://<lb>/api/v1/agents -d '{"prompt":"..."}' -H 'Content-Type: application/json' | grep -i x-request-id
grep 'rid=<id>' app.log
```

### The event vocabulary

| Event | Emitted when | Useful fields |
| ----- | ------------ | ------------- |
| `http` | every request completes | `method` `path` `status` `elapsed_ms` |
| `db_query` | a table is read | `table` `route` `engine` `rows` `elapsed_ms` |
| `db_query_failed` | that read throws | plus `cause` |
| `run_team_start` | an agent run begins | `conv` `team` `prompt_chars` `threaded` |
| `run_team_done` | it returns normally | `elapsed_ms` `answer_chars` |
| `run_team_recovered` | `RUN_TEAM` threw but the answer was recovered from the catalog | `elapsed_ms` `answer_chars` `ora` |
| `run_team_failed` | it threw with nothing recoverable | `elapsed_ms` `ora` |
| `run_team_retry` | the gateway idle-drop retry fired | `attempt` `cause` |
| `agent_task` | one line per agent in the run | `agent` `task` `state` `duration_ms` |
| `agent_tool` | one line per Select AI tool call | `tool` `agent` `duration_ms` `output_chars` |
| `readiness` | a component changes state | `component` `state` `from` `cause` |

`http`, readiness polls and `/actuator` are logged at DEBUG so they don't drown
the file; everything above is INFO or higher.

### Patterns worth watching

```bash
# Are agent runs failing, and are they being recovered?
grep -c run_team_recovered app.log ; grep -c run_team_failed app.log

# Latency profile of agent runs (spikes are LLM variance, not faults)
grep -o 'event=run_team_done.*elapsed_ms=[0-9]*' app.log | grep -o 'elapsed_ms=[0-9]*' | sort -t= -k2 -n | tail

# Which agent or tool is slow
grep 'event=agent_task' app.log | grep -o 'agent=[A-Z_]* .*duration_ms=[0-9]*'
grep 'event=agent_tool' app.log | grep -o 'tool=[A-Z_]* .*duration_ms=[0-9]*'

# direct vs federated cost per table
grep 'event=db_query ' app.log | grep -o 'table=[a-z_]* route=[a-z]* engine=[a-z]* .*elapsed_ms=[0-9.]*'

# Did a tier flap?
grep 'event=readiness' app.log
```

A `run_team_recovered` line means the agents finished but `RUN_TEAM` failed on
its return path — the user still got an answer. See
[`known-limitation-pg-link-gateway.md`](known-limitation-pg-link-gateway.md).

### Turn up detail temporarily

Logging levels are live-adjustable through actuator, no restart:

```bash
curl -X POST http://<lb>/actuator/loggers/dev.victormartin.aidatagateway \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

## Inspect rendered configs and vars

When behaviour doesn't match your Terraform variables, read what
cloud-init actually wrote:

```bash
echo "back=$BACKEND front=$FRONTEND db=$DATABASES"
cat  /home/opc/endpoints.env

jq . /home/opc/ansible_params.json        # all rendered passwords + project vars
ls   /home/opc/ops/wallet/                # ADB wallet (ops)
cat  /home/opc/backend/config/application.yaml   # backend service config

sudo systemctl cat oracle                 # rendered systemd unit (databases)
sudo systemctl cat backend                # backend service unit
```

## Re-run ansible per tier

If cloud-init failed partway through, re-run the playbook by hand —
all roles are idempotent.

```bash
# ops
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/ops.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_ops/server.yaml

# databases
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/databases.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_databases/server.yaml

# backend
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/backend.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_backend/server.yaml

# frontend
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/frontend.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_frontend/server.yaml
```

## Re-run cloud-init bootstrap (when even ansible never ran)

If `/var/log/cloud-init-output.log` shows the user-data script aborted
early (e.g. the `wait_for_dns` guard), the rendered script is still on
disk. Re-run it directly:

```bash
sudo ls /var/lib/cloud/instance/scripts/
sudo bash /var/lib/cloud/instance/scripts/part-001 \
    2>&1 | sudo tee /var/log/bootstrap-rerun.log
```

## AI agent path health

`RUN_TEAM` (the `/api/v1/agents` endpoint) reaches OCI Generative AI over
UTL_HTTP and reads `V_BNK_*` views, some of which resolve through the
`PG_LINK` heterogeneous gateway. Two things can go wrong; both are mitigated
in `AgentsService` and both are visible in the log.

Quick health probes (run from your laptop against the public LB, or from ops
against `$BACKEND:8080`):

```bash
F=<lb_public_ip from ./manage.py status>

# Agent end-to-end (expect ok=true; 50-90 s is normal, 150 s still normal under load)
curl -sS --max-time 200 http://$F/api/v1/diag/agents/sanity | jq .

# Did any run fail on the return path but still deliver an answer?
curl -sS -H 'Range: bytes=-200000' http://$F/actuator/logfile | grep -c run_team_recovered

# Per-agent and per-tool timings for recent runs
curl -sS -H 'Range: bytes=-200000' http://$F/actuator/logfile | grep -E "event=agent_(task|tool)" | tail -20
```

Read a failure by elapsed time, not by the ORA number — `ORA-01010` appears in
more than one mode:

- **50–90 s and failed** — the agents almost certainly finished. Check
  `user_ai_agent_task_history` for the execution; if every task is `SUCCEEDED`
  the answer was recoverable and the log will carry `event=run_team_recovered`.
- **300–450 ms and failed** — a gateway error surfacing on the agent path. One
  transparent retry is already applied; a second consecutive failure means the
  gateway is genuinely down, so check `/api/v1/diag/links/probe`.
- **Slow but successful** — not a fault. `RUN_TEAM` latency varies with LLM
  load; spikes to 150 s were measured on a healthy system.

Full diagnosis, and the explanations that were tried and abandoned, in
[`known-limitation-pg-link-gateway.md`](./known-limitation-pg-link-gateway.md).

## Federated query sanity SQL

If `route=direct` works but `route=federated` doesn't, connect to ADB
and check the link state directly. The links and views are defined in
`database/liquibase/adb/002-db-links.yaml`.

```sql
-- adb
SELECT db_link, host, created FROM user_db_links;

-- one-hop test per link
SELECT COUNT(*) FROM accounts@ORAFREE_LINK;
SELECT COUNT(*) FROM "public"."policies"@PG_LINK;
SELECT COUNT(*) FROM "support_tickets"@MONGO_LINK;

-- the wrapper views the backend actually queries
SELECT * FROM V_ACCOUNTS;
SELECT * FROM V_POLICIES;
SELECT * FROM V_SUPPORT_TICKETS;
```

## Nuke-and-reseed the banking demo data

When seed data drifts from what the views expect:

```bash
# on ops
cd /home/opc/ops/database/liquibase/oracle
liquibase --defaults-file=liquibase.properties rollback-count 2
liquibase --defaults-file=liquibase.properties update

cd /home/opc/ops/database/liquibase/postgres
liquibase --defaults-file=liquibase.properties rollback-count 2
liquibase --defaults-file=liquibase.properties update

# Mongo: init.js is idempotent on first run; to re-seed manually:
mg admin --eval 'db.support_tickets.drop()'
mg /home/opc/ops/database/mongo/init.js
```
