# Federated Queries from the ADB 26ai Sidecar

How the ADB 26ai sidecar reaches the three simulated "production" databases
in this POC:

- Oracle Database Free 26ai (homogeneous Oracle → Oracle)
- PostgreSQL 18 (Oracle-managed heterogeneous gateway)
- MongoDB 8 (Oracle-managed heterogeneous gateway)

All three are created with **`DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`**.
Classic `CREATE DATABASE LINK` DDL is not the supported path on Autonomous
Database — go through the package.

Scope: ADB is always the **initiator**. The three prod DBs never call ADB.
Workload on ADB is OLTP / ECPU, **private endpoint** in the same VCN as the
`databases` compute; targets are reached via `private_target => TRUE`.

The three production engines hold a small banking demo dataset, seeded by
Liquibase / `mongosh` on first deploy:

- Oracle Free — `accounts`, `transactions`
- PostgreSQL — `policies`, `rules`
- MongoDB — `support_tickets` (in a dedicated `banking` database — **not**
  `admin`, see §4 for why)

ADB surfaces each remote table as a view (`V_ACCOUNTS`, `V_TRANSACTIONS`,
`V_POLICIES`, `V_RULES`, `V_SUPPORT_TICKETS`) so the rest of the stack does
not care where the data physically lives.

---

## 0. Two hard requirements learned the painful way

Both of these are not in the Oracle error messages as written; you have to
know to look for them.

### 0.1 `hostname` must be a DNS-resolvable name, not a raw IP

`DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK` validates its `hostname` argument and
rejects anything that looks like a raw IPv4 or IPv6 literal:

```
ORA-20000: Invalid host name 10.0.3.231 specified
ORA-06512: at "C##CLOUD$SERVICE.DBMS_CLOUD_DBLINK_INTERNAL", ...
```

This is an ADB-specific guard against link targets being pinned to arbitrary
IPs. The fix is to pass a hostname the VCN's internal DNS can resolve. In
this POC the target compute is created with `assign_private_dns_record = true`
and a `hostname_label`, so OCI registers it in the VCN's private resolver.
The full form is:

```
<hostname_label>.<subnet_dns_label>.<vcn_dns_label>.oraclevcn.com
```

For this project that resolves to e.g.
`databasesadbsidecarxy.db.vcnadbsidecarxy.oraclevcn.com` (the `xy` is the
random 2-char deploy ID). Terraform builds this string from locals and
passes it down as `databases_fqdn`; Liquibase substitutes it into every
`CREATE_DATABASE_LINK` call via the `${databases_fqdn}` parameter.

ADB's private endpoint lives in the same VCN, so its DNS resolver sees
the same private record and resolves the FQDN to the target's private IP.

### 0.2 Put MongoDB data outside the `admin` database

Mongo's `admin` database is the auth database — where user accounts live —
and the DataDirect MongoDB ODBC driver that ADB's heterogeneous gateway
uses does **not** expose collections stored there through its table
catalog. Liquibase's `CREATE OR REPLACE VIEW V_SUPPORT_TICKETS` returns:

```
ORA-28500: connection from ORACLE to a non-Oracle system returned this message:
[DataDirect][ODBC MongoDB driver][MongoDB]syntax error or access rule violation:
object not found: support_tickets {42S22, NativeErr = -5501}
ORA-02063: preceding 2 lines from MONGO_LINK
```

The collection _physically_ exists — `db.support_tickets.countDocuments({})`
from `mongosh` returns the expected count — but the gateway can't see it to
resolve the table reference. Move the collection to a non-`admin` database
and point `MONGO_LINK`'s `service_name` at that database. Auth still
happens through the `admin` user; only the query target changes.

Our `init.js` seeds `support_tickets` into the `banking` database, and the
Liquibase changeset creates `MONGO_LINK` with `service_name => 'banking'`.

---

## 1. Credentials (all three links)

Credentials are stored per-schema via `DBMS_CLOUD.CREATE_CREDENTIAL` and
referenced by name from `CREATE_DATABASE_LINK`.

```sql
BEGIN
  DBMS_CLOUD.CREATE_CREDENTIAL(
    credential_name => 'ORAFREE_CRED',
    username        => 'SYSTEM',
    password        => '&oracle_db_password.'
  );
END;
/
```

Repeat for `PG_CRED` and `MONGO_CRED` with the matching app/admin user.
Drop with `DBMS_CLOUD.DROP_CREDENTIAL`.

Docs:

- [DBMS_CLOUD.CREATE_CREDENTIAL](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-package-cred.html)

---

## 2. ADB → Oracle Database Free 26ai

Homogeneous Oracle-to-Oracle. No `gateway_params` needed.

```sql
BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name    => 'ORAFREE_LINK',
    hostname        => '&databases_fqdn.',
    port            => 1521,
    service_name    => 'FREEPDB1',
    credential_name => 'ORAFREE_CRED',
    ssl_server_cert_dn => NULL,
    public_link     => FALSE,
    private_target  => TRUE
  );
END;
/

SELECT id, customer_name, balance  FROM accounts@ORAFREE_LINK ORDER BY id;
SELECT id, account_id, amount, tx_date FROM transactions@ORAFREE_LINK ORDER BY id;
```

- `service_name` is the PDB service (`FREEPDB1` on the Free image), not the CDB.
- Plain TCP on 1521 is fine for this POC — Oracle Database Free does not ship
  a documented TCPS env-var; enabling TCPS would require manual `orapki` +
  `configTcps.sh` inside the container.
- `private_target => TRUE` is the supported setting here because the ADB is
  provisioned with a **private endpoint** in the same VCN as the target (see §5).

Docs:

- [CREATE_DATABASE_LINK procedure](https://docs.oracle.com/en-us/iaas/autonomous-database/doc/ref-dbms_cloud_admin-create_database_link-procedure.html)

---

## 3. ADB → PostgreSQL 18

Uses the Oracle-managed heterogeneous gateway. Flip with
`gateway_params => JSON_OBJECT('db_type' VALUE 'postgres')`.

```sql
BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name    => 'PG_LINK',
    hostname        => '&databases_fqdn.',
    port            => 5432,
    service_name    => 'postgres',                -- PG database name
    credential_name => 'PG_CRED',
    gateway_params  => JSON_OBJECT('db_type' VALUE 'postgres'),
    public_link     => FALSE,
    private_target  => TRUE
  );
END;
/

SELECT "id", "name", "description"           FROM "policies"@PG_LINK ORDER BY "id";
SELECT "id", "policy_id", "expression"       FROM "rules"@PG_LINK    ORDER BY "id";
```

- PG identifiers are case-sensitive; quote table and column names.
- If the gateway requires a schema prefix, use `"public"."policies"@PG_LINK`.
  The POC ships the unqualified form — adjust in `002-db-links.yaml` if a fresh
  deploy surfaces a "relation does not exist" error.
- **Query is the first-class operation.** DML works for simple single-row
  inserts/updates. **DDL through the link is not supported.** There is no
  two-phase commit across the gateway, so don't mix Oracle writes with PG
  writes in one transaction.
- `HETEROGENEOUS_CONNECTIVITY_INFO` on ADB lists any optional
  `gateway_params` sub-keys per `db_type`.

Docs:

- [Oracle-managed heterogeneous connectivity](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/database-links-other-databases-oracle-managed.html)

---

## 4. ADB → MongoDB 8

> **Status in this POC: blocked by an ADB-side bug. The link is created,
> but every SELECT through it fails.** See
> [`ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md`](./ISSUE_ADB_HETEROGENEOUS_MONGODB_OBJECT_NOT_FOUND.md)
> for the full reproducer, server-side traces, and the variations we
> ruled out. The `adb-002-view-support-tickets` Liquibase changeset has
> been removed; `MONGO_CRED` and `MONGO_LINK` are still created so the
> path can be re-tested against future ADB releases. The backend's
> "Via ADB sidecar" endpoint surfaces a static "known limitation" note
> for MongoDB instead of attempting the doomed query.

Also Oracle-managed heterogeneous, `db_type = 'mongodb'`, port 27017.

```sql
BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name    => 'MONGO_LINK',
    hostname        => '&databases_fqdn.',
    port            => 27017,
    service_name    => 'banking',                 -- target Mongo database (NOT `admin`; see §0.2)
    credential_name => 'MONGO_CRED',
    gateway_params  => JSON_OBJECT('db_type' VALUE 'mongodb'),
    public_link     => FALSE,
    private_target  => TRUE
  );
END;
/

-- Collections appear as tables. Names are case-sensitive.
SELECT CAST("ticket_id" AS NUMBER)        AS ticket_id,
       CAST("customer"  AS VARCHAR2(100)) AS customer,
       CAST("subject"   AS VARCHAR2(200)) AS subject,
       CAST("status"    AS VARCHAR2(20))  AS status
FROM   "support_tickets"@MONGO_LINK
ORDER BY ticket_id;
```

- The container in this POC runs with `MONGO_INITDB_ROOT_USERNAME=admin`.
  The root user lives in the `admin` database (Mongo's auth DB) but the
  `support_tickets` collection is seeded into a separate `banking`
  database by `database/mongo/init.js`. `service_name => 'banking'` points
  the link at the data, and the `admin` user authenticates via
  `MONGO_CRED` regardless — Mongo's auth layer is separate from the
  query-target database. See §0.2 for why we can't just use `admin`.
- The gateway presents collections as relational-looking tables. Cast to
  concrete Oracle types so JDBC clients get a stable schema — without the
  `CAST(...)` wrappers column widths can shift between queries.
- MongoDB-specific `gateway_params` sub-keys, if any, are listed in
  `HETEROGENEOUS_CONNECTIVITY_INFO` — check before assuming defaults.

> The Oracle **Database API for MongoDB** (ORDS on ADB speaking the Mongo
> wire protocol) is a _different_ feature: it lets Mongo clients read/write
> ADB SODA collections. Not needed here — we want ADB to read the prod
> Mongo, not the other way around.

Docs:

- [Oracle-managed heterogeneous connectivity](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/database-links-other-databases-oracle-managed.html)

---

## 5. Networking prerequisites

Current POC: **ADB on a private endpoint in `db_subnet` (10.0.3.0/24)** —
same subnet as the `databases` compute, attached to `nsg_adb` — and every
link uses `private_target => TRUE`.

- The ADB private endpoint is allocated a VCN-local IP on port 1522. The
  Spring Boot backend and the ops bastion reach ADB through that endpoint;
  the wallet is bundled with the usual `TNS_ADMIN` + mTLS.
- Egress from the ADB private endpoint toward the `databases` compute on
  1521 / 5432 / 27017 is allowed because the two sit in the same subnet
  (`db_seclist` explicitly allows `db_subnet_cidr` as source for those ports).
- NSG `nsg_adb` exposes 1522 inbound from `app_subnet` (back) and
  `public_subnet` (ops) only — outbound is the VCN default (stateful allow).
- ACL-based inbound to ADB is not used; private endpoint + NSG is sufficient.

If you ever need the public-endpoint variant back (for a demo without a VCN)
you would also need to expose the `databases` compute publicly or drop the
heterogeneous links — `private_target => TRUE` is the only path that reaches
the `databases` compute as wired today.

---

## 6. Liquibase changeset skeleton

The real file will live at `database/liquibase/adb/002-db-links.yaml` and
be parameterized from Jinja-rendered `liquibase.properties`. Skeleton:

```yaml
databaseChangeLog:
  - changeSet:
      id: adb-db-link-cred-orafree
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              BEGIN
                DBMS_CLOUD.CREATE_CREDENTIAL(
                  credential_name => 'ORAFREE_CRED',
                  username        => 'SYSTEM',
                  password        => '${oracle_db_password}'
                );
              END;
              /

  - changeSet:
      id: adb-db-link-orafree
      author: adbsidecar
      changes:
        - sql:
            endDelimiter: "/"
            splitStatements: false
            sql: |
              BEGIN
                DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
                  db_link_name    => 'ORAFREE_LINK',
                  hostname        => '${databases_fqdn}',
                  port            => 1521,
                  service_name    => 'FREEPDB1',
                  credential_name => 'ORAFREE_CRED',
                  public_link     => FALSE,
                  private_target  => TRUE
                );
              END;
              /
```

Repeat for `PG_*` and `MONGO_*` with the relevant `gateway_params`.
Rollbacks use `DBMS_CLOUD_ADMIN.DROP_DATABASE_LINK` +
`DBMS_CLOUD.DROP_CREDENTIAL`.

The `${databases_fqdn}` parameter is plumbed end-to-end: Terraform
(`deploy/tf/app/main.tf`) builds the VCN-internal FQDN from the
`hostname_label` + subnet + VCN DNS labels and passes it into the ops
instance's `ansible_params.json`. Ansible renders it into
`liquibase.properties`, and Liquibase substitutes it into every
`CREATE_DATABASE_LINK` call at changeset execution time. See §0.1 for
why a raw IP would be rejected.

---

## 7. Gotchas worth knowing before you debug

- `DBMS_CLOUD_ADMIN`, not `DBMS_CLOUD_LINK`. The latter does not exist.
- DB links created by the package show up in `USER_DB_LINKS` / `DBA_DB_LINKS`.
- Heterogeneous links are **query-mostly**. Mixed-DB transactions are not
  two-phase committed.
- Character-set conversion: ADB is `AL32UTF8`; PG/Mongo are typically UTF-8.
  Round-trips are clean. Columns/fields wider than the gateway's text cap
  get truncated.
- No `DBMS_HS_PASSTHROUGH` on the Oracle-managed gateway. Use regular SQL.
- ADB egress IPs are not fixed. Don't pin remote firewalls to a single IP.
- Verify what sub-keys a given `db_type` accepts before guessing:
  `SELECT * FROM HETEROGENEOUS_CONNECTIVITY_INFO WHERE DATABASE_TYPE='mongodb';`

### Transient JDBC drops mid-Liquibase (`ORA-17008`)

ADB occasionally drops a JDBC session in the middle of a long Liquibase
run:

```
ERROR: Exception Primary Reason: ORA-17008: Closed connection
Unexpected error running Liquibase: ORA-17008: Closed connection
```

Symptoms, what to check, and how to recover:

- **What happened**: a changeset mid-list ran its DDL (which auto-commits
  in Oracle), but the subsequent `INSERT` into `DATABASECHANGELOG` — the
  tracking table Liquibase uses to remember which changesets have already
  run — never made it before the session died. You end up with a real
  object (a view, say) that Liquibase doesn't think exists.
- **Side effect**: Liquibase also holds a row in `DATABASECHANGELOGLOCK`
  for the duration of its run, and that row was not released. The next
  run aborts with `Could not acquire change log lock. Currently locked
by <host> since <time>`.
- **Recovery**:

  ```bash
  cd /home/opc/ops/database/liquibase/adb
  sudo /usr/local/bin/liquibase --defaults-file=liquibase.properties releaseLocks
  sudo /usr/local/bin/liquibase --defaults-file=liquibase.properties update
  ```

  `releaseLocks` clears the stale `DATABASECHANGELOGLOCK` row; `update`
  picks up from the first changeset that isn't in `DATABASECHANGELOG`.
  All DDL in our changesets uses `CREATE OR REPLACE`, so re-running
  the "already actually applied" changeset is a no-op.

This is why every changeset in `002-db-links.yaml` uses
`CREATE OR REPLACE VIEW` and the credential/link blocks are idempotent
on their own terms (`DROP_CREDENTIAL` / `DROP_DATABASE_LINK` rollbacks
defined) — so a mid-run drop is recoverable without hand-surgery on
ADB.

### Cross-references to §0

- `ORA-20000: Invalid host name ...` when running the link changeset →
  you're passing a raw IP, not an FQDN. See §0.1.
- `ORA-28500 ... [MongoDB] object not found: <collection>` when running
  the Mongo view changeset → your collection is in the `admin` database.
  See §0.2.

---

## References

- [DBMS_CLOUD_ADMIN subprograms](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-admin.html)
- [CREATE_DATABASE_LINK procedure](https://docs.oracle.com/en-us/iaas/autonomous-database/doc/ref-dbms_cloud_admin-create_database_link-procedure.html)
- [Oracle-managed heterogeneous connectivity](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/database-links-other-databases-oracle-managed.html)
- [DBMS_CLOUD.CREATE_CREDENTIAL](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-package-cred.html)
- [ADB private endpoints](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/private-endpoint-configure.html)
