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

`RUN_TEAM` (the `/api/v1/agents` endpoint) runs in a `DBMS_SCHEDULER`
worker session that caches its own `PG_LINK` gateway connection. That
connection dies after `HS_IDLE_TIMEOUT = 5 min`; without continuous
traffic on the agent path, the worker's cached handle goes stale and
the next `RUN_TEAM` fast-fails on `TASK_0` with `ORA-01010 / ORA-02063
from PG_LINK`. The backend ships an always-on keep-warm
(`AgentsService.keepWarm()`, every 60 s) that prevents this.

Quick health probes (run from your laptop against the public LB, or
from ops against `$BACKEND:8080`):

```bash
F=<frontend_ip from ./manage.py status>

# Agent end-to-end (expect ok=true; elapsed 30-70s normal, 70-150s on cold reconnect)
curl -sS --max-time 120 http://$F/api/v1/diag/agents/sanity | jq .

# Recent scheduler-side failures (expect [] when keep-warm is doing its job)
curl -sS http://$F/api/v1/diag/agents/scheduler-failures?sinceMinutes=15 | jq .

# Confirm keep-warm is firing in the back log
curl -sS -H 'Range: bytes=-50000' http://$F/actuator/logfile | grep -E "keepwarm_(ok|failed)" | tail -10
```

If sanity fast-fails (~300-450 ms, `ORA-02063 from PG_LINK`), the
keep-warm has stopped or never started. Check:

- `curl http://$F/actuator/scheduledtasks` — should list
  `keepWarm` with `fixedDelay=60000`.
- back log for `event=keepwarm_failed` — recent entries indicate why.
- `selectai.agents.keepwarm.enabled` in `/home/opc/backend/config/application.yaml`
  on the backend host — must be `true`.

Recovery if a wedge has already fired: ~10 min of idle clears it (the
scheduler reaps the poisoned worker). Drop+recreate of the link or
team does NOT recover. Full diagnosis in
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
