# Troubleshooting

Day-two playbook for the four tiers — ops, databases, back, front — plus
how to poke at each database from the ops bastion.

## Getting onto ops

```bash
python manage.py info        # prints ops public IP + the SSH command
ssh -i ~/.ssh/id_rsa opc@<ops_public_ip>
```

Once on ops, everything else in this doc assumes `opc@ops ~`.

The `opc` shell sources `~/endpoints.env` on login, which exports the
private IPs of the other tiers so you don't have to look them up:

```bash
echo "back=$BACK front=$FRONT db=$DB"
# e.g. back=10.0.2.4 front=10.0.2.5 db=10.0.3.3

curl -i http://$BACK:8080/api/v1/health
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=direct&runId=manual"
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=federated&runId=manual"
curl -sI http://$FRONT          # nginx on front
nc -zv $DB 1521                 # Oracle Free container
nc -zv $DB 5432                 # Postgres container
nc -zv $DB 27017                # Mongo container
```

## Connecting to the four databases from ops

The ops Ansible role pre-installs SQLcl, `psql`, and `mongosh`, and saves a
working connection for each engine. You don't need to copy SSH keys or
forward anything — the `databases` compute sits in the same VCN, and the
security list already lets ops reach 1521 / 5432 / 27017.

| Database         | Command   | Behind the scenes                                          |
| ---------------- | --------- | ---------------------------------------------------------- |
| ADB 26ai sidecar | `adb`     | `sql -name adb` (SQLcl saved connection via wallet)        |
| Oracle Free 26ai | `orafree` | `sql -name orafree` (SQLcl saved connection over JDBC URL) |
| PostgreSQL 18    | `pg`      | `psql -h <ip> -U postgres -d postgres` (reads `~/.pgpass`) |
| MongoDB 8        | `mg`      | `mongosh mongodb://admin:…@<ip>:27017/admin`               |

All four shortcuts live in `/home/opc/bin/` and are on PATH via `.bashrc`.

### Raw credentials (when you need to build a URI by hand)

The shortcuts embed the passwords so you never see them. For ad-hoc
commands (e.g. re-running `init.js`, attaching a GUI client) pull them
from `/home/opc/ansible_params.json`, which cloud-init wrote with
mode `0600` from the Terraform variables:

```bash
jq -r .mongo_db_password    /home/opc/ansible_params.json
jq -r .postgres_db_password /home/opc/ansible_params.json
jq -r .oracle_db_password   /home/opc/ansible_params.json   # SYSTEM on Oracle Free
jq -r .adb_admin_password   /home/opc/ansible_params.json   # ADMIN on ADB sidecar
```

Mongo connection string, ready to paste into `mongosh`. The password
often contains `#`, `@`, `/`, etc. — all reserved in a URI — so
percent-encode it with `jq`'s `@uri` filter before substitution,
otherwise mongosh errors with `MongoParseError: Password contains
unescaped characters`:

```bash
MONGO_PWD=$(jq -r '.mongo_db_password | @uri' /home/opc/ansible_params.json)
echo "mongodb://admin:${MONGO_PWD}@${DB}:27017/admin"

# e.g. re-seed support_tickets manually
mongosh "mongodb://admin:${MONGO_PWD}@${DB}:27017/admin" \
  /home/opc/ops/database/mongo/init.js
```

For the raw (un-encoded) password — e.g. to paste into a GUI client
that does its own escaping — drop the `| @uri`:

```bash
jq -r .mongo_db_password /home/opc/ansible_params.json
```

You can also just `cat /home/opc/bin/mg` — the password is rendered into
the wrapper script verbatim.

Quick sanity checks once connected:

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

## Ops tier

### Cloud-init log

Everything the instance did on first boot ends up here:

```bash
sudo tail -200 /var/log/cloud-init-output.log
```

The Ansible run's own tee'd log:

```bash
tail -200 ~/ansible-playbook.log
```

Liquibase + mongosh output from all four engines:

```bash
sudo cat /home/opc/ops/liquibase.log
```

### Re-run the ops Ansible role by hand

If cloud-init failed partway through, you can re-run the playbook without
rebuilding the instance:

```bash
sudo ANSIBLE_PYTHON_INTERPRETER=/usr/bin/python3 ansible-playbook \
    -i /home/opc/ops.ini \
    --extra-vars @/home/opc/ansible_params.json \
    /home/opc/ansible_ops/server.yaml
```

Liquibase is idempotent across its own changesets, so re-running is safe.

### `ORA-17957: SSO KeyStore not available` → `SSO not found`

`com.oracle.database.jdbc:ojdbc11` alone does **not** include the JCE
provider for the SSO wallet format. `oraclepki` must ride alongside it.
Without it you get `NoSuchAlgorithmException: SSO KeyStore not available`.
(The legacy `osdt_core` / `osdt_cert` companions are not published past
21.21.0.0 on Maven Central and modern `oraclepki` 23.x no longer needs
them.)

- **Ops-side (Liquibase)**: the jar is installed to `/opt/liquibase/lib`
  by the ops role. Verify:

  ```bash
  ls /opt/liquibase/lib/oraclepki-*.jar
  ```

  If missing, fetch it:

  ```bash
  sudo curl -fL -o /opt/liquibase/lib/oraclepki-23.9.0.25.07.jar \
    https://repo1.maven.org/maven2/com/oracle/database/security/oraclepki/23.9.0.25.07/oraclepki-23.9.0.25.07.jar
  ```

- **Back-side (Spring Boot)**: `src/backend/build.gradle` pulls
  `com.oracle.database.spring:oracle-spring-boot-starter-wallet`, which
  brings the PKI jars in transitively and is supposed to handle the
  `sqlnet.ora` `?/network/admin` path rewrite for you. A missing-PKI
  failure shows up as the Spring context failing to start and
  `curl http://$BACK:8080/actuator/health` returning nothing. Fix:
  **rebuild** the JAR (`python manage.py build`) and replace the back
  instance — a stale JAR without the starter won't pick it up.

If the starter doesn't handle the `sqlnet.ora` path rewrite out of the
box (observed behavior pending verification), the manual fallback is:

```bash
sed -i 's|?/network/admin|/home/opc/ops/wallet|' /home/opc/ops/wallet/sqlnet.ora
```

### `ORA-01033: ORACLE initialization or shutdown in progress`

The Oracle Free PDB is not open yet. The listener on 1521 comes up first;
the PDB follows ~60–180s later. The ops role polls `liquibase status` on a
retry loop precisely for this — if a manual run hits it, wait a minute and
retry.

### `changesum from database does not match changelog` (Liquibase)

You edited a changeset after it was applied to ADB. Either:

```bash
cd /home/opc/ops/database/liquibase/adb
liquibase --defaults-file=liquibase.properties clearChecksums
liquibase --defaults-file=liquibase.properties update
```

or run a proper `rollback` first. In a POC `clearChecksums` is almost
always what you want.

## Databases tier (podman containers)

The databases compute has no public IP; the ops bastion talks to it over
the VCN. For day-to-day ops you don't need a shell on the databases host
— use the `adb` / `orafree` / `pg` / `mg` shortcuts on ops. When you do
need a shell (container failed to start, want to read `journalctl`),
hop from ops:

```bash
# from your laptop, with agent forwarding so the key on ops can reach databases
ssh -A opc@<ops_public_ip>
# then on ops
ssh -o StrictHostKeyChecking=accept-new opc@"$DB"
```

The same key is authorized on both hosts (`deploy/tf/modules/databases/compute.tf`),
so no extra key copy is needed. OCI Bastion service sessions also work
if you prefer not to expose ops as a jump host.

### Check that the three containers are running

From the ops bastion:

```bash
# probe each port
nc -zv $DB 1521
nc -zv $DB 5432
nc -zv $DB 27017
```

If the port is closed, the container didn't start. Hop onto the databases
compute (see above), then:

```bash
sudo systemctl status oracle postgres mongo --no-pager
sudo journalctl -u postgres -n 200 --no-pager   # swap unit name as needed
sudo podman ps --all
sudo podman logs --tail 200 postgres            # or oracle / mongo
```

### Postgres container exits 1 immediately on first boot

The `postgres:18-alpine` image refuses to start when the persistent
volume is mounted at `/var/lib/postgresql/data` (the v17 convention).
You'll see this in `journalctl -u postgres` or `podman logs postgres`:

```
Error: in 18+, these Docker images are configured to store database data in a
       format which is compatible with "pg_ctlcluster" ...
       The suggested container configuration for 18+ is to place a single mount
       at /var/lib/postgresql which will then place PostgreSQL data in a
       subdirectory ...
```

The systemd unit will retry until `Start request repeated too quickly`
locks it out, after which `systemctl start postgres` is a no-op until
the cooldown clears. Recovery on a running host (the source template
`deploy/ansible/databases/roles/podman/files/postgres.service.j2`
already mounts at the correct path; this recipe is for hosts deployed
before the fix):

```bash
# /data/postgres should be empty on a clean failure — nothing to migrate
sudo ls -la /data/postgres

sudo sed -i 's|/data/postgres:/var/lib/postgresql/data:Z|/data/postgres:/var/lib/postgresql:Z|' \
  /etc/systemd/system/postgres.service
sudo systemctl daemon-reload
sudo systemctl reset-failed postgres
sudo systemctl restart postgres
sudo podman logs --tail 30 postgres   # expect "ready to accept connections"
```

If `/data/postgres` already contains v17-format data you want to keep,
that's a `pg_upgrade` problem — out of scope for this POC; nuke the
volume (`sudo rm -rf /data/postgres/*`) and let Liquibase reseed.

A related failure surfaces _after_ the mount-path fix:

```
mkdir: can't create directory '/var/lib/postgresql/18/': Permission denied
```

`postgres:18-alpine` runs as UID 70, not the debian image's UID 999. The
ansible role now creates `/data/postgres` owned by 70 (`deploy/ansible/
databases/roles/podman/tasks/main.yaml`); on a host deployed before that
fix, recover with:

```bash
sudo chown 70:70 /data/postgres
sudo systemctl reset-failed postgres
sudo systemctl restart postgres
```

### Oracle Free container slow first boot

First run of `container-registry.oracle.com/database/free:latest` does a
full initdb into `/data/oracle` and can take 5–10 minutes. Look for the
line `DATABASE IS READY TO USE!` in `podman logs oracle`.

### Credentials changed but container ignored it

`ORACLE_PWD`, `POSTGRES_PASSWORD`, `MONGO_INITDB_ROOT_PASSWORD` are only
consumed on the **first** boot, when the data directory is empty. If you
re-run Terraform with a different password, the container keeps the old
one. Either:

- Nuke the volume: `sudo rm -rf /data/oracle` (or /data/postgres, /data/mongo)
  and restart the service, **or**
- Change the password in-place from a SQL client.

## Back tier (Spring Boot)

### Service status + logs

Quick health check from ops without jumping:

```bash
curl -i http://$BACK:8080/actuator/health
```

For the service-level view you do need a shell on the back instance. Use
the ops bastion as a jump host (needs your laptop's SSH agent forwarded):

```bash
# from your laptop, not ops
ssh -J opc@<ops_public_ip> opc@<back_private_ip_from_`echo $BACK`_on_ops>
# then
sudo systemctl status back
sudo journalctl -u back -n 200 -f
```

### Backend returns 500 on `/api/v1/query`

The endpoint shape is `GET /api/v1/query?table=<t>&route=<r>&runId=<id>`
where `table ∈ {accounts, transactions, policies, rules, support_tickets}`
and `route ∈ {direct, federated}`. From ops:

```bash
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=direct&runId=manual"
```

Most common causes on first boot:

1. **`ORA-17957 SSO KeyStore not available`** when Spring tries to open
   the ADB datasource. Same root cause as the ops-side fix. The back
   Ansible role applies it — if you see it, rerun the back playbook.
2. **`ORA-01033`** — the Oracle Free PDB wasn't open yet when the backend
   started. `systemctl restart back` after waiting a minute.
3. **`Communications link failure`** to PG or Mongo — the containers
   aren't up (see databases tier above).
4. **Empty result + `error: "...does not exist"`** — Liquibase / mongosh
   seeding never ran. Check `/home/opc/ops/liquibase.log` on ops; if
   missing, the ops playbook bailed out early — re-run it (see
   "Re-run the ops Ansible role by hand" above).

The response body carries the per-call elapsed time, row count, and
(on failure) an `error` field, so curl the JSON before SSHing anywhere.

### Change the backend without rebuilding

`systemctl restart back` reloads from
`/home/opc/back/backend-1.0.0.jar` and the YAML config at
`/home/opc/back/config/application.yaml`. No need to re-run Terraform for
config-only fixes.

## Front tier (nginx + Angular)

### Service status + logs

From ops (no ssh needed to test reachability):

```bash
curl -sI http://$FRONT
curl -s  http://$FRONT | head -40
```

For the service-level view you do need a shell on the front instance:

```bash
# from your laptop
ssh -J opc@<ops_public_ip> opc@<front_private_ip_from_`echo $FRONT`_on_ops>
sudo systemctl status nginx
sudo journalctl -u nginx -n 100
sudo tail -200 /var/log/nginx/error.log
```

### Page loads but buttons show "Request failed"

The backend is unreachable or returning 5xx. Hit it from ops directly:

```bash
curl -i http://$BACK:8080/api/v1/health
curl -i "http://$BACK:8080/api/v1/query?table=accounts&route=direct&runId=manual"
```

If `/health` is green but `/query` fails, the issue is in the back tier.
If `/health` also fails, the back service isn't running or the NSG / seclist
isn't letting traffic through on 8080.

### Load balancer routing

The LB's backend set has both `front` (`/`) and `back` (`/api/*`,
`/actuator/*`). A 502 from the LB usually means the backend-set health
check is red — fix whichever instance is failing, don't fiddle with the LB.

```bash
# from your laptop
curl -i http://$(terraform -chdir=deploy/tf/app output -raw lb_public_ip)/api/v1/health
```

## Federated query sanity check (ADB → DB_LINKs)

If `route=direct` works but `route=federated` fails, the problem is in
the ADB-side federation. Connect to ADB from ops and inspect:

```bash
adb
```

```sql
-- DB_LINKs that should exist
SELECT db_link, host, created FROM user_db_links;

-- Test each hop
SELECT COUNT(*) FROM accounts@ORAFREE_LINK;
SELECT COUNT(*) FROM "public"."policies"@PG_LINK;
SELECT COUNT(*) FROM "support_tickets"@MONGO_LINK;

-- Test the wrapper views
SELECT * FROM V_ACCOUNTS;
SELECT * FROM V_POLICIES;
SELECT * FROM V_SUPPORT_TICKETS;
```

Common failures:

- **`ORA-12154 TNS: could not resolve ...`** — the heterogeneous gateway
  doesn't recognise the `db_type` you passed, or the target version isn't
  in `HETEROGENEOUS_CONNECTIVITY_INFO`. Check gap #10 in `NOTES.md`.
- **`"public"."policies" does not exist`** — schema prefix mismatch. Edit
  the view in `002-db-links.yaml` to drop or add the `"public"` qualifier,
  re-run ADB Liquibase.
- **`ORA-28759 failure to open file`** — wallet path issue on ADB side;
  only happens if you added a TCPS link with `directory_name`.

## Nuke-and-reseed the banking demo data

If the seed data drifts from what the views expect:

```bash
# On ops
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
