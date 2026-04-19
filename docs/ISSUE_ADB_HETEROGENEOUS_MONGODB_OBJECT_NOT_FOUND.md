# ADB heterogeneous MongoDB gateway — every collection reports "object not found"

Filed: 2026-04-20
Author: Victor Martin (vmleon@gmail.com) — Oracle Database EMEA Platform Technology Solutions

## Summary

Any `SELECT ... FROM "<collection>"@MONGO_LINK` against an Autonomous Database
26ai heterogeneous MongoDB link fails with
`ORA-28500 ... [DataDirect][ODBC MongoDB driver][MongoDB] syntax error or
access rule violation: object not found: <collection> {42S22, NativeErr = -5501}`
**regardless of** whether the collection exists, which MongoDB database it
lives in, what `service_name` the link was created with, or which MongoDB
version the target is running.

The `DBMS_CLOUD_ADMIN.CREATE_CREDENTIAL` and `DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK`
calls both succeed. The symptom is isolated to collection/table resolution
through the link.

Server-side tracing on MongoDB confirms the gateway's DataDirect ODBC driver
**never issues a `listCollections` or `find` against the target MongoDB
database**. The failure occurs inside the Oracle-managed gateway before any
catalog query reaches MongoDB.

This blocks the third engine of a federated-banking POC in the
`oracle-selectai-adb-sidecar-architecture` project. Oracle Free 26ai and
PostgreSQL 18 federation via `DBMS_CLOUD_ADMIN` works end-to-end in the same
deployment; only MongoDB fails.

## Environment

| Component                     | Version                                                                      |
| ----------------------------- | ---------------------------------------------------------------------------- |
| Autonomous Database           | Oracle AI Database 26ai Enterprise Edition Release 23.26.2.1.0 - Production  |
| Heterogeneous gateway address | `pvtnlb.adbs-private.oraclevcn.com:1523` (from `ORA-28511` disclosure)       |
| Gateway service alias         | `SERVICE_NAME=MONGODB` (from `ORA-28511`)                                    |
| Driver reported in error      | `DataDirect ODBC MongoDB driver`                                             |
| MongoDB target (test 1)       | `docker.io/library/mongo:8` (official image), bind-ip `*`, auth enabled      |
| MongoDB target (test 2)       | `docker.io/library/mongo:7` (official image), bind-ip `*`, auth enabled      |
| MongoDB target placement      | Podman on Oracle Linux 9, same VCN as ADB private endpoint, same `db_subnet` |
| MongoDB auth user             | `admin` (created by `MONGO_INITDB_ROOT_USERNAME`/`PASSWORD`), role `root`    |
| Liquibase                     | 4.29.2 (runs the link + view changesets from the ops bastion)                |

ADB is provisioned with a **private endpoint in the same VCN** as the
MongoDB host. The DNS FQDN used for the link (`databases<...>.db.vcn<...>.oraclevcn.com`)
resolves to the target's private IP from the ADB private endpoint.

## Repro

### 1. Create the credential and link

```sql
BEGIN
  DBMS_CLOUD.CREATE_CREDENTIAL(
    credential_name => 'MONGO_CRED',
    username        => 'admin',
    password        => '<root-password>'
  );
END;
/

BEGIN
  DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(
    db_link_name       => 'MONGO_LINK',
    hostname           => 'databases<deploy>.db.vcn<deploy>.oraclevcn.com',
    port               => 27017,
    service_name       => 'banking',                       -- see §3 for variations
    ssl_server_cert_dn => NULL,
    credential_name    => 'MONGO_CRED',
    gateway_params     => JSON_OBJECT('db_type' VALUE 'mongodb'),
    public_link        => FALSE,
    private_target     => TRUE
  );
END;
/
```

Both calls succeed. `USER_DB_LINKS` shows `MONGO_LINK` afterward.

### 2. Seed a trivial collection on the MongoDB side

```javascript
// via mongosh as admin against MongoDB:27017
db.getSiblingDB("banking").createCollection("support_tickets");
db.getSiblingDB("banking").support_tickets.insertMany([
  {
    ticket_id: 1,
    customer: "Alice Morgan",
    subject: "Card declined",
    status: "open",
  },
  {
    ticket_id: 2,
    customer: "Bob Chen",
    subject: "Wire pending",
    status: "in_progress",
  },
]);
db.getSiblingDB("banking").support_tickets.countDocuments({}); // => 2
```

Direct verification from the same bastion (plain mongosh connection, not via
ADB) returns all documents as expected.

### 3. Query the collection through MONGO_LINK from ADB

```sql
SELECT * FROM "support_tickets"@MONGO_LINK WHERE ROWNUM <= 1;
```

**Expected**: one row.

**Actual**:

```
ORA-28500: connection from ORACLE to a non-Oracle system returned this message:
[DataDirect][ODBC MongoDB driver][MongoDB]syntax error or access rule violation:
  object not found: support_tickets {42S22, NativeErr = -5501}
ORA-02063: preceding 2 lines from MONGO_LINK
```

The same error is returned whether the SELECT is issued directly in SQLcl
or wrapped in a `CREATE OR REPLACE VIEW` via Liquibase.

## Variations attempted — all fail with the same `object not found`

| #   | Collection placement            | `service_name` | MongoDB version | MongoDB user setup                                                           | Result                                           |
| --- | ------------------------------- | -------------- | --------------- | ---------------------------------------------------------------------------- | ------------------------------------------------ |
| 1   | `admin.support_tickets`         | `admin`        | 8.x             | `admin` root user in `admin` db                                              | `object not found`                               |
| 2   | `banking.support_tickets`       | `banking`      | 8.x             | `admin` root user in `admin` db only                                         | `object not found`                               |
| 3   | `banking.support_tickets`       | `banking`      | 8.x             | `admin` user **also created in `banking`** with `readWrite`                  | `object not found`                               |
| 4   | `banking.support_tickets`       | `banking`      | 7.x             | `admin` root user in `admin` db                                              | `object not found`                               |
| 5   | `admin.probe` (simple test doc) | `banking`      | 7.x             | Custom role granting `find/insert/update/remove` on `config.system.sessions` | `object not found`                               |
| 6   | After logLevel 3 on Mongo       | `banking`      | 7.x             | (as #5)                                                                      | **`ORA-28511` — lost RPC connection to gateway** |

Variation #3 was to rule out the auth-database-vs-query-database mismatch.
Variation #5 was to rule out authorization on `config.system.sessions`
(see §"MongoDB server view" below). Variation #6 surfaced a gateway crash
that leaked the internal gateway topology.

## MongoDB server view of the gateway's behavior

### Authentication fallback

With `service_name => 'banking'` the DataDirect driver's first auth attempt
targets `db=banking`, which fails (`UserNotFound`), then falls back to
`db=admin`, which succeeds. Relevant server log:

```json
{"c":"ACCESS", "id":5286307, "msg":"Failed to authenticate",
  "attr":{"user":"admin","db":"banking",
          "error":"UserNotFound: Could not find user \"admin\" for db \"banking\""}}
{"c":"ACCESS", "id":5286306, "msg":"Successfully authenticated",
  "attr":{"user":"admin","db":"admin","mechanism":"SCRAM-SHA-256"}}
```

So authentication itself is not the problem.

### The gateway's very first post-auth command

Immediately after successful auth the driver issues:

```json
{"c":"ACCESS", "id":20436, "msg":"Checking authorization failed",
  "attr":{"error":{"code":13,"codeName":"Unauthorized",
    "errmsg":"not authorized on config to execute command {
       aggregate: \"system.sessions\",
       pipeline: [ { $limit: 1000 } ],
       $db: \"config\", ...
    }"}}}
```

The MongoDB `root` role does **not** implicitly grant `find`/`aggregate` on
`config.system.sessions`. Granting a custom role that does (see
`FEDERATED_QUERIES.md`) removes this specific entry from the log but does
not change the `object not found` outcome.

### No `listCollections` / `find` against the target ever appears

Server-side profiling (`db.setProfilingLevel(2)` on `admin` and `banking`)
**and** global verbose command logging (`setParameter: {logLevel: 3}`) were
enabled together and the failing ADB query was re-issued. The only
commands observed from the gateway's source IP were the SASL handshake and
the `config.system.sessions` aggregation above. No `listCollections`, no
`find`, no `aggregate` on the target database. The driver does not appear
to reach a catalog-discovery phase before returning "object not found" to
Oracle.

### ORA-28511 reveals the gateway topology

Re-running the query once with the verbose logLevel active produced:

```
ORA-28511: lost RPC connection to heterogeneous remote agent using
  SID=(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)
       (HOST=pvtnlb.adbs-private.oraclevcn.com)(PORT=1523))
     (CONNECT_DATA=(SERVICE_NAME=MONGODB)
       (HS_SERVICE_ALIAS=4FD7364ECCF28124E0638C13000A6FE5_svc)
       (HS_SERVICE_ALIAS_DIRECTORY=4FC291E7EA3A050AE063B010000A8172/hs_data))
     (SECURITY=(MY_WALLET_DIRECTORY=/u02/nfsad1/gateway_nfs/gateway_wallets/client/wallet)
               (SSL_SERVER_DN_MATCH=TRUE)
               (SSL_SERVER_CERT_DN=CN=gateway)))
```

This indicates the managed gateway process itself died during session
handling. The gateway recovers on subsequent queries but reverts to the
same `object not found` response.

## Things ruled out

- **Network path**: `db.getSiblingDB("banking").support_tickets.countDocuments({})`
  from the ops bastion returns the expected document count; the ADB private
  endpoint and the MongoDB host are in the same VCN. The gateway clearly
  connects and authenticates (server logs confirm).
- **Authentication / authSource**: tested with user in `admin` only,
  in target db only, and in both. No change.
- **Case sensitivity**: collection name quoted as `"support_tickets"` matches
  the Mongo-side casing. Variations with uppercase / qualified names
  (`"banking"."support_tickets"@MONGO_LINK`) return the same error.
- **MongoDB version**: 8.x and 7.x both fail identically.
- **Collection state**: collections tested include an empty one, a
  single-document probe, and a multi-document seeded collection.
- **FQDN vs raw IP**: using the VCN-internal FQDN
  (`<host>.<subnet>.<vcn>.oraclevcn.com`); raw IPv4 was ruled out upstream
  (`ORA-20000: Invalid host name ...` — known guard, unrelated to this bug).
- **gateway_params sub-keys**:
  `SELECT * FROM HETEROGENEOUS_CONNECTIVITY_INFO WHERE database_type = 'mongodb'`
  returns empty `OPTIONAL_PARAMETERS` for mongodb, so there is no
  documented way to override authSource or other driver config from the
  client side.

## What we would like from the ADB team

1. Confirmation of what `service_name` maps to in the DataDirect MongoDB
   ODBC connection string the gateway builds internally — is it the
   MongoDB database name, the authentication database, or something else.
2. The expected MongoDB role / privilege profile for the user named in
   `credential_name`. `root` is clearly insufficient (given the
   `config.system.sessions` authorization failure) but is also not
   documented.
3. Whether the gateway performs schema discovery at link-creation time or
   at query time, and whether there is a cache that must be invalidated.
4. The supported MongoDB major versions for this gateway release.
5. A way to enable the gateway's own client-side trace/log so the failure
   can be diagnosed without `ORA-28500`'s single-line relay.

## Current workaround in this project

- `MONGO_CRED` and `MONGO_LINK` are still created (cheap and harmless, keeps
  the infrastructure in place for re-testing after any gateway update).
- The `V_SUPPORT_TICKETS` changeset has been removed from Liquibase so
  deploys complete cleanly.
- The backend's "Via ADB sidecar" endpoint surfaces a static
  "heterogeneous gateway limitation — see issue doc" note for MongoDB
  instead of a stack trace. Oracle Free and PostgreSQL federation
  (`V_ACCOUNTS`, `V_TRANSACTIONS`, `V_POLICIES`, `V_RULES`) are unaffected
  and return data end-to-end.

## Reproducer repository

`github.com/vmleon/oracle-selectai-adb-sidecar-architecture` (Terraform +
Ansible + Liquibase for the full stack). The MongoDB pieces of interest:

- `database/mongo/init.js` — seeds `banking.support_tickets`
- `database/liquibase/adb/002-db-links.yaml` — `adb-002-cred-mongo` and
  `adb-002-link-mongo` changesets
- `docs/FEDERATED_QUERIES.md` — design doc, includes §0.2 on this bug
