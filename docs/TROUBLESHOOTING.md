# Troubleshooting

Diagnostic toolkit for the four tiers — ops, databases, back, front.
Discovery commands first; recipes for non-obvious operations last.
When something breaks, work top-down: probe from ops, hop to the
relevant host, read the logs, inspect the rendered configs.

## Getting onto ops

```bash
python manage.py info        # prints ops public IP + the SSH command
ssh -i ~/.ssh/id_rsa opc@<ops_public_ip>
```

The rest of this doc assumes a shell on ops. The `opc` shell sources
`~/endpoints.env` on login, exporting the private IPs of the other
tiers as `$BACK`, `$FRONT`, `$DB`.

## Probe everything from ops (no extra ssh)

```bash
echo "back=$BACK front=$FRONT db=$DB"

# back tier
curl -i http://$BACK:8080/actuator/health
curl -i http://$BACK:8080/api/v1/health
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=direct&runId=manual"
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=federated&runId=manual"

# front tier
curl -sI http://$FRONT
curl -s  http://$FRONT | head -40

# databases tier (port-only — no auth needed to confirm reachability)
nc -zv $DB 1521          # Oracle Free
nc -zv $DB 5432          # Postgres
nc -zv $DB 27017         # Mongo

# load balancer (from your laptop, not ops)
curl -i http://$(terraform -chdir=deploy/tf/app output -raw lb_public_ip)/api/v1/health
```

`nc` failing with "no route to host" → firewall or routing.
`nc` succeeding but the SQL/curl call failing → the service is up but
something inside is wrong; move on to logs.

## Connect to the four databases from ops

The ops Ansible role pre-installs SQLcl, `psql`, and `mongosh`, and
saves a working connection for each engine:

| Database         | Command   | Behind the scenes                                          |
| ---------------- | --------- | ---------------------------------------------------------- |
| ADB 26ai sidecar | `adb`     | `sql -name adb` (SQLcl saved connection via wallet)        |
| Oracle Free 26ai | `orafree` | `sql -name orafree` (SQLcl saved connection over JDBC URL) |
| PostgreSQL 18    | `pg`      | `psql -h $DB -U postgres -d postgres` (reads `~/.pgpass`)  |
| MongoDB 8        | `mg`      | `mongosh mongodb://admin:…@$DB:27017/admin`                |

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
jq -r .adb_admin_password   /home/opc/ansible_params.json   # ADMIN on ADB sidecar
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

ops is the only tier with a public IP; back/front/databases are
private. Hop via ops with agent forwarding:

```bash
# from your laptop
ssh -A opc@<ops_public_ip>

# then on ops
ssh -o StrictHostKeyChecking=accept-new opc@$DB        # databases
ssh -o StrictHostKeyChecking=accept-new opc@$BACK      # back
ssh -o StrictHostKeyChecking=accept-new opc@$FRONT     # front

# or single-hop from your laptop
ssh -J opc@<ops_public_ip> opc@<private_ip>
```

The same key is authorized on all hosts (see
`deploy/tf/modules/*/compute.tf`), so no extra key copy is needed.

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
- **back**: `back`
- **front**: `nginx`
- **ops**: no long-running service — ops just bootstraps and exits

Quick service status:

```bash
sudo systemctl status oracle postgres mongo --no-pager   # databases
sudo systemctl status back --no-pager                    # back
sudo systemctl status nginx --no-pager                   # front
```

## Inspect rendered configs and vars

When behaviour doesn't match your Terraform variables, read what
cloud-init actually wrote:

```bash
echo "back=$BACK front=$FRONT db=$DB"
cat  /home/opc/endpoints.env

jq . /home/opc/ansible_params.json        # all rendered passwords + project vars
ls   /home/opc/ops/wallet/                # ADB wallet (ops)
cat  /home/opc/back/config/application.yaml   # back service config

sudo systemctl cat oracle                 # rendered systemd unit (databases)
sudo systemctl cat back                   # back service unit
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

# back
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/back.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_back/server.yaml

# front
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/front.ini --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_front/server.yaml
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
