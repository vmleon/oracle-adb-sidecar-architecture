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
- MongoDB — `support_tickets` (in the `admin` database)

ADB surfaces each remote table as a view (`V_ACCOUNTS`, `V_TRANSACTIONS`,
`V_POLICIES`, `V_RULES`, `V_SUPPORT_TICKETS`) so the rest of the stack does
not care where the data physically lives.

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
    hostname        => '&databases_private_ip.',
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
    hostname        => '&databases_private_ip.',
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

Also Oracle-managed heterogeneous, `db_type = 'mongodb'`, port 27017.

```sql
BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name    => 'MONGO_LINK',
    hostname        => '&databases_private_ip.',
    port            => 27017,
    service_name    => 'admin',                   -- auth DB / Mongo database
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

- The container in this POC runs with `MONGO_INITDB_ROOT_USERNAME=admin`; the
  banking `support_tickets` collection is seeded into the `admin` database by
  `database/mongo/init.js`, which is exactly what `service_name` points at.
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
                  hostname        => '${databases_private_ip}',
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

---

## References

- [DBMS_CLOUD_ADMIN subprograms](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-admin.html)
- [CREATE_DATABASE_LINK procedure](https://docs.oracle.com/en-us/iaas/autonomous-database/doc/ref-dbms_cloud_admin-create_database_link-procedure.html)
- [Oracle-managed heterogeneous connectivity](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/database-links-other-databases-oracle-managed.html)
- [DBMS_CLOUD.CREATE_CREDENTIAL](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/dbms-cloud-package-cred.html)
- [ADB private endpoints](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/private-endpoint-configure.html)
